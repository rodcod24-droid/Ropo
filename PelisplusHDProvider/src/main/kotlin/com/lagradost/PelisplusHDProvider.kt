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
    override var mainUrl = "https://pelisplushd.mx"
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
            
            // Get Movies Section
            try {
                val moviesDoc = app.get("$mainUrl/peliculas", timeout = 120, headers = mapOf("User-Agent" to userAgent)).document
                val movieItems = moviesDoc.select("a.Posters-link, .MovieList .TPostMv, .movies .item").take(20).mapNotNull { element ->
                    element.toSearchResult()
                }
                if (movieItems.isNotEmpty()) {
                    items.add(HomePageList("Películas", movieItems))
                }
            } catch (e: Exception) {
                logError(e)
            }
            
            // Get Series Section  
            try {
                val seriesDoc = app.get("$mainUrl/series", timeout = 120, headers = mapOf("User-Agent" to userAgent)).document
                val seriesItems = seriesDoc.select("a.Posters-link, .MovieList .TPostMv, .series .item").take(20).mapNotNull { element ->
                    element.toSearchResult()
                }
                if (seriesItems.isNotEmpty()) {
                    items.add(HomePageList("Series", seriesItems))
                }
            } catch (e: Exception) {
                logError(e)
            }
            
            // Get Anime Section
            try {
                val animeDoc = app.get("$mainUrl/animes", timeout = 120, headers = mapOf("User-Agent" to userAgent)).document
                val animeItems = animeDoc.select("a.Posters-link, .MovieList .TPostMv, .anime .item").take(20).mapNotNull { element ->
                    element.toSearchResult()
                }
                if (animeItems.isNotEmpty()) {
                    items.add(HomePageList("Anime", animeItems))
                }
            } catch (e: Exception) {
                logError(e)
            }
            
            // Fallback: get content from main page if sections are empty
            if (items.isEmpty()) {
                val document = app.get(mainUrl, timeout = 120, headers = mapOf("User-Agent" to userAgent)).document  
                val fallbackItems = document.select("a.Posters-link, .MovieList .TPostMv, .content .item").take(30).mapNotNull { element ->
                    element.toSearchResult()
                }
                if (fallbackItems.isNotEmpty()) {
                    items.add(HomePageList("Contenido", fallbackItems))
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
            val title = this.selectFirst("h2.Title, h3.Title, .title, .listing-content p")?.text()?.trim()
                ?: this.attr("title").takeIf { it.isNotEmpty() }
                ?: return null
            
            val href = this.selectFirst("a")?.attr("href")
                ?: this.attr("href").takeIf { it.isNotEmpty() }
                ?: return null
            
            val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
            
            val posterUrl = this.selectFirst("img.Posters-img, img")?.run {
                attr("data-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                    ?: attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                    ?: attr("src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
            }
            
            val isMovie = fullHref.contains("/pelicula/") || 
                         fullHref.contains("/movie/") || 
                         fullHref.contains("/movies/")
            
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
            val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
            val document = app.get(searchUrl, timeout = 120, headers = mapOf("User-Agent" to userAgent)).document
            
            document.select("a.Posters-link, .MovieList .TPostMv, .search-results .item").mapNotNull { element ->
                element.toSearchResult()
            }
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val soup = app.get(url, timeout = 120, headers = mapOf("User-Agent" to userAgent)).document

            val title = soup.selectFirst("h1, .title, .movie-title")?.text()?.trim() ?: return null
            val description = soup.selectFirst(".description, .synopsis, .overview, .plot")?.text()?.trim()
            val poster = soup.selectFirst("img.poster, .movie-poster img, img")?.run {
                attr("data-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                    ?: attr("data-lazy-src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
                    ?: attr("src").takeIf { it.isNotEmpty() && !it.contains("data:image") }
            }
            
            // Extract year
            val yearText = soup.selectFirst(".year, .date, .release-date")?.text() ?: soup.text()
            val year = Regex("(19|20)\\d{2}").find(yearText)?.value?.toIntOrNull()
            
            // Get episodes for series
            val episodes = soup.select(".episodes .episode, .season .episode, .episode-list li, .TPostMv").mapNotNull { li ->
                try {
                    val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                    
                    val name = li.selectFirst(".episode-title, .title")?.text()?.trim()
                    val numberText = li.selectFirst(".episode-number, .number")?.text() ?: href
                    
                    // Parse season and episode numbers
                    val seasonEpisodePattern = Regex("temporada[/-]?(\\d+)[/-]?.*?capitulo[/-]?(\\d+)|s(\\d+)e(\\d+)|(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)
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
            val doc = app.get(data, timeout = 120, headers = mapOf("User-Agent" to userAgent)).document
            
            val tasks = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
            
            // Method 1: Look for player buttons/tabs
            doc.select(".TPlayer.embed_div, .player, .video-player").forEach { playerDiv ->
                tasks.add(async {
                    try {
                        // Look for iframes within player divs
                        val iframe = playerDiv.selectFirst("iframe")
                        if (iframe != null) {
                            val iframeUrl = iframe.attr("data-src").takeIf { it.isNotEmpty() }
                                ?: iframe.attr("src").takeIf { it.isNotEmpty() }
                                ?: return@async
                            
                            val fullUrl = if (iframeUrl.startsWith("http")) iframeUrl else "$mainUrl$iframeUrl"
                            loadExtractor(fullUrl, data, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        logError(e)
                    }
                })
            }
            
            // Method 2: Look for server/option buttons
            doc.select("[data-option], [data-server], .server-option, .player-option").forEach { option ->
                tasks.add(async {
                    try {
                        val optionValue = option.attr("data-option")
                            ?: option.attr("data-server")
                            ?: return@async
                        
                        if (optionValue.startsWith("http")) {
                            loadExtractor(optionValue, data, subtitleCallback, callback)
                        } else {
                            // Try to construct player URL
                            val playerUrl = "$mainUrl/wp-content/plugins/player/player.php?data=$optionValue"
                            val playerDoc = app.get(playerUrl, headers = mapOf("User-Agent" to userAgent)).document
                            val playerIframe = playerDoc.selectFirst("iframe")
                            if (playerIframe != null) {
                                val playerSrc = playerIframe.attr("src")
                                if (playerSrc.startsWith("http")) {
                                    loadExtractor(playerSrc, data, subtitleCallback, callback)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logError(e)
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
                        logError(e)
                    }
                })
            }
            
            // Method 4: Script analysis
            doc.select("script").forEach { script ->
                tasks.add(async {
                    try {
                        val scriptContent = script.data()
                        if (scriptContent.contains("http")) {
                            val videoPatterns = listOf(
                                Regex("(?:\"|\')([^\"\']*(?:fembed|embedsb|streamtape|doodstream|uqload|mixdrop|upstream|voe|streamwish|filemoon)[^\"\']*?)(?:\"|\')", RegexOption.IGNORE_
