package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class EntrepeliculasyseriesProvider : MainAPI() {
    override var mainUrl = "https://entrepeliculasyseries.nz"
    override var name = "EntrePeliculasySeries"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        Pair("$mainUrl/series/page/", "Series"),
        Pair("$mainUrl/peliculas/page/", "Peliculas"),
        Pair("$mainUrl/anime/page/", "Animes"),
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        try {
            val url = request.data + page
            val soup = app.get(url, timeout = 120).document
            
            // Try multiple selectors for content items
            val contentSelectors = listOf(
                "ul.list-movie li",
                ".movie-list li",
                ".content-item",
                "article.TPost",
                ".TPost"
            )
            
            var home: List<SearchResponse> = emptyList()
            
            for (selector in contentSelectors) {
                val elements = soup.select(selector)
                if (elements.isNotEmpty()) {
                    home = elements.mapNotNull { element ->
                        try {
                            // Try multiple selectors for title
                            val title = element.selectFirst("a.link-title h2, h2, h3, .title, .Title")?.text()?.trim()
                                ?: return@mapNotNull null
                            
                            // Try multiple selectors for link
                            val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                            val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                            
                            // Try multiple selectors for poster
                            val posterImg = element.selectFirst("a.poster img, img")?.run {
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

            return newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            logError(e)
            return newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val searchUrls = listOf(
                "$mainUrl/?s=$query",
                "$mainUrl/search?s=$query"
            )
            
            for (url in searchUrls) {
                try {
                    val document = app.get(url, timeout = 120).document
                    
                    val contentSelectors = listOf(
                        "li.xxx.TPostMv",
                        ".TPost",
                        ".search-result",
                        ".movie-item",
                        ".content-item"
                    )
                    
                    for (selector in contentSelectors) {
                        val results = document.select(selector).mapNotNull { element ->
                            try {
                                val title = element.selectFirst("h2.Title, h2, h3, .title")?.text()?.trim()
                                    ?: return@mapNotNull null
                                val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                                val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                                
                                val image = element.selectFirst("img.lazy, img")?.run {
                                    attr("data-src").takeIf { it.isNotEmpty() }
                                        ?: attr("src").takeIf { it.isNotEmpty() }
                                }
                                
                                val isMovie = fullHref.contains("/pelicula/") || fullHref.contains("/movie/")
                                
                                if (isMovie) {
                                    newMovieSearchResponse(title, fullHref) {
                                        this.posterUrl = image
                                    }
                                } else {
                                    newTvSeriesSearchResponse(title, fullHref) {
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
        } catch (e: Exception) {
            logError(e)
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val soup = app.get(url, timeout = 120).document

            // Try multiple selectors for title
            val title = soup.selectFirst("h1.title-post, h1, .title, .post-title")?.text()?.trim()
                ?: return null
            
            // Try multiple selectors for description
            val description = soup.selectFirst("p.text-content:nth-child(3), .description, .overview, .plot")?.text()?.trim()
            
            // Try multiple selectors for poster
            val poster = soup.selectFirst("article.TPost img.lazy, .poster img, img")?.run {
                attr("data-src").takeIf { it.isNotEmpty() }
                    ?: attr("src").takeIf { it.isNotEmpty() }
            }
            
            // Extract episodes for series
            val episodeSelectors = listOf(
                ".TPostMv article",
                ".episode-list article",
                ".episodes-container .episode",
                ".season-episodes .episode"
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
                            
                            val epThumb = li.selectFirst("div.Image img, img")?.run {
                                attr("data-src").takeIf { it.isNotEmpty() }
                                    ?: attr("src").takeIf { it.isNotEmpty() }
                            }
                            
                            val seasonEpisodeText = li.selectFirst("span.Year, .episode-number")?.text() ?: ""
                            val seasonEpisode = seasonEpisodeText.split("x", "X", "-").mapNotNull { subStr -> 
                                subStr.replace(Regex("[^\\d]"), "").toIntOrNull() 
                            }
                            
                            val isValid = seasonEpisode.size >= 2
                            val episode = if (isValid) seasonEpisode.getOrNull(1) else null
                            val season = if (isValid) seasonEpisode.getOrNull(0) else null
                            
                            newEpisode(fullHref) {
                                this.season = season
                                this.episode = episode
                                this.posterUrl = if (epThumb?.contains("svg") == true) null else epThumb
                            }
                        } catch (e: Exception) {
                            logError(e)
                            null
                        }
                    }
                    if (episodes.isNotEmpty()) break
                }
            }
            
            val tvType = if (url.contains("/pelicula/") || url.contains("/movie/")) TvType.Movie else TvType.TvSeries
            
            return when (tvType) {
                TvType.TvSeries -> {
                    newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                        this.posterUrl = poster
                        this.plot = description
                    }
                }
                TvType.Movie -> {
                    newMovieLoadResponse(title, url, TvType.Movie, url) {
                        this.posterUrl = poster
                        this.plot = description
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }

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
            
            val iframeTasks = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
            
            for (selector in iframeSelectors) {
                val iframes = doc.select(selector)
                if (iframes.isNotEmpty()) {
                    iframes.forEach { iframe ->
                        iframeTasks.add(async {
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
                    break
                }
            }
            
            iframeTasks.awaitAll()
            true
        } catch (e: Exception) {
            logError(e)
            false
        }
    }
}
