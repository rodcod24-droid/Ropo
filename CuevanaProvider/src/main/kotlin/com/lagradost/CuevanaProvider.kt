package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class CuevanaProvider : MainAPI() {
    override var mainUrl = "https://cuevana.pro"
    override var name = "Cuevana"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        
        try {
            // Get main page content with proper headers
            val document = app.get(
                mainUrl, 
                timeout = 120,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
                )
            ).document
            
            // Try different selectors for movies
            val movieSelectors = listOf(
                "div.MovieList .TPostMv",
                ".movies .item",
                ".content .item",
                "article.item",
                ".TPostMv"
            )
            
            for (selector in movieSelectors) {
                val movies = document.select(selector).take(20).mapNotNull { element ->
                    try {
                        val titleElement = element.selectFirst("h2.Title, h3.Title, .title, h2, h3")
                        val title = titleElement?.text()?.trim() ?: return@mapNotNull null
                        
                        val linkElement = element.selectFirst("a") ?: titleElement?.parent()?.selectFirst("a") 
                        val link = linkElement?.attr("href") ?: return@mapNotNull null
                        val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                        
                        val posterElement = element.selectFirst("img")
                        val poster = posterElement?.run {
                            listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attr ->
                                attr(attr).takeIf { it.isNotEmpty() && !it.contains("data:image") }
                            }
                        }
                        
                        if (fullLink.contains("/pelicula/") || fullLink.contains("/movie/")) {
                            newMovieSearchResponse(title, fullLink) {
                                this.posterUrl = poster
                            }
                        } else {
                            newTvSeriesSearchResponse(title, fullLink) {
                                this.posterUrl = poster
                            }
                        }
                    } catch (e: Exception) {
                        logError(e)
                        null
                    }
                }
                
                if (movies.isNotEmpty()) {
                    items.add(HomePageList("Películas Populares", movies))
                    break
                }
            }
            
            // Try to get series
            try {
                val seriesUrls = listOf("$mainUrl/serie", "$mainUrl/series")
                for (seriesUrl in seriesUrls) {
                    try {
                        val seriesDoc = app.get(
                            seriesUrl, 
                            timeout = 120,
                            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        ).document
                        
                        val series = seriesDoc.select(".TPostMv, .item, article.item").take(20).mapNotNull { element ->
                            try {
                                val title = element.selectFirst("h2.Title, h3.Title, .title, h2, h3")?.text()?.trim() 
                                    ?: return@mapNotNull null
                                val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                                val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                                val poster = element.selectFirst("img")?.run {
                                    listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attr ->
                                        attr(attr).takeIf { it.isNotEmpty() && !it.contains("data:image") }
                                    }
                                }
                                
                                newTvSeriesSearchResponse(title, fullLink) {
                                    this.posterUrl = poster
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }
                        
                        if (series.isNotEmpty()) {
                            items.add(HomePageList("Series", series))
                            break
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            } catch (e: Exception) {
                logError(e)
            }
            
        } catch (e: Exception) {
            logError(e)
        }

        if (items.isEmpty()) {
            // Return empty list instead of throwing exception
            return HomePageResponse(listOf(HomePageList("No Content", emptyList())))
        }
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrls = listOf(
                "$mainUrl/?s=$query",
                "$mainUrl/search?s=$query"
            )
            
            for (url in searchUrls) {
                try {
                    val document = app.get(
                        url, 
                        timeout = 120,
                        headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    ).document
                    
                    val results = document.select(".TPostMv, .item, .search-item, article.item").mapNotNull { element ->
                        try {
                            val title = element.selectFirst("h2.Title, h3.Title, .title, h2, h3")?.text()?.trim() 
                                ?: return@mapNotNull null
                            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                            val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                            val image = element.selectFirst("img")?.run {
                                listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attr ->
                                    attr(attr).takeIf { it.isNotEmpty() && !it.contains("data:image") }
                                }
                            }
                            
                            val isSerie = fullHref.contains("/serie/") || fullHref.contains("/series/")
                            
                            if (isSerie) {
                                newTvSeriesSearchResponse(title, fullHref) {
                                    this.posterUrl = image
                                }
                            } else {
                                newMovieSearchResponse(title, fullHref) {
                                    this.posterUrl = image
                                }
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    
                    if (results.isNotEmpty()) return results
                } catch (e: Exception) {
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
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            ).document
            
            val title = soup.selectFirst("h1.Title, h1, .entry-title")?.text()?.trim() ?: return null
            val description = soup.selectFirst(".Description p, .description, .wp-content p")?.text()?.trim()
            val poster = soup.selectFirst(".movtv-info img, .poster img, .wp-post-image")?.run {
                listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attr ->
                    attr(attr).takeIf { it.isNotEmpty() && !it.contains("data:image") }
                }
            }
            
            // Extract year from various possible locations
            val yearText = soup.selectFirst(".date, .year, .meta, footer p")?.text() ?: ""
            val year = Regex("(19|20)\\d{2}").find(yearText)?.value?.toIntOrNull()
            
            // Extract episodes for series
            val episodes = soup.select(".episodios .item, .episodes .episode, .TPostMv").mapNotNull { li ->
                try {
                    val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                    
                    val epThumb = li.selectFirst("img")?.run {
                        listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attr ->
                            attr(attr).takeIf { it.isNotEmpty() && !it.contains("data:image") }
                        }
                    }
                    
                    val episodeText = li.selectFirst(".numerando, .episode-number, .num")?.text() ?: ""
                    val episodeName = li.selectFirst("h3, .title, .episode-title")?.text()?.trim()
                    
                    // Try to parse season and episode
                    val seasonEpisodePattern = Regex("(\\d+)[-x](\\d+)")
                    val match = seasonEpisodePattern.find(episodeText)
                    val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val episode = match?.groupValues?.get(2)?.toIntOrNull()
                    
                    newEpisode(fullHref) {
                        this.name = episodeName
                        this.season = season
                        this.episode = episode
                        this.posterUrl = epThumb
                    }
                } catch (e: Exception) {
                    null
                }
            }
            
            val tags = soup.select(".genres a, .genre a, .meta a").map { it.text().trim() }
            val tvType = if (episodes.isEmpty()) TvType.Movie else TvType.TvSeries
            
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
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            ).document
            
            val tasks = doc.select("iframe, .player iframe, .video-player iframe").map { iframe ->
                async {
                    try {
                        val iframeUrl = iframe.attr("data-src").takeIf { it.isNotEmpty() }
                            ?: iframe.attr("src").takeIf { it.isNotEmpty() }
                            ?: return@async
                        
                        val fullUrl = if (iframeUrl.startsWith("http")) iframeUrl else "$mainUrl$iframeUrl"
                        loadExtractor(fullUrl, data, subtitleCallback, callback)
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
            
            tasks.awaitAll()
            true
        } catch (e: Exception) {
            logError(e)
            false
        }
    }
}
