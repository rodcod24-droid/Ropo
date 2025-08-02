package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element

class PelisplusHDProvider : MainAPI() {
    override var mainUrl = "https://pelisplushd.nz"
    override var name = "PelisplusHD"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val items = ArrayList<HomePageList>()
            val document = app.get(
                mainUrl, 
                timeout = 120,
                headers = mapOf("User-Agent" to userAgent)
            ).document
            
            // Try different content selectors
            val contentMaps = listOf(
                "Películas" to listOf(".movies .item", ".peliculas .item", "a.Posters-link"),
                "Series" to listOf(".series .item", ".tv-shows .item", ".series-list .item"),
                "Destacadas" to listOf(".featured .item", ".destacadas .item", ".slider .item")
            )
            
            for ((sectionName, selectors) in contentMaps) {
                for (selector in selectors) {
                    val elements = document.select(selector)
                    if (elements.isNotEmpty()) {
                        val sectionItems = elements.take(20).mapNotNull { element ->
                            element.toSearchResult()
                        }
                        if (sectionItems.isNotEmpty()) {
                            items.add(HomePageList(sectionName, sectionItems))
                            break
                        }
                    }
                }
            }
            
            // Fallback - get any content
            if (items.isEmpty()) {
                val fallbackSelectors = listOf(
                    "a.Posters-link",
                    ".content .item",
                    ".movie-item",
                    "article.item"
                )
                
                for (selector in fallbackSelectors) {
                    val elements = document.select(selector)
                    if (elements.isNotEmpty()) {
                        val fallbackItems = elements.take(30).mapNotNull { element ->
                            element.toSearchResult()
                        }
                        if (fallbackItems.isNotEmpty()) {
                            items.add(HomePageList("Contenido", fallbackItems))
                            break
                        }
                    }
                }
            }
            
            newHomePageResponse(items.ifEmpty { 
                listOf(HomePageList("No Content", emptyList())) 
            })
        } catch (e: Exception) {
            logError(e)
            newHomePageResponse(listOf(HomePageList("Error", emptyList())))
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        return try {
            // Get title from various possible selectors
            val title = this.selectFirst("h2, h3, .title, .listing-content p")?.text()?.trim()
                ?: this.attr("title").takeIf { it.isNotEmpty() }
                ?: return null
            
            // Get link
            val href = this.selectFirst("a")?.attr("href")
                ?: this.attr("href").takeIf { it.isNotEmpty() }
                ?: return null
            
            val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
            
            // Get poster image
            val posterUrl = this.selectFirst("img")?.run {
                listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attr ->
                    attr(attr).takeIf { it.isNotEmpty() && !it.contains("data:image") }
                }
            }
            
            val isMovie = fullHref.contains("/pelicula/") || 
                         fullHref.contains("/movie/") || 
                         !fullHref.contains("/serie/")
            
            if (isMovie) {
                newMovieSearchResponse(title, fullHref) {
                    this.posterUrl = posterUrl
                }
            } else {
                newTvSeriesSearchResponse(title, fullHref) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrls = listOf(
                "$mainUrl/?s=${query.replace(" ", "+")}",
                "$mainUrl/search?q=${query.replace(" ", "+")}",
                "$mainUrl/buscar?s=${query.replace(" ", "+")}"
            )
            
            for (url in searchUrls) {
                try {
                    val document = app.get(
                        url, 
                        timeout = 120,
                        headers = mapOf("User-Agent" to userAgent)
                    ).document
                    
                    val contentSelectors = listOf(
                        "a.Posters-link",
                        ".search-results .item",
                        ".movies .item",
                        ".content .item",
                        "article.item"
                    )
                    
                    for (selector in contentSelectors) {
                        val results = document.select(selector).mapNotNull { element ->
                            element.toSearchResult()
                        }
                        
                        if (results.isNotEmpty()) return results
                    }
                } catch (e: Exception) {
                    logError(e)
                    continue
                }
            }
            
            emptyList()
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val soup = app.get(
                url, 
                timeout = 120,
                headers = mapOf("User-Agent" to userAgent)
            ).document

            // Get title from multiple selectors
            val title = soup.selectFirst("h1, .title, .movie-title")?.text()?.trim() ?: return null
            
            // Get description
            val description = soup.selectFirst(".description, .synopsis, .overview, .plot")?.text()?.trim()
            
            // Get poster
            val poster = soup.selectFirst("img.poster, .movie-poster img, img")?.run {
                listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attr ->
                    attr(attr).takeIf { it.isNotEmpty() && !it.contains("data:image") }
                }
            }
            
            // Extract year
            val yearText = soup.selectFirst(".year, .date, .release-date")?.text() ?: soup.text()
            val year = Regex("(19|20)\\d{2}").find(yearText)?.value?.toIntOrNull()
            
            // Get episodes for series
            val episodes = soup.select(".episodes .episode, .season .episode, .episode-list li").mapNotNull { li ->
                try {
                    val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                    
                    val name = li.selectFirst(".episode-title, .title")?.text()?.trim()
                    val numberText = li.selectFirst(".episode-number, .number")?.text() ?: href
                    
                    // Parse season and episode numbers
                    val seasonEpisodePattern = Regex("temporada[/-]?(\\d+)[/-]?.*?capitulo[/-]?(\\d+)|s(\\d+)e(\\d+)|(\\d+)x(\\d+)")
                    val match = seasonEpisodePattern.find(numberText.lowercase())
                    
                    val season = match?.let { 
                        it.groupValues[1].toIntOrNull() ?: 
                        it.groupValues[3].toIntOrNull() ?: 
                        it.groupValues[5].toIntOrNull() ?: 1
                    } ?: 1
                    
                    val episode = match?.let {
                        it.groupValues[2].toIntOrNull() ?: 
                        it.groupValues[4].toIntOrNull() ?: 
                        it.groupValues[6].toIntOrNull()
                    }
                    
                    newEpisode(fullHref) {
                        this.name = name
                        this.season = season
                        this.episode = episode
                    }
                } catch (e: Exception) {
                    logError(e)
                    null
                }
            }

            // Get genres/tags
            val tags = soup.select(".genres a, .genre, .tags a").map { 
                it.text().trim() 
            }.filter { it.isNotEmpty() }

            val tvType = if (url.contains("/pelicula/") || url.contains("/movie/") || episodes.isEmpty()) {
                TvType.Movie
            } else {
                TvType.TvSeries
            }
            
            when (tvType) {
                TvType.TvSeries -> {
                    newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                        this.posterUrl = poster
                        this.year = year
                        this.plot = description
                        this.tags = tags
                    }
                }
                TvType.Movie -> {
                    newMovieLoadResponse(title, url, TvType.Movie, url) {
                        this.posterUrl = poster
                        this.year = year
                        this.plot = description
                        this.tags = tags
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        return@coroutineScope try {
            val doc = app.get(
                data, 
                timeout = 120,
                headers = mapOf("User-Agent" to userAgent)
            ).document
            
            val tasks = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
            
            // Look for iframes with video content
            val iframes = doc.select("iframe, .player iframe, .video-player iframe")
            
            iframes.forEach { iframe ->
                tasks.add(async {
                    try {
                        val iframeUrl = iframe.attr("data-src").takeIf { it.isNotEmpty() }
                            ?: iframe.attr("src").takeIf { it.isNotEmpty() }
                            ?: return@async
                        
                        val fullUrl = if (iframeUrl.startsWith("http")) iframeUrl else "$mainUrl$iframeUrl"
                        loadExtractor(fullUrl, data, subtitleCallback, callback)
                    } catch (e: Exception) {
                        logError(e)
                    }
                })
            }
            
            // Also look for script tags that might contain video URLs
            val scripts = doc.select("script")
            scripts.forEach { script ->
                tasks.add(async {
                    try {
                        val scriptContent = script.data()
                        if (scriptContent.contains("http") && (scriptContent.contains(".mp4") || scriptContent.contains(".m3u8"))) {
                            // Extract URLs from script
                            val urlPattern = Regex("(?:\"|\')([^\"\']*(?:\\.mp4|\\.m3u8)[^\"\']*?)(?:\"|\')")
                            val matches = urlPattern.findAll(scriptContent)
                            
                            matches.forEach { match ->
                                val url = match.groupValues[1]
                                if (url.startsWith("http")) {
                                    loadExtractor(url, data, subtitleCallback, callback)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logError(e)
                    }
                })
            }
            
            tasks.awaitAll()
            true
        } catch (e: Exception) {
            logError(e)
            false
        }
    }
}
