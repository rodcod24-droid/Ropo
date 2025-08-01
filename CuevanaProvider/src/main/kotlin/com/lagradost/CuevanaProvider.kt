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
        val urls = listOf(
            Pair(mainUrl, "Destacadas"),
            Pair("$mainUrl/peliculas", "Películas"),
            Pair("$mainUrl/series", "Series"),
        )

        for ((url, name) in urls) {
            try {
                val soup = app.get(url, timeout = 120).document
                
                // Try multiple possible selectors for content items
                val contentSelectors = listOf(
                    "article.TPost",
                    ".TPost",
                    ".movie-item",
                    ".content-item",
                    "div[class*='post']",
                    ".item",
                    "li.xxx.TPostMv"
                )
                
                var home: List<SearchResponse> = emptyList()
                
                for (selector in contentSelectors) {
                    val elements = soup.select(selector)
                    if (elements.isNotEmpty()) {
                        home = elements.mapNotNull { element ->
                            try {
                                // Try multiple selectors for title
                                val title = element.selectFirst("h1, h2, h3, .title, .Title, .post-title")?.text()?.trim()
                                    ?: return@mapNotNull null
                                
                                // Try multiple selectors for link
                                val link = element.selectFirst("a")?.attr("href")
                                    ?: return@mapNotNull null
                                
                                val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                                
                                // Try multiple selectors for poster
                                val posterImg = element.selectFirst("img")?.run {
                                    attr("data-src").takeIf { it.isNotEmpty() }
                                        ?: attr("data-lazy-src").takeIf { it.isNotEmpty() }
                                        ?: attr("src").takeIf { it.isNotEmpty() }
                                }
                                
                                if (fullLink.contains("/pelicula/") || fullLink.contains("/movie/")) {
                                    newMovieSearchResponse(title, fullLink) {
                                        this.posterUrl = posterImg
                                    }
                                } else {
                                    newTvSeriesSearchResponse(title, fullLink) {
                                        this.posterUrl = posterImg
                                    }
                                }
                            } catch (e: Exception) {
                                logError(e)
                                null
                            }
                        }
                        if (home.isNotEmpty()) break
                    }
                }
                
                if (home.isNotEmpty()) {
                    items.add(HomePageList(name, home))
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        if (items.isEmpty()) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrls = listOf(
            "$mainUrl/search?s=$query",
            "$mainUrl/?s=$query"
        )
        
        for (url in searchUrls) {
            try {
                val document = app.get(url, timeout = 120).document
                
                val contentSelectors = listOf(
                    "article.TPost",
                    ".TPost",
                    "li.xxx.TPostMv",
                    ".movie-item",
                    ".search-item"
                )
                
                for (selector in contentSelectors) {
                    val results = document.select(selector).mapNotNull { element ->
                        try {
                            val title = element.selectFirst("h1, h2, h3, .title, .Title")?.text()?.trim()
                                ?: return@mapNotNull null
                            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                            val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                            
                            val image = element.selectFirst("img")?.run {
                                attr("data-src").takeIf { it.isNotEmpty() }
                                    ?: attr("data-lazy-src").takeIf { it.isNotEmpty() }
                                    ?: attr("src").takeIf { it.isNotEmpty() }
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
                            logError(e)
                            null
                        }
                    }
                    
                    if (results.isNotEmpty()) return results
                }
            } catch (e: Exception) {
                logError(e)
            }
        }
        
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val soup = app.get(url, timeout = 120).document
            
            // Try multiple selectors for title
            val title = soup.selectFirst("h1.Title, h1, .title, .post-title, .movie-title")?.text()?.trim()
                ?: return null
            
            // Try multiple selectors for description
            val description = soup.selectFirst(".Description p, .description, .overview, .plot")?.text()?.trim()
            
            // Try multiple selectors for poster
            val poster = soup.selectFirst("img.lazy, .poster img, .movie-poster img, img")?.run {
                attr("data-src").takeIf { it.isNotEmpty() }
                    ?: attr("data-lazy-src").takeIf { it.isNotEmpty() }
                    ?: attr("src").takeIf { it.isNotEmpty() }
            }
            
            // Extract year
            val yearText = soup.selectFirst(".year, .date, .release-date, footer p.meta")?.text() ?: ""
            val year = Regex("(\\d{4})").find(yearText)?.value?.toIntOrNull()
            
            // Extract episodes for series
            val episodeSelectors = listOf(
                ".all-episodes li.TPostMv article",
                ".episodes-list .episode",
                ".season-episodes .episode",
                ".episode-list li"
            )
            
            var episodes: List<Episode> = emptyList()
            
            for (selector in episodeSelectors) {
                val episodeElements = soup.select(selector)
                if (episodeElements.isNotEmpty()) {
                    episodes = episodeElements.mapNotNull { li ->
                        try {
                            val href = li.select("a").attr("href")
                            if (href.isEmpty()) return@mapNotNull null
                            
                            val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                            
                            val epThumb = li.selectFirst("img")?.run {
                                attr("data-src").takeIf { it.isNotEmpty() }
                                    ?: attr("src").takeIf { it.isNotEmpty() }
                            }
                            
                            val seasonEpisodeText = li.selectFirst(".episode-number, .Year, .episode-info")?.text() ?: ""
                            
                            // Parse season and episode numbers
                            val seasonEpisode = seasonEpisodeText.split("x", "X", "-").mapNotNull { 
                                it.replace(Regex("[^\\d]"), "").toIntOrNull() 
                            }
                            
                            val season = seasonEpisode.getOrNull(0)
                            val episode = seasonEpisode.getOrNull(1)
                            
                            newEpisode(fullHref) {
                                this.season = season
                                this.episode = episode
                                this.posterUrl = epThumb?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
                            }
                        } catch (e: Exception) {
                            logError(e)
                            null
                        }
                    }
                    if (episodes.isNotEmpty()) break
                }
            }
            
            val tags = soup.select(".genres a, .genre a, .category a").map { it.text() }
            val tvType = if (episodes.isEmpty()) TvType.Movie else TvType.TvSeries
            
            return when (tvType) {
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
            return null
        }
    }

    data class Femcuevana(
        @JsonProperty("url") val url: String,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        try {
            val doc = app.get(data, timeout = 120).document
            
            // Try multiple selectors for iframes
            val iframeSelectors = listOf(
                "div.TPlayer.embed_div iframe",
                ".player iframe",
                ".video-player iframe",
                "iframe[src*='embed']",
                "iframe"
            )
            
            var iframes: List<org.jsoup.nodes.Element> = emptyList()
            
            for (selector in iframeSelectors) {
                iframes = doc.select(selector)
                if (iframes.isNotEmpty()) break
            }
            
            iframes.map { iframe ->
                async {
                    try {
                        val iframeUrl = iframe.attr("data-src").takeIf { it.isNotEmpty() }
                            ?: iframe.attr("src").takeIf { it.isNotEmpty() }
                            ?: return@async
                        
                        val fullUrl = if (iframeUrl.startsWith("http")) iframeUrl else "$mainUrl$iframeUrl"
                        
                        // Load the iframe content and extract video sources
                        loadExtractor(fullUrl, data, subtitleCallback, callback)
                        
                        // Also try to extract direct video links from the iframe
                        try {
                            val iframeDoc = app.get(fullUrl, timeout = 30).document
                            val videoSources = iframeDoc.select("source, video").mapNotNull { source ->
                                source.attr("src").takeIf { it.isNotEmpty() }
                            }
                            
                            videoSources.forEach { videoUrl ->
                                val fullVideoUrl = if (videoUrl.startsWith("http")) videoUrl else "$mainUrl$videoUrl"
                                loadExtractor(fullVideoUrl, data, subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                            // Continue if iframe content can't be loaded
                        }
                        
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }.awaitAll()
            
            true
        } catch (e: Exception) {
            logError(e)
            false
        }
    }
}
