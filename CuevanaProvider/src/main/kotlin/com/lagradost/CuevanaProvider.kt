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
        TvType.Anime,
    )
    
    // Fixed mainPage structure similar to Cinecalidad
    override val mainPage = mainPageOf(
        Pair("$mainUrl/serie/page/", "Series"),
        Pair("$mainUrl/peliculas/page/", "Películas"),
        Pair("$mainUrl/animes/page/", "Anime"),
        Pair("$mainUrl/estrenos/page/", "Estrenos"),
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val soup = app.get(url, timeout = 120, headers = mapOf("User-Agent" to userAgent)).document
        
        // Look for multiple possible selectors
        val home = soup.select("article.TPost, .MovieList article, .TPostMv, .item.movies, article").mapNotNull { element ->
            try {
                val title = element.selectFirst("h2.Title, .title, h3, div.in_title")?.text()?.trim() ?: return@mapNotNull null
                val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                
                val posterImg = element.selectFirst("img")?.run {
                    attr("data-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                        ?: attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                        ?: attr("src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                        ?: return@mapNotNull null
                }
                
                // Determine content type based on URL patterns
                when {
                    fullLink.contains("/pelicula/") || fullLink.contains("/movies/") || request.name.contains("Películas") -> {
                        newMovieSearchResponse(title, fullLink) {
                            this.posterUrl = posterImg
                        }
                    }
                    fullLink.contains("/serie/") || fullLink.contains("/series/") || request.name.contains("Series") -> {
                        newTvSeriesSearchResponse(title, fullLink) {
                            this.posterUrl = posterImg
                        }
                    }
                    fullLink.contains("/anime/") || request.name.contains("Anime") -> {
                        newAnimeSearchResponse(title, fullLink) {
                            this.posterUrl = posterImg
                        }
                    }
                    else -> {
                        // Default fallback - try to determine from content
                        if (element.text().contains("Temporada") || element.text().contains("Episodio")) {
                            newTvSeriesSearchResponse(title, fullLink) {
                                this.posterUrl = posterImg
                            }
                        } else {
                            newMovieSearchResponse(title, fullLink) {
                                this.posterUrl = posterImg
                            }
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
        return try {
            val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
            val document = app.get(searchUrl, timeout = 120, headers = mapOf("User-Agent" to userAgent)).document
            
            document.select("article.TPost, .MovieList article, .TPostMv, article").mapNotNull { element ->
                try {
                    val title = element.selectFirst("h2.Title, .title, h3, div.in_title")?.text()?.trim() ?: return@mapNotNull null
                    val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                    
                    val image = element.selectFirst("img")?.run {
                        attr("data-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                            ?: attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                            ?: attr("src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                    }
                    
                    when {
                        fullHref.contains("/serie/") || fullHref.contains("/series/") -> {
                            newTvSeriesSearchResponse(title, fullHref) {
                                this.posterUrl = image
                            }
                        }
                        fullHref.contains("/anime/") -> {
                            newAnimeSearchResponse(title, fullHref) {
                                this.posterUrl = image
                            }
                        }
                        else -> {
                            newMovieSearchResponse(title, fullHref) {
                                this.posterUrl = image
                            }
                        }
                    }
                } catch (e: Exception) {
                    logError(e)
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
            
            val title = soup.selectFirst("h1.Title, h1, .entry-title, .single_left h1")?.text()?.trim() ?: return null
            val description = soup.selectFirst(".Description p, .wp-content p, .synopsis, div.single_left table tbody tr td p")?.text()?.trim()
            val poster = soup.selectFirst(".movtv-info img, .poster img, .wp-post-image, .alignnone")?.run {
                attr("data-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                    ?: attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                    ?: attr("src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
            }
            
            // Extract year
            val yearText = soup.selectFirst(".date, .year, .meta")?.text() ?: soup.text()
            val year = Regex("(19|20)\\d{2}").find(yearText)?.value?.toIntOrNull()
            
            // Extract episodes for series - multiple selectors for different layouts
            val episodes = soup.select(".episodios .item, .all-episodes .TPostMv, .episodes .episode, div.se-c div.se-a ul.episodios li").mapNotNull { li ->
                try {
                    val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                    
                    val epThumb = li.selectFirst("img, img.lazy")?.run {
                        attr("data-src").takeIf { it.isNotEmpty() && !it.contains("data:image") && !it.contains("svg") }
                            ?: attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.contains("data:image") && !it.contains("svg") }
                            ?: attr("src").takeIf { it.isNotEmpty() && !it.contains("data:image") && !it.contains("svg") }
                    }
                    
                    val episodeText = li.selectFirst(".numerando, .episode-number, .num, .Year")?.text() ?: ""
                    val episodeName = li.selectFirst("h3, .title, .episode-title, .episodiotitle a")?.text()?.trim() ?: "Episode"
                    
                    // Parse season and episode - multiple patterns
                    var season: Int? = null
                    var episode: Int? = null
                    
                    // Pattern 1: S1-E1 format
                    val seasonEpisodePattern1 = Regex("(\\d+)[-x](\\d+)")
                    val match1 = seasonEpisodePattern1.find(episodeText)
                    if (match1 != null) {
                        season = match1.groupValues[1].toIntOrNull()
                        episode = match1.groupValues[2].toIntOrNull()
                    } else {
                        // Pattern 2: Look for separate season/episode numbers
                        val seasonPattern = Regex("S(\\d+)", RegexOption.IGNORE_CASE)
                        val episodePattern = Regex("E(\\d+)", RegexOption.IGNORE_CASE)
                        
                        season = seasonPattern.find(episodeText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        episode = episodePattern.find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                    }
                    
                    newEpisode(fullHref) {
                        this.name = episodeName
                        this.season = season
                        this.episode = episode
                        this.posterUrl = epThumb
                    }
                } catch (e: Exception) {
                    logError(e)
                    null
                }
            }
            
            val tags = soup.select(".genres a, .genre a, .meta a").map { it.text().trim() }
            
            // Determine content type
            val tvType = when {
                url.contains("/anime/") -> TvType.Anime
                episodes.isNotEmpty() || url.contains("/serie/") -> TvType.TvSeries
                else -> TvType.Movie
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
                TvType.Anime -> {
                    newAnimeLoadResponse(title, url, TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                        this.plot = description
                        this.tags = tags
                        addEpisodes(DubStatus.Subbed, episodes)
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
            val doc = app.get(data, timeout = 120, headers = mapOf("User-Agent" to userAgent)).document
            
            val tasks = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
            
            // Method 1: Look for player buttons/options (similar to Cinecalidad)
            doc.select("[data-tplayernv], [data-option], [data-server], .player-option, .dooplay_player_option").forEach { option ->
                tasks.add(async {
                    try {
                        val optionUrl = option.attr("data-tplayernv")
                            ?: option.attr("data-option")
                            ?: option.attr("data-server")
                            ?: return@async
                        
                        if (optionUrl.startsWith("http")) {
                            loadExtractor(optionUrl, data, subtitleCallback, callback)
                        } else {
                            // Try different API endpoints
                            val apiEndpoints = listOf(
                                "$mainUrl/wp-json/dooplayer/v1/$optionUrl",
                                "$mainUrl/wp-json/dooplayer/v2/$optionUrl",
                                "$mainUrl/player/$optionUrl"
                            )
                            
                            for (endpoint in apiEndpoints) {
                                try {
                                    val playerResponse = app.get(endpoint, headers = mapOf("User-Agent" to userAgent))
                                    val playerData = parseJson<PlayerResponse>(playerResponse.text)
                                    if (playerData.embed_url.isNotEmpty()) {
                                        loadExtractor(playerData.embed_url, data, subtitleCallback, callback)
                                        break
                                    }
                                } catch (e: Exception) {
                                    // Try next endpoint
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logError(e)
                    }
                })
            }
            
            // Method 2: Direct iframes
            doc.select("iframe").forEach { iframe ->
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
            
            // Method 3: Script analysis for video URLs
            doc.select("script").forEach { script ->
                tasks.add(async {
                    try {
                        val scriptContent = script.data()
                        if (scriptContent.contains("http")) {
                            // Look for common video hosting patterns
                            val patterns = listOf(
                                Regex("(?:\"|\')([^\"\']*(?:fembed|embedsb|streamtape|doodstream|uqload|mixdrop|upstream|voe|streamwish|filemoon|evoload|cinestart)[^\"\']*?)(?:\"|\')", RegexOption.IGNORE_CASE),
                                Regex("file:\\s*[\"']([^\"']+\\.(?:mp4|m3u8))[\"']"),
                                Regex("src:\\s*[\"']([^\"']+)[\"']"),
                                Regex("url:\\s*[\"']([^\"']+)[\"']"),
                                Regex("\"embed_url\":\\s*\"([^\"]+)\"")
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
                        logError(e)
                    }
                })
            }
            
            // Method 4: Look for AJAX endpoints in page source
            val pageSource = doc.html()
            if (pageSource.contains("ajax")) {
                tasks.add(async {
                    try {
                        val ajaxPattern = Regex("ajax.*?url.*?[\"']([^\"']+)[\"']")
                        ajaxPattern.findAll(pageSource).forEach { match ->
                            val ajaxUrl = match.groupValues[1]
                            if (ajaxUrl.startsWith("/")) {
                                val fullAjaxUrl = "$mainUrl$ajaxUrl"
                                val ajaxResponse = app.get(fullAjaxUrl, headers = mapOf("User-Agent" to userAgent))
                                val ajaxDoc = ajaxResponse.document
                                
                                ajaxDoc.select("iframe").forEach { iframe ->
                                    val src = iframe.attr("src")
                                    if (src.startsWith("http")) {
                                        loadExtractor(src, data, subtitleCallback, callback)
                                    }
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

    data class PlayerResponse(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("type") val type: String? = null
    )
}
