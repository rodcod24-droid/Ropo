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

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        
        // Try to get different sections
        val sections = listOf(
            Triple("$mainUrl/serie", "Series", "series"),
            Triple("$mainUrl/peliculas", "Películas", "movies"),
            Triple("$mainUrl/estrenos", "Estrenos", "releases"),
            Triple(mainUrl, "Recientes", "recent")
        )
        
        for ((url, sectionName, sectionType) in sections) {
            try {
                val doc = app.get(url, 
                    timeout = 120,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
                    )
                ).document
                
                // Enhanced selectors for content items
                val possibleSelectors = listOf(
                    "section.home-series li",
                    "section li.xxx.TPostMv",
                    "li.xxx.TPostMv",
                    "article.TPost",
                    "div.TPost",
                    ".MovieList article",
                    ".MovieList li",
                    ".item.movies",
                    "div.item",
                    ".movie-item",
                    ".serie-item",
                    "article",
                    ".post-item",
                    ".content-item",
                    "[class*='movie']",
                    "[class*='serie']",
                    "[class*='post']",
                    ".TPMvCn",
                    ".TPost",
                    "div[class*='Movie']",
                    "li[class*='TPost']"
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
                            // Enhanced title extraction
                            val titleElement = element.selectFirst("h2.Title")
                                ?: element.selectFirst("h2")
                                ?: element.selectFirst("h3")
                                ?: element.selectFirst(".title")
                                ?: element.selectFirst(".movie-title")
                                ?: element.selectFirst("a[title]")
                                ?: element.selectFirst("a")
                            
                            val title = titleElement?.text()?.trim()
                                ?: titleElement?.attr("title")?.trim()
                                ?: return@mapNotNull null
                            
                            if (title.isEmpty()) return@mapNotNull null
                            
                            // Enhanced link extraction
                            val linkElement = element.selectFirst("a")
                            val rawLink = linkElement?.attr("href") ?: return@mapNotNull null
                            val link = if (rawLink.startsWith("http")) {
                                rawLink
                            } else if (rawLink.startsWith("/")) {
                                "$mainUrl$rawLink"
                            } else {
                                "$mainUrl/$rawLink"
                            }
                            
                            // Enhanced image extraction
                            val imgElement = element.selectFirst("img.lazy")
                                ?: element.selectFirst("img[data-src]")
                                ?: element.selectFirst("img[data-lazy-src]")
                                ?: element.selectFirst("img")
                            
                            val rawPoster = imgElement?.attr("data-src")
                                ?: imgElement?.attr("data-lazy-src")
                                ?: imgElement?.attr("src")
                                ?: return@mapNotNull null
                            
                            val poster = if (rawPoster.startsWith("http")) {
                                rawPoster
                            } else if (rawPoster.startsWith("/")) {
                                "$mainUrl$rawPoster"
                            } else {
                                "$mainUrl/$rawPoster"
                            }
                            
                            // Determine type based on URL or section
                            when {
                                link.contains("/pelicula/") || sectionType == "movies" -> {
                                    newMovieSearchResponse(title, link) {
                                        this.posterUrl = poster
                                    }
                                }
                                link.contains("/serie/") || sectionType == "series" -> {
                                    newTvSeriesSearchResponse(title, link) {
                                        this.posterUrl = poster
                                    }
                                }
                                else -> {
                                    // Default to movie if unclear
                                    newMovieSearchResponse(title, link) {
                                        this.posterUrl = poster
                                    }
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

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl, 
            timeout = 120,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
            )
        ).document

        // Enhanced selectors for search results
        val possibleSelectors = listOf(
            "li.xxx.TPostMv",
            "article.TPost", 
            "div.TPost",
            ".MovieList article",
            ".MovieList li",
            ".item.movies",
            "div.item",
            "article",
            ".search-result",
            ".post-item",
            ".TPMvCn",
            "div[class*='Movie']",
            "li[class*='TPost']"
        )
        
        var elements: org.jsoup.select.Elements? = null
        
        for (selector in possibleSelectors) {
            elements = document.select(selector)
            if (elements.isNotEmpty()) break
        }
        
        return elements?.mapNotNull { element ->
            try {
                // Enhanced title extraction
                val titleElement = element.selectFirst("h2.Title")
                    ?: element.selectFirst("h2")
                    ?: element.selectFirst("h3")
                    ?: element.selectFirst(".title")
                    ?: element.selectFirst("a[title]")
                    ?: element.selectFirst("a")
                
                val title = titleElement?.text()?.trim()
                    ?: titleElement?.attr("title")?.trim()
                    ?: return@mapNotNull null
                
                if (title.isEmpty()) return@mapNotNull null
                
                // Enhanced link extraction
                val rawHref = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val href = if (rawHref.startsWith("http")) {
                    rawHref
                } else if (rawHref.startsWith("/")) {
                    "$mainUrl$rawHref"
                } else {
                    "$mainUrl/$rawHref"
                }
                
                // Enhanced image extraction
                val imgElement = element.selectFirst("img.lazy")
                    ?: element.selectFirst("img[data-src]")
                    ?: element.selectFirst("img[data-lazy-src]")
                    ?: element.selectFirst("img")
                
                val rawImage = imgElement?.attr("data-src")
                    ?: imgElement?.attr("data-lazy-src")
                    ?: imgElement?.attr("src")
                    ?: return@mapNotNull null
                
                val image = if (rawImage.startsWith("http")) {
                    rawImage
                } else if (rawImage.startsWith("/")) {
                    "$mainUrl$rawImage"
                } else {
                    "$mainUrl/$rawImage"
                }
                
                val isSerie = href.contains("/serie/")

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
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val soup = app.get(url, 
                timeout = 120,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
                )
            ).document

            // Enhanced title extraction
            val titleElement = soup.selectFirst("h1.Title")
                ?: soup.selectFirst("h1")
                ?: soup.selectFirst(".movie-title")
                ?: soup.selectFirst(".title")
                ?: soup.selectFirst("title")
            
            val title = titleElement?.text()?.trim()?.let { text ->
                // Clean up title by removing common suffixes
                text.replace(Regex("\\s*-\\s*(Ver|Watch|Online|Gratis|HD).*$", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s*\\|.*$"), "")
                    .trim()
            } ?: return null
                
            // Enhanced description extraction
            val description = soup.selectFirst(".Description p")?.text()?.trim()
                ?: soup.selectFirst(".synopsis")?.text()?.trim()
                ?: soup.selectFirst(".overview")?.text()?.trim()
                ?: soup.selectFirst("meta[name='description']")?.attr("content")?.trim()
                ?: soup.selectFirst("p")?.text()?.trim()
            
            // Enhanced poster extraction
            val posterElement = soup.selectFirst(".movtv-info div.Image img")
                ?: soup.selectFirst(".poster img")
                ?: soup.selectFirst("img[data-src]")
                ?: soup.selectFirst("img")
            
            val rawPoster = posterElement?.attr("data-src")
                ?: posterElement?.attr("src")
            
            val poster = rawPoster?.let { raw ->
                if (raw.startsWith("http")) {
                    raw
                } else if (raw.startsWith("/")) {
                    "$mainUrl$raw"
                } else {
                    "$mainUrl/$raw"
                }
            }
            
            // Enhanced year extraction
            val yearText = soup.selectFirst("footer p.meta")?.toString() 
                ?: soup.selectFirst(".year")?.text()
                ?: soup.selectFirst(".date")?.text()
                ?: soup.selectFirst("meta[property='video:release_date']")?.attr("content")
                ?: ""
            val yearRegex = Regex("(\\d{4})")
            val year = yearRegex.find(yearText)?.value?.toIntOrNull()

            // Enhanced episode extraction
            val episodes = soup.select(".all-episodes li.TPostMv article, .episodes .episode, .episode-list .episode, li.episode, .TPostMv").mapNotNull { li ->
                try {
                    val epLink = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val href = if (epLink.startsWith("http")) {
                        epLink
                    } else if (epLink.startsWith("/")) {
                        "$mainUrl$epLink"
                    } else {
                        "$mainUrl/$epLink"
                    }
                    
                    val thumbElement = li.selectFirst("div.Image img")
                        ?: li.selectFirst("img.lazy")
                        ?: li.selectFirst("img[data-src]")
                        ?: li.selectFirst("img")
                    
                    val rawThumb = thumbElement?.attr("data-src")
                        ?: thumbElement?.attr("src")
                    
                    val epThumb = rawThumb?.let { raw ->
                        if (raw.startsWith("http")) {
                            raw
                        } else if (raw.startsWith("/")) {
                            "$mainUrl$raw"
                        } else {
                            "$mainUrl/$raw"
                        }
                    }
                    
                    // Enhanced episode number extraction
                    val seasonEpisodeText = li.selectFirst("span.Year")?.text()
                        ?: li.selectFirst(".episode-number")?.text()
                        ?: li.selectFirst(".number")?.text()
                        ?: li.selectFirst("h2")?.text() ?: ""
                    
                    val seasonEpisode = seasonEpisodeText.split("x").mapNotNull { it.trim().toIntOrNull() }
                    val isValid = seasonEpisode.size == 2
                    val season = if (isValid) seasonEpisode.getOrNull(0) else 1
                    val episode = if (isValid) seasonEpisode.getOrNull(1) else null
                    
                    newEpisode(href) {
                        this.name = null
                        this.season = season
                        this.episode = episode
                        this.posterUrl = epThumb
                    }
                } catch (e: Exception) {
                    logError(e)
                    null
                }
            }

            val tags = soup.select("ul.InfoList li.AAIco-adjust:contains(Genero) a, .genres a, .genre a").map { it.text() }
            val tvType = if (episodes.isEmpty()) TvType.Movie else TvType.TvSeries
            
            // Enhanced recommendations extraction
            val recelement = if (tvType == TvType.TvSeries) 
                "main section div.series_listado.series div.xxx, .recommendations .item, .related .item"
            else 
                "main section ul.MovieList li, .recommendations .item, .related .item"
                
            val recommendations = soup.select(recelement).mapNotNull { element ->
                try {
                    val recTitle = element.selectFirst("h2.Title")?.text()
                        ?: element.selectFirst("h3")?.text()
                        ?: element.selectFirst(".title")?.text()
                        ?: return@mapNotNull null
                    
                    val imgEl = element.selectFirst("figure img")
                        ?: element.selectFirst("img[data-src]")
                        ?: element.selectFirst("img")
                    
                    val rawImage = imgEl?.attr("data-src") ?: imgEl?.attr("src")
                    val image = rawImage?.let { raw ->
                        if (raw.startsWith("http")) {
                            raw
                        } else if (raw.startsWith("/")) {
                            "$mainUrl$raw"
                        } else {
                            "$mainUrl/$raw"
                        }
                    }
                    
                    val rawRecUrl = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val recUrl = if (rawRecUrl.startsWith("http")) {
                        rawRecUrl
                    } else if (rawRecUrl.startsWith("/")) {
                        "$mainUrl$rawRecUrl"
                    } else {
                        "$mainUrl/$rawRecUrl"
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

    data class Femcuevana(
        @JsonProperty("url") val url: String,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(data, 
                timeout = 120,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
                )
            ).document
            
            // Enhanced iframe selectors
            val iframeSelectors = listOf(
                "div.TPlayer.embed_div iframe",
                ".player iframe",
                "iframe[data-src]",
                "iframe[src]",
                "iframe",
                ".video-player iframe",
                "[data-src*='http']",
                ".embed iframe",
                "#player iframe"
            )
            
            var iframes: org.jsoup.select.Elements? = null
            
            for (selector in iframeSelectors) {
                iframes = doc.select(selector)
                if (iframes.isNotEmpty()) break
            }
            
            if (iframes == null || iframes.isEmpty()) {
                // Try to find embedded player buttons or links
                val playerButtons = doc.select("button[data-src], a[data-src], .player-option")
                playerButtons.forEach { button ->
                    val buttonUrl = button.attr("data-src")
                    if (buttonUrl.isNotEmpty()) {
                        val fullUrl = if (buttonUrl.startsWith("http")) {
                            buttonUrl
                        } else if (buttonUrl.startsWith("/")) {
                            "$mainUrl$buttonUrl"
                        } else {
                            "$mainUrl/$buttonUrl"
                        }
                        loadExtractor(fullUrl, data, subtitleCallback, callback)
                    }
                }
            }
            
            iframes?.apmap { iframe ->
                val rawIframeUrl = iframe.attr("data-src").takeIf { it.isNotEmpty() }
                    ?: iframe.attr("src").takeIf { it.isNotEmpty() }
                    ?: return@apmap
                
                val fullIframeUrl = if (rawIframeUrl.startsWith("http")) {
                    rawIframeUrl
                } else if (rawIframeUrl.startsWith("/")) {
                    "$mainUrl$rawIframeUrl"
                } else {
                    "$mainUrl/$rawIframeUrl"
                }
                
                // Handle Cuevana specific embeds
                if (fullIframeUrl.contains("cuevana") && fullIframeUrl.contains("fembed")) {
                    val femregex = Regex("(https.\\/\\/.+\\/fembed\\/\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
                    femregex.findAll(fullIframeUrl).map { femreg ->
                        femreg.value
                    }.toList().apmap { fem ->
                        val key = fem.substringAfter("?h=")
                        val baseUrl = fem.substringBefore("/fembed")
                        val apiUrl = "$baseUrl/fembed/api.php"
                        val host = try {
                            baseUrl.substringAfter("://").substringBefore("/")
                        } catch (e: Exception) {
                            "cuevana.pro"
                        }
                        
                        try {
                            val response = app.post(
                                apiUrl,
                                allowRedirects = false,
                                headers = mapOf(
                                    "Host" to host,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                                    "Accept-Language" to "en-US,en;q=0.5",
                                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "Origin" to baseUrl,
                                    "DNT" to "1",
                                    "Connection" to "keep-alive",
                                    "Referer" to data
                                ),
                                data = mapOf("h" to key)
                            ).text
                            val json = parseJson<Femcuevana>(response)
                            val link = json.url
                            if (link.contains("fembed") || link.startsWith("http")) {
                                loadExtractor(link, data, subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }
                } else {
                    // Try to load as direct extractor
                    loadExtractor(fullIframeUrl, data, subtitleCallback, callback)
                }
            }
            return true
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }
}
