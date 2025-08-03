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
        
        try {
            val soup = app.get(url, timeout = 120).document
            
            // Multiple possible selectors for different Cuevana layouts
            val possibleSelectors = listOf(
                "article.TPost.B",           // Original attempt
                "article.TPost",             // Without .B class
                "article",                   // Generic article
                ".MovieList article",        // MovieList container
                ".item.movies",              // Movies items
                ".TPostMv",                  // Alternative post format
                ".movie-item",               // Movie item class
                ".serie-item",               // Serie item class
                "div.item",                  // Generic item div
                ".post",                     // Generic post
                ".content-item"              // Content item
            )
            
            var elements: org.jsoup.select.Elements? = null
            var usedSelector = ""
            
            // Try each selector until we find content
            for (selector in possibleSelectors) {
                elements = soup.select(selector)
                if (elements.isNotEmpty()) {
                    usedSelector = selector
                    break
                }
            }
            
            if (elements == null || elements.isEmpty()) {
                // Debug: Print available elements to see what's actually on the page
                println("CUEVANA DEBUG: No content found with any selector for ${request.name}")
                println("CUEVANA DEBUG: Available articles: ${soup.select("article").size}")
                println("CUEVANA DEBUG: Available divs with class: ${soup.select("div[class]").size}")
                println("CUEVANA DEBUG: Page title: ${soup.title()}")
                return newHomePageResponse(request.name, emptyList())
            }
            
            println("CUEVANA DEBUG: Found ${elements.size} elements using selector: $usedSelector")
            
            val home = elements.mapNotNull { element ->
                try {
                    // Multiple possible title selectors
                    val title = element.selectFirst("h2.Title")?.text()?.trim()
                        ?: element.selectFirst("h2")?.text()?.trim()
                        ?: element.selectFirst("h3")?.text()?.trim()
                        ?: element.selectFirst(".title")?.text()?.trim()
                        ?: element.selectFirst(".movie-title")?.text()?.trim()
                        ?: element.selectFirst("div.in_title")?.text()?.trim()
                        ?: return@mapNotNull null
                    
                    // Multiple possible link selectors
                    val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    
                    // Multiple possible image selectors
                    val posterImg = element.selectFirst("figure img")?.attr("data-src")
                        ?: element.selectFirst("figure img")?.attr("src")
                        ?: element.selectFirst("img")?.attr("data-src")
                        ?: element.selectFirst("img")?.attr("src")
                        ?: element.selectFirst("img")?.attr("data-lazy-src")
                        ?: element.selectFirst(".poster img")?.attr("data-src")
                        ?: element.selectFirst(".poster img")?.attr("src")
                    
                    if (posterImg == null || posterImg.contains("data:image")) {
                        return@mapNotNull null
                    }
                    
                    println("CUEVANA DEBUG: Found item - Title: $title, Link: $link")
                    
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
            
            println("CUEVANA DEBUG: Returning ${home.size} items for ${request.name}")
            return newHomePageResponse(request.name, home)
            
        } catch (e: Exception) {
            logError(e)
            println("CUEVANA DEBUG: Error loading main page: ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
            val document = app.get(searchUrl, timeout = 120).document
            
            // Try multiple selectors for search results too
            val possibleSelectors = listOf(
                "article.TPost.B",
                "article.TPost", 
                "article",
                ".MovieList article",
                ".item.movies",
                ".TPostMv",
                ".search-item",
                "div.item"
            )
            
            var elements: org.jsoup.select.Elements? = null
            
            for (selector in possibleSelectors) {
                elements = document.select(selector)
                if (elements.isNotEmpty()) break
            }
            
            elements?.mapNotNull { element ->
                try {
                    val title = element.selectFirst("h2.Title")?.text()?.trim()
                        ?: element.selectFirst("h2")?.text()?.trim()
                        ?: element.selectFirst("h3")?.text()?.trim()
                        ?: element.selectFirst(".title")?.text()?.trim()
                        ?: return@mapNotNull null
                    
                    val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    
                    val image = element.selectFirst("figure img")?.attr("data-src")
                        ?: element.selectFirst("figure img")?.attr("src")
                        ?: element.selectFirst("img")?.attr("data-src")
                        ?: element.selectFirst("img")?.attr("src")
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
            } ?: emptyList()
            
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val soup = app.get(url, timeout = 120).document

            val title = soup.selectFirst("h1.Title")?.text()?.trim()
                ?: soup.selectFirst("h1")?.text()?.trim()
                ?: soup.selectFirst(".single_left h1")?.text()?.trim()
                ?: return null
                
            val description = soup.selectFirst(".Description p")?.text()?.trim()
                ?: soup.selectFirst(".wp-content p")?.text()?.trim()
                ?: soup.selectFirst(".synopsis")?.text()?.trim()
                ?: soup.selectFirst("div.single_left table tbody tr td p")?.text()?.trim()
                
            val poster = soup.selectFirst(".poster img")?.attr("data-src")
                ?: soup.selectFirst(".poster img")?.attr("src")
                ?: soup.selectFirst(".alignnone")?.attr("data-src")
                ?: soup.selectFirst(".alignnone")?.attr("src")
                ?: soup.selectFirst("img")?.attr("data-src")
                ?: soup.selectFirst("img")?.attr("src")
            
            // Extract year with multiple patterns
            val year = soup.selectFirst(".Date")?.text()?.let { 
                Regex("(\\d{4})").find(it)?.value?.toIntOrNull() 
            } ?: soup.selectFirst(".year")?.text()?.toIntOrNull()
            
            // Extract episodes with multiple selectors
            val episodes = soup.select(".episodios .item, .all-episodes .TPostMv, .episodes .episode, div.se-c div.se-a ul.episodios li").mapNotNull { li ->
                try {
                    val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val epThumb = li.selectFirst("img")?.attr("data-src")
                        ?: li.selectFirst("img")?.attr("src")
                        ?: li.selectFirst("img.lazy")?.attr("data-src")
                    val name = li.selectFirst(".episodiotitle a")?.text()?.trim()
                        ?: li.selectFirst("h3")?.text()?.trim()
                        ?: li.selectFirst(".title")?.text()?.trim()
                        ?: "Episode"
                    
                    // Parse season and episode numbers with multiple patterns
                    val seasonEpisodeText = li.selectFirst(".numerando")?.text()
                        ?: li.selectFirst(".episode-number")?.text()
                        ?: li.selectFirst(".num")?.text() ?: ""
                        
                    val match = Regex("(\\d+)[-x](\\d+)").find(seasonEpisodeText)
                    val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val episode = match?.groupValues?.get(2)?.toIntOrNull()
                    
                    newEpisode(href) {
                        this.name = name
                        this.season = season
                        this.episode = episode
                        this.posterUrl = if (epThumb?.contains("svg") == true) null else epThumb
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

            // Method 1: Look for player options with multiple selectors
            val playerOptions = doc.select("#playeroptionsul li, .dooplay_player_option, [data-tplayernv], [data-option], [data-server], .player-option")
            playerOptions.map { option ->
                async {
                    try {
                        val post = option.attr("data-post")
                        val nume = option.attr("data-nume") 
                        val type = option.attr("data-type")
                        val playerId = option.attr("data-tplayernv")
                        val optionUrl = option.attr("data-option")
                        
                        // Try AJAX method first
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
                        
                        // Try player ID method
                        else if (playerId.isNotEmpty()) {
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
                        
                        // Try direct option URL
                        else if (optionUrl.isNotEmpty() && optionUrl.startsWith("http")) {
                            loadExtractor(optionUrl, data, subtitleCallback, callback)
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

            // Method 3: Script analysis
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
