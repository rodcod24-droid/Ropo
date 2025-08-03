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
    override var mainUrl = "https://w3vn.cuevana.pro" // Alternative: "https://cuevana.pro"
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
        Pair("$mainUrl/serie", "Series"),
        Pair("$mainUrl/peliculas", "Películas"),
        Pair("$mainUrl/genero/animacion", "Anime"),
        Pair("$mainUrl/estrenos", "Estrenos"),
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val soup = app.get(url, timeout = 120).document
        
        val home = soup.select("article.TPost.B").mapNotNull { element ->
            try {
                val title = element.selectFirst("h2.Title")?.text()?.trim() ?: return@mapNotNull null
                val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val posterImg = element.selectFirst("figure img")?.attr("data-src") 
                    ?: element.selectFirst("figure img")?.attr("src")
                    ?: return@mapNotNull null
                
                when {
                    link.contains("/pelicula/") || request.name.contains("Películas") -> {
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
            } catch (e: Exception) {
                logError(e)
                null
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl, timeout = 120).document

        return document.select("article.TPost.B").mapNotNull { element ->
            try {
                val title = element.selectFirst("h2.Title")?.text()?.trim() ?: return@mapNotNull null
                val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val image = element.selectFirst("figure img")?.attr("data-src") 
                    ?: element.selectFirst("figure img")?.attr("src")
                    ?: return@mapNotNull null

                when {
                    href.contains("/pelicula/") -> {
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
            } catch (e: Exception) {
                logError(e)
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val soup = app.get(url, timeout = 120).document

            val title = soup.selectFirst("h1.Title")?.text()?.trim() ?: return null
            val description = soup.selectFirst(".Description p")?.text()?.trim()
            val poster = soup.selectFirst(".poster img")?.attr("data-src")
                ?: soup.selectFirst(".poster img")?.attr("src")
            
            // Extract year
            val year = soup.selectFirst(".Date")?.text()?.let { 
                Regex("(\\d{4})").find(it)?.value?.toIntOrNull() 
            }
            
            // Extract episodes for series
            val episodes = soup.select(".episodios .item").mapNotNull { li ->
                try {
                    val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val epThumb = li.selectFirst("img")?.attr("data-src")
                        ?: li.selectFirst("img")?.attr("src")
                    val name = li.selectFirst(".episodiotitle a")?.text()?.trim() ?: "Episode"
                    
                    // Parse season and episode numbers
                    val seasonEpisodeText = li.selectFirst(".numerando")?.text() ?: ""
                    val match = Regex("(\\d+)-(\\d+)").find(seasonEpisodeText)
                    val season = match?.groupValues?.get(1)?.toIntOrNull()
                    val episode = match?.groupValues?.get(2)?.toIntOrNull()
                    
                    newEpisode(href) {
                        this.name = name
                        this.season = season
                        this.episode = episode
                        this.posterUrl = epThumb
                    }
                } catch (e: Exception) {
                    logError(e)
                    null
                }
            }

            val tvType = when {
                url.contains("/anime/") -> TvType.Anime
                url.contains("/pelicula/") -> TvType.Movie
                episodes.isNotEmpty() -> TvType.TvSeries
                else -> TvType.Movie
            }
            
            return when (tvType) {
                TvType.TvSeries -> {
                    newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                        this.posterUrl = poster
                        this.year = year
                        this.plot = description
                    }
                }
                TvType.Anime -> {
                    newAnimeLoadResponse(title, url, TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                        this.plot = description
                        addEpisodes(DubStatus.Subbed, episodes)
                    }
                }
                TvType.Movie -> {
                    newMovieLoadResponse(title, url, TvType.Movie, url) {
                        this.posterUrl = poster
                        this.year = year
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
            val response = app.get(data, timeout = 120)
            val doc = response.document

            // Method 1: Look for player options
            val playerOptions = doc.select("#playeroptionsul li")
            playerOptions.map { option ->
                async {
                    try {
                        val post = option.attr("data-post")
                        val nume = option.attr("data-nume")
                        val type = option.attr("data-type")
                        
                        if (post.isNotEmpty() && nume.isNotEmpty()) {
                            val requestBody = mapOf(
                                "action" to "doo_player_ajax",
                                "post" to post,
                                "nume" to nume,
                                "type" to type
                            )
                            
                            val ajaxResponse = app.post(
                                "$mainUrl/wp-admin/admin-ajax.php",
                                data = requestBody,
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to data,
                                    "X-Requested-With" to "XMLHttpRequest"
                                ),
                                timeout = 60
                            )
                            
                            val playerData = parseJson<PlayerResponse>(ajaxResponse.text)
                            if (playerData.embed_url.isNotEmpty()) {
                                loadExtractor(playerData.embed_url, data, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }.awaitAll()

            // Method 2: Direct iframes
            doc.select("iframe").map { iframe ->
                async {
                    try {
                        val iframeUrl = iframe.attr("data-src").takeIf { it.isNotEmpty() }
                            ?: iframe.attr("src").takeIf { it.isNotEmpty() }
                        
                        if (iframeUrl != null && iframeUrl.startsWith("http")) {
                            loadExtractor(iframeUrl, data, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }.awaitAll()

            // Method 3: Look for embedded URLs in scripts
            doc.select("script").map { script ->
                async {
                    try {
                        val scriptContent = script.data()
                        val patterns = listOf(
                            Regex("\"embed_url\":\\s*\"([^\"]+)\""),
                            Regex("'embed_url':\\s*'([^']+)'"),
                            Regex("file:\\s*[\"']([^\"']+\\.(?:mp4|m3u8))[\"']"),
                            Regex("(?:src|url):\\s*[\"']([^\"']*(?:fembed|embedsb|streamtape|doodstream|uqload|mixdrop|upstream|voe|streamwish|filemoon)[^\"']*)[\"']", RegexOption.IGNORE_CASE)
                        )
                        
                        patterns.forEach { pattern ->
                            pattern.findAll(scriptContent).forEach { match ->
                                val extractedUrl = match.groupValues[1]
                                if (extractedUrl.startsWith("http")) {
                                    loadExtractor(extractedUrl, data, subtitleCallback, callback)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }.awaitAll()

            // Method 4: Alternative player detection
            doc.select("[data-tplayernv]").map { element ->
                async {
                    try {
                        val playerId = element.attr("data-tplayernv")
                        if (playerId.isNotEmpty()) {
                            val playerUrl = "$mainUrl/wp-json/dooplayer/v2/$playerId"
                            val playerResponse = app.get(
                                playerUrl,
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to data
                                ),
                                timeout = 60
                            )
                            
                            val playerData = parseJson<PlayerResponse>(playerResponse.text)
                            if (playerData.embed_url.isNotEmpty()) {
                                loadExtractor(playerData.embed_url, data, subtitleCallback, callback)
                            }
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

    data class PlayerResponse(
        @JsonProperty("embed_url") val embed_url: String = "",
        @JsonProperty("type") val type: String? = null
    )
}
