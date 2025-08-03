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
        TvType.Anime,
    )
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        Pair("$mainUrl/series/page/", "Series"),
        Pair("$mainUrl/peliculas/page/", "Peliculas"),
        Pair("$mainUrl/animes/page/", "Anime"),
        Pair("$mainUrl/estrenos/page/", "Estrenos"),
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val soup = app.get(url).document
        
        val home = soup.select("article.TPost, .MovieList article, .item.movies, article").mapNotNull { element ->
            val title = element.selectFirst("h2.Title, .title, h3, div.in_title")?.text() ?: return@mapNotNull null
            val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val posterImg = element.selectFirst("img")?.run {
                attr("data-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                    ?: attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                    ?: attr("src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
            } ?: return@mapNotNull null
            
            // Determine content type based on URL patterns or section
            when {
                link.contains("/pelicula/") || link.contains("/movies/") || request.name.contains("Peliculas") -> {
                    newMovieSearchResponse(title, link) {
                        this.posterUrl = posterImg
                    }
                }
                link.contains("/anime/") || request.name.contains("Anime") -> {
                    newAnimeSearchResponse(title, link) {
                        this.posterUrl = posterImg
                    }
                }
                else -> {
                    newTvSeriesSearchResponse(title, link) {
                        this.posterUrl = posterImg
                    }
                }
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("article.TPost, .MovieList article, .item.movies, article").mapNotNull { element ->
            val title = element.selectFirst("h2.Title, .title, h3, div.in_title")?.text() ?: return@mapNotNull null
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val image = element.selectFirst("img")?.run {
                attr("data-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                    ?: attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                    ?: attr("src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
            } ?: return@mapNotNull null

            when {
                href.contains("/pelicula/") || href.contains("/movies/") -> {
                    newMovieSearchResponse(title, href) {
                        this.posterUrl = image
                    }
                }
                href.contains("/anime/") -> {
                    newAnimeSearchResponse(title, href) {
                        this.posterUrl = image
                    }
                }
                else -> {
                    newTvSeriesSearchResponse(title, href) {
                        this.posterUrl = image
                    }
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url, timeout = 120).document

        val title = soup.selectFirst("h1.Title, h1, .entry-title, .single_left h1")?.text() ?: return null
        val description = soup.selectFirst(".Description p, .wp-content p, .synopsis, div.single_left table tbody tr td p")?.text()?.trim()
        val poster = soup.selectFirst(".movtv-info img, .poster img, .wp-post-image, .alignnone")?.run {
            attr("data-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                ?: attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                ?: attr("src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
        }
        
        val episodes = soup.select(".episodios .item, .all-episodes .TPostMv, .episodes .episode, div.se-c div.se-a ul.episodios li").mapNotNull { li ->
            val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val epThumb = li.selectFirst("img, img.lazy")?.run {
                attr("data-src").takeIf { it.isNotEmpty() && !it.contains("svg") }
                    ?: attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.contains("svg") }
                    ?: attr("src").takeIf { it.isNotEmpty() && !it.contains("svg") }
            }
            val name = li.selectFirst("h3, .title, .episode-title, .episodiotitle a")?.text() ?: "Episode"
            
            // Parse season and episode numbers from the episode numbering
            val seasonEpisodeText = li.selectFirst(".numerando, .episode-number, .num")?.text()?.replace(Regex("(S|E)"), "") ?: ""
            val seasonEpisode = seasonEpisodeText.split("-").mapNotNull { it.toIntOrNull() }
            val isValid = seasonEpisode.size == 2
            val episode = if (isValid) seasonEpisode.getOrNull(1) else null
            val season = if (isValid) seasonEpisode.getOrNull(0) else null
            
            newEpisode(href) {
                this.name = name
                this.season = season
                this.episode = episode
                this.posterUrl = if (epThumb?.contains("svg") == true) null else epThumb
            }
        }

        // Determine content type
        val tvType = when {
            url.contains("/anime/") -> TvType.Anime
            url.contains("/pelicula/") || url.contains("/movies/") -> TvType.Movie
            episodes.isNotEmpty() -> TvType.TvSeries
            else -> TvType.Movie
        }
        
        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
                }
            }
            TvType.Anime -> {
                newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.plot = description
                    addEpisodes(DubStatus.Subbed, episodes)
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
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val response = app.get(data)
        val doc = response.document
        val datatext = response.text

        // Process player options (similar to Cinecalidad)
        val playerOptions = doc.select(".dooplay_player_option, [data-tplayernv], [data-option], [data-server], .player-option")
        playerOptions.map { element ->
            async {
                try {
                    val url = element.attr("data-option")
                        ?: element.attr("data-tplayernv")
                        ?: element.attr("data-server")
                    
                    if (url.isNotEmpty()) {
                        if (url.startsWith("http")) {
                            loadExtractor(url, mainUrl, subtitleCallback, callback)
                        } else {
                            // Try to get embed URL from API
                            processCuevanaPlayerUrl(url, data, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }.awaitAll()

        // Process direct iframes
        doc.select("iframe").map { iframe ->
            async {
                try {
                    val iframeUrl = iframe.attr("data-src").takeIf { it.isNotEmpty() }
                        ?: iframe.attr("src").takeIf { it.isNotEmpty() }
                    
                    if (iframeUrl != null && iframeUrl.startsWith("http")) {
                        loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }.awaitAll()

        // Process script content for embedded URLs
        doc.select("script").map { script ->
            async {
                try {
                    val scriptContent = script.data()
                    if (scriptContent.contains("http")) {
                        val urlPatterns = listOf(
                            Regex("(?:\"|\')([^\"\']*(?:fembed|embedsb|streamtape|doodstream|uqload|mixdrop|upstream|voe|streamwish|filemoon|evoload|cinestart)[^\"\']*?)(?:\"|\')", RegexOption.IGNORE_CASE),
                            Regex("\"embed_url\":\\s*\"([^\"]+)\""),
                            Regex("file:\\s*[\"']([^\"']+\\.(?:mp4|m3u8))[\"']")
                        )
                        
                        urlPatterns.forEach { pattern ->
                            pattern.findAll(scriptContent).forEach { match ->
                                val extractedUrl = match.groupValues[1]
                                if (extractedUrl.startsWith("http") && !extractedUrl.contains("facebook") && !extractedUrl.contains("twitter")) {
                                    loadExtractor(extractedUrl, mainUrl, subtitleCallback, callback)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }.awaitAll()

        true
    }

    private suspend fun processCuevanaPlayerUrl(
        playerData: String,
        refererData: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        try {
            // Try different API endpoints that Cuevana might use
            val apiEndpoints = listOf(
                "$mainUrl/wp-json/dooplayer/v1/$playerData",
                "$mainUrl/wp-json/dooplayer/v2/$playerData",
                "$mainUrl/player/$playerData",
                "$mainUrl/embed/$playerData"
            )
            
            for (endpoint in apiEndpoints) {
                try {
                    val headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to refererData,
                        "Accept" to "application/json, text/plain, */*",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                    
                    val response = app.get(endpoint, headers = headers)
                    
                    // Try to parse as JSON first
                    try {
                        val playerResponse = parseJson<PlayerResponse>(response.text)
                        if (playerResponse.embed_url.isNotEmpty()) {
                            loadExtractor(playerResponse.embed_url, mainUrl, subtitleCallback, callback)
                            break
                        }
                    } catch (e: Exception) {
                        // If not JSON, try to extract URLs from HTML response
                        val responseDoc = response.document
                        responseDoc.select("iframe").forEach { iframe ->
                            val src = iframe.attr("src")
                            if (src.startsWith("http")) {
                                loadExtractor(src, mainUrl, subtitleCallback, callback)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Continue to next endpoint
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    data class PlayerResponse(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("type") val type: String? = null
    )
}
