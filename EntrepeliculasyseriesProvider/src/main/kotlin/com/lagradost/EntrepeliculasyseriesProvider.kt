override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        
        // Try different sections with specific URLs
        val sections = listOf(
            Triple("Películas", "$mainUrl/peliculas", listOf(".MovieList .TPostMv", ".movies .item", ".content .item")),
            Triple("Series", "$mainUrl/series", listOf(".MovieList .TPostMv", ".series .item", ".content .item")),
            Triple("Anime", "$mainUrl/animes", listOf(".MovieList .TPostMv", ".anime .item", ".content .item"))
        )
        
        for ((sectionName, sectionUrl, selectors) in sections) {
            try {
                val soup = app.get(
                    sectionUrl, 
                    timeout = 120,
                    headers = mapOf("User-Agent" to userAgent)
                ).document
                
                for (selector in selectors) {
                    val elements = soup.select(selector)
                    if (elements.isNotEmpty()) {
                        val sectionItems = elements.take(15).mapNotNull { element ->
                            try {
                                val title = element.selectFirst("h2.Title, h3.Title, .title")?.text()?.trim()
                                    ?: return@mapNotNull null
                                
                                val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                                val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                                
                                val posterImg = element.selectFirst("img")?.run {
                                    listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attr ->
                                        attr(attr).takeIf { it.isNotEmpty() && !it.contains("data:image") }
                                    }
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
                        
                        if (sectionItems.isNotEmpty()) {
                            items.add(HomePageList(sectionName, sectionItems))
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                logError(e)
                continue
            }
        }

        // Fallback: try main page
        if (items.isEmpty()) {
            try {
                val mainDoc = app.get(
                    mainUrl, 
                    timeout = 120,
                    headers = mapOf("User-Agent" to userAgent)
                ).document
                
                val fallbackItems = mainDoc.select(".MovieList .TPostMv, .content .item, .movie-item").take(30).mapNotNull { element ->
                    try {
                        val title = element.selectFirst("h2.Title, h3.Title, .title")?.text()?.trim()
                            ?: return@mapNotNull null
                        val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                        val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                        val posterImg = element.selectFirst("img")?.run {
                            listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attr ->
                                attr(attr).takeIf { it.isNotEmpty() && !it.contains("data:image") }
                            }
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
                        null
                    }package com.lagradost

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

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        
        // Try different main page approaches
        val sections = listOf(
            Triple("Películas", "$mainUrl/peliculas", listOf(".movies .item", ".content .item", "article.item")),
            Triple("Series", "$mainUrl/series", listOf(".series .item", ".content .item", "article.item")),
            Triple("Estrenos", "$mainUrl/estrenos", listOf(".releases .item", ".content .item", "article.item"))
        )
        
        for ((sectionName, sectionUrl, selectors) in sections) {
            try {
                val soup = app.get(
                    sectionUrl, 
                    timeout = 120,
                    headers = mapOf("User-Agent" to userAgent)
                ).document
                
                for (selector in selectors) {
                    val elements = soup.select(selector)
                    if (elements.isNotEmpty()) {
                        val sectionItems = elements.take(20).mapNotNull { element ->
                            try {
                                val title = element.selectFirst("h2, h3, .title")?.text()?.trim()
                                    ?: return@mapNotNull null
                                
                                val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                                val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                                
                                val posterImg = element.selectFirst("img")?.run {
                                    listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attr ->
                                        attr(attr).takeIf { it.isNotEmpty() && !it.contains("data:image") }
                                    }
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
                        
                        if (sectionItems.isNotEmpty()) {
                            items.add(HomePageList(sectionName, sectionItems))
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                logError(e)
                continue
            }
        }

        // Fallback: try main page
        if (items.isEmpty()) {
            try {
                val mainDoc = app.get(
                    mainUrl, 
                    timeout = 120,
                    headers = mapOf("User-Agent" to userAgent)
                ).document
                
                val fallbackSelectors = listOf(
                    ".movies .item", ".series .item", ".content .item", 
                    "article.item", ".movie-item", ".post"
                )
                
                for (selector in fallbackSelectors) {
                    val elements = mainDoc.select(selector)
                    if (elements.isNotEmpty()) {
                        val fallbackItems = elements.take(30).mapNotNull { element ->
                            try {
                                val title = element.selectFirst("h2, h3, .title")?.text()?.trim()
                                    ?: return@mapNotNull null
                                val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                                val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                                val posterImg = element.selectFirst("img")?.run {
                                    listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attr ->
                                        attr(attr).takeIf { it.isNotEmpty() && !it.contains("data:image") }
                                    }
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
                                null
                            }
                        }
                        
                        if (fallbackItems.isNotEmpty()) {
                            items.add(HomePageList("Contenido", fallbackItems))
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        return newHomePageResponse(items.ifEmpty { 
            listOf(HomePageList("No Content", emptyList())) 
        })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrls = listOf(
                "$mainUrl/?s=${query.replace(" ", "+")}",
                "$mainUrl/search?q=${query.replace(" ", "+")}",
                "$mainUrl/buscar?s=${query.replace(" ", "+")}"
            )
            
            for (url in searchUrls) {
                try {
                    val document = app.get(
                        url, 
                        timeout = 120,
                        headers = mapOf("User-Agent" to userAgent)
                    ).document

                    val contentSelectors = listOf(
                        ".search-results .item",
                        ".movies .item", 
                        ".content .item",
                        "article.item",
                        ".movie-item"
                    )
                    
                    for (selector in contentSelectors) {
                        val results = document.select(selector).mapNotNull { element ->
                            try {
                                val title = element.selectFirst("h2, h3, .title")?.text()?.trim()
                                    ?: return@mapNotNull null
                                val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                                val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                                
                                val image = element.selectFirst("img")?.run {
                                    listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attr ->
                                        attr(attr).takeIf { it.isNotEmpty() && !it.contains("data:image") }
                                    }
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
                } catch (e: Exception) {
                    logError(e)
                    continue
                }
            }
            
            emptyList()
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val soup = app.get(
                url, 
                timeout = 120,
                headers = mapOf("User-Agent" to userAgent)
            ).document

            val title = soup.selectFirst("h1, .title, .movie-title")?.text()?.trim() ?: return null
            val description = soup.selectFirst(".description, .synopsis, .overview")?.text()?.trim()
            val poster = soup.selectFirst(".poster img, img")?.run {
                listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attr ->
                    attr(attr).takeIf { it.isNotEmpty() && !it.contains("data:image") }
                }
            }
            
            // Extract year
            val yearText = soup.selectFirst(".year, .date, .meta")?.text() ?: soup.text()
            val year = Regex("(19|20)\\d{2}").find(yearText)?.value?.toIntOrNull()
            
            // Extract episodes for series
            val episodes = soup.select(".episodes .episode, .episode-list li, .season li").mapNotNull { li ->
                try {
                    val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                    
                    val epThumb = li.selectFirst("img")?.run {
                        listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attr ->
                            attr(attr).takeIf { it.isNotEmpty() && !it.contains("data:image") }
                        }
                    }
                    
                    val episodeText = li.selectFirst(".episode-number, .number")?.text() ?: href
                    val episodeName = li.selectFirst(".episode-title, .title")?.text()
                    
                    // Parse season and episode from various formats
                    val patterns = listOf(
                        Regex("(\\d+)x(\\d+)"),  // 1x1
                        Regex("temporada[/-]?(\\d+).*?capitulo[/-]?(\\d+)", RegexOption.IGNORE_CASE),  // temporada-1-capitulo-1
                        Regex("s(\\d+)e(\\d+)", RegexOption.IGNORE_CASE)  // s1e1
                    )
                    
                    var season: Int? = null
                    var episode: Int? = null
                    
                    for (pattern in patterns) {
                        val match = pattern.find(episodeText.lowercase())
                        if (match != null) {
                            season = match.groupValues[1].toIntOrNull() ?: 1
                            episode = match.groupValues[2].toIntOrNull()
                            break
                        }
                    }
                    
                    if (season == null) season = 1
                    
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
            
            val tags = soup.select(".genres a, .genre, .tags a").map { it.text().trim() }
            val tvType = if (url.contains("/pelicula/") || episodes.isEmpty()) TvType.Movie else TvType.TvSeries
            
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
    ): Boolean {
        return try {
            val doc = app.get(
                data, 
                timeout = 120,
                headers = mapOf("User-Agent" to userAgent)
            ).document
            
            // Look for iframes
            doc.select("iframe, .player iframe, .video-player iframe").forEach { iframe ->
                try {
                    val iframeUrl = iframe.attr("data-src").takeIf { it.isNotEmpty() }
                        ?: iframe.attr("src").takeIf { it.isNotEmpty() }
                        ?: return@forEach
                    
                    val fullUrl = if (iframeUrl.startsWith("http")) iframeUrl else "$mainUrl$iframeUrl"
                    loadExtractor(fullUrl, data, subtitleCallback, callback)
                } catch (e: Exception) {
                    logError(e)
                }
            }
            
            // Look for script tags with video URLs
            doc.select("script").forEach { script ->
                try {
                    val scriptContent = script.data()
                    if (scriptContent.contains("http") && (scriptContent.contains(".mp4") || scriptContent.contains(".m3u8"))) {
                        val urlPattern = Regex("(?:\"|\')([^\"\']*(?:\\.mp4|\\.m3u8)[^\"\']*?)(?:\"|\')")
                        urlPattern.findAll(scriptContent).forEach { match ->
                            val url = match.groupValues[1]
                            if (url.startsWith("http")) {
                                loadExtractor(url, data, subtitleCallback, callback)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
            
            true
        } catch (e: Exception) {
            logError(e)
            false
        }
    }
}
