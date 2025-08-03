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
    override var mainUrl = "https://w3vn.cuevana.pro"
    override var name = "Cuevana"
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
        val items = ArrayList<HomePageList>()
        
        try {
            // Get Movies Section
            try {
                val moviesPage = app.get("$mainUrl/peliculas", timeout = 120, headers = mapOf("User-Agent" to userAgent)).document
                val moviesList = moviesPage.select("article.TPost, .MovieList article, .TPostMv").take(20).mapNotNull { element ->
                    try {
                        val title = element.selectFirst("h2.Title, .title, h3")?.text()?.trim() ?: return@mapNotNull null
                        val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                        val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                        val poster = element.selectFirst("img")?.run {
                            attr("data-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                                ?: attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                                ?: attr("src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                        }
                        
                        newMovieSearchResponse(title, fullLink) {
                            this.posterUrl = poster
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                
                if (moviesList.isNotEmpty()) {
                    items.add(HomePageList("Películas", moviesList))
                }
            } catch (e: Exception) {
                logError(e)
            }
            
            // Get Series Section
            try {
                val seriesPage = app.get("$mainUrl/serie", timeout = 120, headers = mapOf("User-Agent" to userAgent)).document
                val seriesList = seriesPage.select("article.TPost, .MovieList article, .TPostMv").take(20).mapNotNull { element ->
                    try {
                        val title = element.selectFirst("h2.Title, .title, h3")?.text()?.trim() ?: return@mapNotNull null
                        val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                        val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                        val poster = element.selectFirst("img")?.run {
                            attr("data-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                                ?: attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                                ?: attr("src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                        }
                        
                        newTvSeriesSearchResponse(title, fullLink) {
                            this.posterUrl = poster
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                
                if (seriesList.isNotEmpty()) {
                    items.add(HomePageList("Series", seriesList))
                }
            } catch (e: Exception) {
                logError(e)
            }
            
            // Get Latest Releases
            try {
                val mainPage = app.get(mainUrl, timeout = 120, headers = mapOf("User-Agent" to userAgent)).document
                val latestList = mainPage.select("article.TPost, .MovieList article, .TPostMv").take(15).mapNotNull { element ->
                    try {
                        val title = element.selectFirst("h2.Title, .title, h3")?.text()?.trim() ?: return@mapNotNull null
                        val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                        val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                        val poster = element.selectFirst("img")?.run {
                            attr("data-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                                ?: attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                                ?: attr("src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                        }
                        
                        if (fullLink.contains("/pelicula/") || fullLink.contains("/movies/")) {
                            newMovieSearchResponse(title, fullLink) {
                                this.posterUrl = poster
                            }
                        } else {
                            newTvSeriesSearchResponse(title, fullLink) {
                                this.posterUrl = poster
                            }
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                
                if (latestList.isNotEmpty()) {
                    items.add(HomePageList("Recientes", latestList))
                }
            } catch (e: Exception) {
                logError(e)
            }
            
        } catch (e: Exception) {
            logError(e)
        }

        return newHomePageResponse(items.ifEmpty { 
            listOf(HomePageList("No Content", emptyList())) 
        })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
            val document = app.get(searchUrl, timeout = 120, headers = mapOf("User-Agent" to userAgent)).document
            
            document.select("article.TPost, .MovieList article, .TPostMv").mapNotNull { element ->
                try {
                    val title = element.selectFirst("h2.Title, .title, h3")?.text()?.trim() ?: return@mapNotNull null
                    val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                    val image = element.selectFirst("img")?.run {
                        attr("data-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                            ?: attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                            ?: attr("src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
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
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val soup = app.get(url, timeout = 120, headers = mapOf("User-Agent" to userAgent)).document
            
            val title = soup.selectFirst("h1.Title, h1, .entry-title")?.text()?.trim() ?: return null
            val description = soup.selectFirst(".Description p, .wp-content p, .synopsis")?.text()?.trim()
            val poster = soup.selectFirst(".movtv-info img, .poster img, .wp-post-image")?.run {
                attr("data-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                    ?: attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                    ?: attr("src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
            }
            
            // Extract year
            val yearText = soup.selectFirst(".date, .year, .meta")?.text() ?: soup.text()
            val year = Regex("(19|20)\\d{2}").find(yearText)?.value?.toIntOrNull()
            
            // Extract episodes for series
            val episodes = soup.select(".episodios .item, .all-episodes .TPostMv, .episodes .episode").mapNotNull { li ->
                try {
                    val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                    
                    val epThumb = li.selectFirst("img")?.run {
                        attr("data-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                            ?: attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                            ?: attr("src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                    }
                    
                    val episodeText = li.selectFirst(".numerando, .episode-number, .num, .Year")?.text() ?: ""
                    val episodeName = li.selectFirst("h3, .title, .episode-title")?.text()?.trim()
                    
                    // Parse season and episode
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
        try {
            val doc = app.get(data, timeout = 120, headers = mapOf("User-Agent" to userAgent)).document
            
            val tasks = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
            
            // Method 1: Look for player options with data-tplayernv attribute
            doc.select("[data-tplayernv], .TPlayerNv").forEach { option ->
                tasks.add(async {
                    try {
                        val optionValue = option.attr("data-tplayernv")
                        if (optionValue.isNotEmpty()) {
                            val playerUrl = "$mainUrl/wp-json/dooplayer/v1/$optionValue"
                            try {
                                val playerResponse = app.get(playerUrl, headers = mapOf("User-Agent" to userAgent))
                                val playerData = parseJson<PlayerResponse>(playerResponse.text)
                                if (playerData.embed_url.isNotEmpty()) {
                                    loadExtractor(playerData.embed_url, data, subtitleCallback, callback)
                                }
                            } catch (parseException: Exception) {
                                val playerDoc = app.get(playerUrl, headers = mapOf("User-Agent" to userAgent)).document
                                val iframe = playerDoc.selectFirst("iframe")
                                if (iframe != null) {
                                    val iframeSrc = iframe.attr("src")
                                    if (iframeSrc.startsWith("http")) {
                                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Continue processing other options
                    }
                })
            }
            
            // Method 2: Look for dooplay player options
            doc.select(".dooplay_player_option, [data-option]").forEach { option ->
                tasks.add(async {
                    try {
                        val optionUrl = option.attr("data-option")
                        if (optionUrl.startsWith("http")) {
                            loadExtractor(optionUrl, data, subtitleCallback, callback)
                        } else if (optionUrl.isNotEmpty()) {
                            val playerEndpoints = listOf(
                                "$mainUrl/wp-json/dooplayer/v2/$optionUrl",
                                "$mainUrl/wp-json/dooplayer/v1/$optionUrl"
                            )
                            
                            for (playerUrl in playerEndpoints) {
                                try {
                                    val playerResponse = app.get(playerUrl, headers = mapOf("User-Agent" to userAgent))
                                    val playerData = parseJson<PlayerResponse>(playerResponse.text)
                                    if (playerData.embed_url.isNotEmpty()) {
                                        loadExtractor(playerData.embed_url, data, subtitleCallback, callback)
                                        break
                                    }
                                } catch (parseException: Exception) {
                                    try {
                                        val playerDoc = app.get(playerUrl, headers = mapOf("User-Agent" to userAgent)).document
                                        val iframe = playerDoc.selectFirst("iframe")
                                        if (iframe != null) {
                                            val iframeSrc = iframe.attr("src")
                                            if (iframeSrc.startsWith("http")) {
                                                loadExtractor(iframeSrc, data, subtitleCallback, callback)
                                                break
                                            }
                                        }
                                    } catch (htmlException: Exception) {
                                        continue
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Continue processing other options
                    }
                })
            }
            
            // Method 3: Direct iframes
            doc.select("iframe").forEach { iframe ->
                tasks.add(async {
                    try {
                        val iframeUrl = iframe.attr("data-src").takeIf { it.isNotEmpty() }
                            ?: iframe.attr("src").takeIf { it.isNotEmpty() }
                            ?: return@async
                        
                        val fullUrl = if (iframeUrl.startsWith("http")) iframeUrl else "$mainUrl$iframeUrl"
                        loadExtractor(fullUrl, data, subtitleCallback, callback)
                    } catch (e: Exception) {
                        // Continue processing other iframes
                    }
                })
            }
            
            // Method 4: Script analysis for embedded URLs
            doc.select("script").forEach { script ->
                tasks.add(async {
                    try {
                        val scriptContent = script.data()
                        if (scriptContent.contains("http")) {
                            val patterns = listOf(
                                Regex("(?:\"|\')([^\"\']*(?:fembed|embedsb|streamtape|doodstream|uqload|mixdrop|upstream|voe|streamwish|filemoon)[^\"\']*?)(?:\"|\')", RegexOption.IGNORE_CASE),
                                Regex("file\\s*:\\s*[\"']([^\"']+\\.(?:mp4|m3u8))[\"']")
                            )
                            
                            patterns.forEach { pattern ->
                                pattern.findAll(scriptContent).forEach { match ->
                                    val url = match.groupValues[1]
                                    if (url.startsWith("http") && !url.contains("facebook") && !url.contains("twitter") && !url.contains("google")) {
                                        loadExtractor(url, data, subtitleCallback, callback)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Continue processing other scripts
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

    data class PlayerResponse(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("type") val type: String? = null
    )
}
