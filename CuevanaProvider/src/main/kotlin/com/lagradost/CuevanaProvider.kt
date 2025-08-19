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
import java.net.URLDecoder
import java.util.Base64

class CuevanaProvider : MainAPI() {
    override var mainUrl = "https://cuevana.biz"
    override var name = "Cuevana"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    // Common headers for all requests
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "es-ES,es;q=0.8,en-US;q=0.5,en;q=0.3",
        "Accept-Encoding" to "gzip, deflate, br",
        "DNT" to "1",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        
        val sections = listOf(
            Triple("$mainUrl/peliculas", "Películas", "movies"),
            Triple("$mainUrl/series", "Series", "series"),
            Triple("$mainUrl/estrenos", "Estrenos", "releases")
        )
        
        for ((url, sectionName, sectionType) in sections) {
            try {
                val doc = app.get(
                    url, 
                    timeout = 60,
                    headers = commonHeaders
                ).document
                
                // More comprehensive selectors for different layouts
                val possibleSelectors = listOf(
                    ".MovieList .TPostMv",
                    ".MovieList article",
                    ".TPMvCn .TPost",
                    ".movies-list .movie-item",
                    "article.item.movies",
                    ".item.movies",
                    ".TPost.C",
                    ".post-item",
                    "li.xxx.TPostMv",
                    "article.TPost"
                )
                
                var elements: org.jsoup.select.Elements? = null
                
                for (selector in possibleSelectors) {
                    elements = doc.select(selector)
                    if (elements.isNotEmpty()) {
                        break
                    }
                }
                
                if (elements != null && elements.isNotEmpty()) {
                    val homeItems = elements.mapNotNull { element ->
                        try {
                            // Enhanced title extraction with multiple fallbacks
                            val title = element.selectFirst("h2.Title")?.text()?.trim()
                                ?: element.selectFirst("h3.Title")?.text()?.trim()
                                ?: element.selectFirst(".title")?.text()?.trim()
                                ?: element.selectFirst("h2")?.text()?.trim()
                                ?: element.selectFirst("h3")?.text()?.trim()
                                ?: element.selectFirst("a")?.attr("title")?.trim()
                                ?: element.selectFirst("a")?.text()?.trim()
                                ?: return@mapNotNull null
                            
                            if (title.isEmpty()) return@mapNotNull null
                            
                            // Enhanced link extraction
                            val rawLink = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                            val link = fixUrl(rawLink)
                            
                            // Enhanced image extraction with better fallbacks
                            val imgElement = element.selectFirst("img")
                            val rawPoster = imgElement?.attr("data-src")?.takeIf { it.isNotEmpty() }
                                ?: imgElement?.attr("data-lazy-src")?.takeIf { it.isNotEmpty() }
                                ?: imgElement?.attr("data-original")?.takeIf { it.isNotEmpty() }
                                ?: imgElement?.attr("src")?.takeIf { it.isNotEmpty() }
                            
                            val poster = rawPoster?.let { fixUrl(it) }
                            
                            // Better type detection
                            val isMovie = link.contains("/pelicula/") || 
                                         link.contains("/movie/") || 
                                         sectionType == "movies" ||
                                         element.hasClass("movie") ||
                                         element.selectFirst(".movie-type")?.text()?.contains("película", true) == true
                            
                            if (isMovie) {
                                newMovieSearchResponse(title, link) {
                                    this.posterUrl = poster
                                }
                            } else {
                                newTvSeriesSearchResponse(title, link) {
                                    this.posterUrl = poster
                                }
                            }
                        } catch (e: Exception) {
                            logError(e)
                            null
                        }
                    }
                    
                    if (homeItems.isNotEmpty()) {
                        items.add(HomePageList(sectionName, homeItems))
                    }
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        if (items.isEmpty()) {
            // Fallback: try to get content from main page
            try {
                val doc = app.get(mainUrl, headers = commonHeaders).document
                val elements = doc.select(".MovieList .TPostMv, .TPMvCn .TPost, article.item")
                
                val fallbackItems = elements.mapNotNull { element ->
                    try {
                        val title = element.selectFirst("h2.Title, h3.Title, .title, h2, h3")?.text()?.trim()
                            ?: return@mapNotNull null
                        
                        val link = fixUrl(element.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                        val poster = element.selectFirst("img")?.let { img ->
                            val src = img.attr("data-src").takeIf { it.isNotEmpty() }
                                ?: img.attr("src").takeIf { it.isNotEmpty() }
                            src?.let { fixUrl(it) }
                        }
                        
                        newMovieSearchResponse(title, link) {
                            this.posterUrl = poster
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
                
                if (fallbackItems.isNotEmpty()) {
                    items.add(HomePageList("Contenido", fallbackItems))
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        if (items.isEmpty()) throw ErrorLoadingException("No se pudo cargar contenido")
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        
        try {
            val document = app.get(
                searchUrl, 
                timeout = 60,
                headers = commonHeaders
            ).document

            // Enhanced selectors for search results
            val possibleSelectors = listOf(
                ".MovieList .TPostMv",
                ".search-results .TPostMv",
                "article.TPost",
                ".TPMvCn .TPost", 
                ".movies-list .movie-item",
                "article.item.movies",
                ".item.movies",
                "li.xxx.TPostMv",
                ".search-result"
            )
            
            var elements: org.jsoup.select.Elements? = null
            
            for (selector in possibleSelectors) {
                elements = document.select(selector)
                if (elements.isNotEmpty()) break
            }
            
            return elements?.mapNotNull { element ->
                try {
                    val title = element.selectFirst("h2.Title, h3.Title, .title, h2, h3")?.text()?.trim()
                        ?: element.selectFirst("a")?.attr("title")?.trim()
                        ?: element.selectFirst("a")?.text()?.trim()
                        ?: return@mapNotNull null
                    
                    if (title.isEmpty()) return@mapNotNull null
                    
                    val href = fixUrl(element.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                    
                    val imgElement = element.selectFirst("img")
                    val image = imgElement?.let { img ->
                        val src = img.attr("data-src").takeIf { it.isNotEmpty() }
                            ?: img.attr("data-lazy-src").takeIf { it.isNotEmpty() }
                            ?: img.attr("src").takeIf { it.isNotEmpty() }
                        src?.let { fixUrl(it) }
                    }
                    
                    val isSerie = href.contains("/serie/") || href.contains("/tv/")

                    if (isSerie) {
                        newTvSeriesSearchResponse(title, href) {
                            this.posterUrl = image
                        }
                    } else {
                        newMovieSearchResponse(title, href) {
                            this.posterUrl = image
                        }
                    }
                } catch (e: Exception) {
                    logError(e)
                    null
                }
            } ?: emptyList()
        } catch (e: Exception) {
            logError(e)
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val soup = app.get(
                url, 
                timeout = 60,
                headers = commonHeaders
            ).document

            // Enhanced title extraction
            val title = soup.selectFirst("h1.Title, h1, .movie-title, .title")?.text()?.trim()?.let { text ->
                // Clean up title by removing common suffixes
                text.replace(Regex("\\s*-\\s*(Ver|Watch|Online|Gratis|HD).*$", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s*\\|.*$"), "")
                    .trim()
            } ?: return null
                
            // Enhanced description extraction
            val description = soup.selectFirst(".Description p, .synopsis, .overview, .plot")?.text()?.trim()
                ?: soup.selectFirst("meta[name='description']")?.attr("content")?.trim()
            
            // Enhanced poster extraction
            val posterElement = soup.selectFirst(".movtv-info .Image img, .poster img, .movie-poster img, img.wp-post-image")
                ?: soup.selectFirst("img[data-src], img")
            
            val poster = posterElement?.let { img ->
                val src = img.attr("data-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("src").takeIf { it.isNotEmpty() }
                src?.let { fixUrl(it) }
            }
            
            // Enhanced year extraction
            val yearText = soup.selectFirst(".year, .date, footer p.meta")?.text() ?: ""
            val year = Regex("(\\d{4})").find(yearText)?.value?.toIntOrNull()

            // Enhanced episode extraction for TV series
            val episodes = soup.select(
                ".all-episodes .TPostMv, " +
                ".episodes-list .episode, " +
                ".episode-list .TPostMv, " +
                "li.TPostMv, " +
                ".season-episodes .episode"
            ).mapNotNull { li ->
                try {
                    val epLink = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val href = fixUrl(epLink)
                    
                    val epThumb = li.selectFirst("img")?.let { img ->
                        val src = img.attr("data-src").takeIf { it.isNotEmpty() }
                            ?: img.attr("src").takeIf { it.isNotEmpty() }
                        src?.let { fixUrl(it) }
                    }
                    
                    // Enhanced episode number extraction
                    val episodeText = li.selectFirst(".Year, .episode-number, .number, h2")?.text() ?: ""
                    
                    // Try different patterns for season/episode
                    val seasonEpisode = when {
                        episodeText.contains("x") -> {
                            episodeText.split("x").mapNotNull { it.trim().toIntOrNull() }
                        }
                        episodeText.contains("-") -> {
                            episodeText.split("-").mapNotNull { it.trim().toIntOrNull() }
                        }
                        else -> {
                            // Try to extract numbers from the text
                            Regex("(\\d+)").findAll(episodeText).map { it.value.toInt() }.toList()
                        }
                    }
                    
                    val season = seasonEpisode.getOrNull(0) ?: 1
                    val episode = seasonEpisode.getOrNull(1) ?: 1
                    
                    newEpisode(href) {
                        this.name = li.selectFirst("h2, .title")?.text()?.trim()
                        this.season = season
                        this.episode = episode
                        this.posterUrl = epThumb
                    }
                } catch (e: Exception) {
                    logError(e)
                    null
                }
            }

            val tags = soup.select(".genres a, .genre a, .InfoList .AAIco-adjust:contains(Genero) a")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
            
            val tvType = if (episodes.isNotEmpty()) TvType.TvSeries else TvType.Movie
            
            // Enhanced recommendations extraction
            val recommendations = soup.select(
                ".related-posts .TPostMv, " +
                ".recommendations .item, " +
                ".MovieList .TPostMv, " +
                ".series_listado .xxx"
            ).mapNotNull { element ->
                try {
                    val recTitle = element.selectFirst("h2.Title, h3, .title")?.text()?.trim()
                        ?: return@mapNotNull null
                    
                    val recUrl = fixUrl(element.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                    
                    val image = element.selectFirst("img")?.let { img ->
                        val src = img.attr("data-src").takeIf { it.isNotEmpty() }
                            ?: img.attr("src").takeIf { it.isNotEmpty() }
                        src?.let { fixUrl(it) }
                    }
                    
                    newMovieSearchResponse(recTitle, recUrl) {
                        this.posterUrl = image
                    }
                } catch (e: Exception) {
                    logError(e)
                    null
                }
            }

            return when (tvType) {
                TvType.TvSeries -> {
                    newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                        this.posterUrl = poster
                        this.year = year
                        this.plot = description
                        this.tags = tags
                        this.recommendations = recommendations
                    }
                }
                TvType.Movie -> {
                    newMovieLoadResponse(title, url, TvType.Movie, url) {
                        this.posterUrl = poster
                        this.year = year
                        this.plot = description
                        this.tags = tags
                        this.recommendations = recommendations
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }

    data class FembedResponse(
        @JsonProperty("url") val url: String,
        @JsonProperty("success") val success: Boolean? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        try {
            val doc = app.get(
                data, 
                timeout = 60,
                headers = commonHeaders.plus("Referer" to mainUrl)
            ).document
            
            // Method 1: Enhanced player buttons and server options
            val serverSelectors = listOf(
                ".TPlayerTb button[data-tplayernv]",
                ".server-item[data-video]",
                "button[data-src]", 
                "button[data-url]",
                ".player-option[data-src]",
                ".aa-cn[data-src]",
                "a[data-video]",
                ".Button[onclick]"
            )
            
            serverSelectors.forEach { selector ->
                doc.select(selector).forEach { button ->
                    val videoUrl = button.attr("data-tplayernv").takeIf { it.isNotEmpty() }
                        ?: button.attr("data-video").takeIf { it.isNotEmpty() }
                        ?: button.attr("data-src").takeIf { it.isNotEmpty() }
                        ?: button.attr("data-url").takeIf { it.isNotEmpty() }
                        ?: extractUrlFromOnclick(button.attr("onclick"))
                    
                    if (videoUrl.isNotEmpty()) {
                        val fullUrl = fixUrl(videoUrl)
                        try {
                            loadExtractor(fullUrl, data, subtitleCallback, callback)
                            foundLinks = true
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }
                }
            }
            
            // Method 2: Enhanced iframe detection
            val iframeSelectors = listOf(
                "#video iframe",
                "#player iframe",
                ".TPlayer iframe",
                ".video-container iframe",
                ".player-container iframe",
                ".embed-responsive iframe",
                "iframe[src*='player']",
                "iframe[src*='embed']"
            )
            
            coroutineScope {
                iframeSelectors.forEach { selector ->
                    doc.select(selector).map { iframe ->
                        async {
                            val iframeUrl = iframe.attr("data-src").takeIf { it.isNotEmpty() }
                                ?: iframe.attr("src").takeIf { it.isNotEmpty() }
                                ?: return@async
                            
                            if (iframeUrl.startsWith("about:") || iframeUrl.startsWith("javascript:")) {
                                return@async
                            }
                            
                            val fullIframeUrl = fixUrl(iframeUrl)
                            
                            try {
                                // Handle specific fembed API
                                if (fullIframeUrl.contains("fembed") && fullIframeUrl.contains("?h=")) {
                                    handleFembedUrl(fullIframeUrl, data, subtitleCallback, callback)
                                    foundLinks = true
                                } else {
                                    loadExtractor(fullIframeUrl, data, subtitleCallback, callback)
                                    foundLinks = true
                                }
                            } catch (e: Exception) {
                                logError(e)
                            }
                        }
                    }.awaitAll()
                }
            }
            
            // Method 3: Extract URLs from JavaScript
            doc.select("script").forEach { script ->
                val content = script.html()
                
                // Enhanced URL patterns
                val urlPatterns = listOf(
                    Regex("""['"](https?://[^'"]+\.(?:mp4|m3u8|mkv|avi))['"']"""),
                    Regex("""['"](https?://[^'"]*(?:fembed|streamtape|doodstream|upstream|mixdrop|streamlare|evoload)[^'"]*)['"']"""),
                    Regex("""(?:src|file|url)["'\s]*:["'\s]*(https?://[^"']+)"""),
                    Regex("""player_url["'\s]*:["'\s]*["'](https?://[^"']+)["']""")
                )
                
                urlPatterns.forEach { pattern ->
                    pattern.findAll(content).forEach { match ->
                        val url = match.groupValues[1]
                        if (url.startsWith("http") && !url.contains(".js") && !url.contains(".css")) {
                            try {
                                loadExtractor(url, data, subtitleCallback, callback)
                                foundLinks = true
                            } catch (e: Exception) {
                                logError(e)
                            }
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            logError(e)
        }
        
        return foundLinks
    }
    
    // Helper function to handle fembed URLs
    private suspend fun handleFembedUrl(
        url: String, 
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val key = url.substringAfter("?h=").substringBefore("&")
            val baseUrl = url.substringBefore("/fembed")
            val apiUrl = "$baseUrl/fembed/api.php"
            
            val response = app.post(
                apiUrl,
                headers = commonHeaders.plus(
                    mapOf(
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to baseUrl,
                        "Referer" to referer
                    )
                ),
                data = mapOf("h" to key)
            )
            
            val json = parseJson<FembedResponse>(response.text)
            if (json.url.startsWith("http")) {
                loadExtractor(json.url, referer, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            logError(e)
        }
    }
    
    // Helper function to extract URL from onclick attributes
    private fun extractUrlFromOnclick(onclick: String): String {
        if (onclick.isEmpty()) return ""
        
        val patterns = listOf(
            Regex("""open\(['"]([^'"]+)['"]"""),
            Regex("""window\.location\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""href\s*=\s*['"]([^'"]+)['"]""")
        )
        
        patterns.forEach { pattern ->
            pattern.find(onclick)?.let { match ->
                return match.groupValues[1]
            }
        }
        
        return ""
    }
    
    // Helper function to fix URLs
    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
}
