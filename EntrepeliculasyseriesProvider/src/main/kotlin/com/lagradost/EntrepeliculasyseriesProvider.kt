package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("$mainUrl/series/page/$page", "Series"),
            Pair("$mainUrl/peliculas/page/$page", "Peliculas"),
            Pair("$mainUrl/anime/page/$page", "Animes"),
        )
        
        for ((url, name) in urls) {
            try {
                val soup = app.get(url, timeout = 120).document
                
                // Try different selectors for content
                val contentSelectors = listOf(
                    "ul.list-movie li",
                    ".movies-list li",
                    "article.TPost",
                    ".content-item",
                    ".movie-item"
                )
                
                var home: List<SearchResponse> = emptyList()
                
                for (selector in contentSelectors) {
                    val elements = soup.select(selector)
                    if (elements.isNotEmpty()) {
                        home = elements.mapNotNull { element ->
                            try {
                                // Try multiple title selectors
                                val title = element.selectFirst("a.link-title h2, h2.Title, h2, h3, .title")?.text()?.trim()
                                    ?: return@mapNotNull null
                                
                                val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                                val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                                
                                // Try multiple poster selectors
                                val posterImg = element.selectFirst("a.poster img, img.lazy, img")?.run {
                                    attr("data-src").takeIf { it.isNotEmpty() }
                                        ?: attr("data-lazy-src").takeIf { it.isNotEmpty() }
                                        ?: attr("src").takeIf { it.isNotEmpty() }
                                }
                                
                                if (fullLink.contains("/pelicula/")) {
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

        return newHomePageResponse(items.ifEmpty { 
            listOf(HomePageList("No content found", emptyList())) 
        })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val url = "$mainUrl/?s=$query"
            val document = app.get(url, timeout = 120).document

            val contentSelectors = listOf(
                "li.xxx.TPostMv",
                ".search-result",
                ".movie-item",
                "article.TPost",
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
                        
                        val isMovie = fullHref.contains("/pelicula/")
                        
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
            
            return emptyList()
        } catch (e: Exception) {
            logError(e)
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val soup = app.get(url, timeout = 120).document

            val title = soup.selectFirst("h1.title-post, h1, .title")?.text()?.trim() ?: return null
            val description = soup.selectFirst("p.text-content:nth-child(3), .description, .overview")?.text()?.trim()
            val poster = soup.selectFirst("article.TPost img.lazy, .poster img, img")?.run {
                attr("data-src").takeIf { it.isNotEmpty() } ?: attr("src")
            }
            
            // Extract episodes for series
            val episodes = soup.select(".TPostMv article, .episodes-list .episode, .season-episodes li").mapNotNull { li ->
                try {
                    val href = li.select("a").attr("href")
                    if (href.isEmpty()) return@mapNotNull null
                    val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                    
                    val epThumb = li.selectFirst("div.Image img, img")?.run {
                        attr("data-src").takeIf { it.isNotEmpty() } ?: attr("src")
                    }
                    
                    val seasonEpisodeText = li.selectFirst("span.Year, .episode-number")?.text() ?: ""
                    val seasonEpisode = seasonEpisodeText.split("x").mapNotNull { subStr -> 
                        subStr.toIntOrNull() 
                    }
                    val isValid = seasonEpisode.size == 2
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
            
            val tvType = if (url.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
            
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
    ): Boolean {
        try {
            val doc = app.get(data, timeout = 120).document
            doc.select("div.TPlayer.embed_div iframe, .player iframe, iframe").forEach { iframe ->
                try {
                    val iframeUrl = iframe.attr("data-src").takeIf { it.isNotEmpty() }
                        ?: iframe.attr("src")
                    if (iframeUrl.isNotEmpty()) {
                        val fullUrl = if (iframeUrl.startsWith("http")) iframeUrl else "$mainUrl$iframeUrl"
                        loadExtractor(fullUrl, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return true
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }
}
