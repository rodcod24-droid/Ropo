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
                val doc = app.get(url, timeout = 120).document
                
                // Try multiple possible selectors for content items
                val possibleSelectors = listOf(
                    "section.home-series li",                    // Original selector for series
                    "section li.xxx.TPostMv",                   // Original selector for movies
                    "li.xxx.TPostMv",                           // Without section
                    "article.TPost",                            // Common article format
                    "div.TPost",                                // Div format
                    ".MovieList article",                       // MovieList container
                    ".item.movies",                             // Item movies
                    "div.item",                                 // Generic item
                    ".movie-item",                              // Movie item
                    ".serie-item",                              // Serie item
                    "article",                                  // Generic articles
                    ".post-item",                               // Post items
                    ".content-item",                            // Content items
                    "[class*='movie']",                         // Any class containing 'movie'
                    "[class*='serie']",                         // Any class containing 'serie'
                    "[class*='post']"                           // Any class containing 'post'
                )
                
                var elements: org.jsoup.select.Elements? = null
                var usedSelector = ""
                
                for (selector in possibleSelectors) {
                    elements = doc.select(selector)
                    if (elements.isNotEmpty()) {
                        usedSelector = selector
                        break
                    }
                }
                
                if (elements != null && elements.isNotEmpty()) {
                    val homeItems = elements.mapNotNull { element ->
                        try {
                            // Try multiple title selectors
                            val title = element.selectFirst("h2.Title")?.text()?.trim()
                                ?: element.selectFirst("h2")?.text()?.trim()
                                ?: element.selectFirst("h3")?.text()?.trim()
                                ?: element.selectFirst(".title")?.text()?.trim()
                                ?: element.selectFirst(".movie-title")?.text()?.trim()
                                ?: element.selectFirst("a")?.attr("title")?.trim()
                                ?: return@mapNotNull null
                            
                            val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                            
                            // Try multiple image selectors
                            val poster = element.selectFirst("img.lazy")?.attr("data-src")
                                ?: element.selectFirst("img")?.attr("data-src")
                                ?: element.selectFirst("img")?.attr("src")
                                ?: element.selectFirst("img")?.attr("data-lazy-src")
                                ?: return@mapNotNull null
                            
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
        val searchUrl = "$mainUrl/?s=${query}"
        val document = app.get(searchUrl, timeout = 120).document

        // Try multiple selectors for search results
        val possibleSelectors = listOf(
            "li.xxx.TPostMv",
            "article.TPost", 
            "div.TPost",
            ".MovieList article",
            ".item.movies",
            "div.item",
            "article",
            ".search-result",
            ".post-item"
        )
        
        var elements: org.jsoup.select.Elements? = null
        
        for (selector in possibleSelectors) {
            elements = document.select(selector)
            if (elements.isNotEmpty()) break
        }
        
        return elements?.mapNotNull { element ->
            try {
                val title = element.selectFirst("h2.Title")?.text()?.trim()
                    ?: element.selectFirst("h2")?.text()?.trim()
                    ?: element.selectFirst("h3")?.text()?.trim()
                    ?: element.selectFirst(".title")?.text()?.trim()
                    ?: return@mapNotNull null
                
                val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                
                val image = element.selectFirst("img.lazy")?.attr("data-src")
                    ?: element.selectFirst("img")?.attr("data-src")
                    ?: element.selectFirst("img")?.attr("src")
                    ?: return@mapNotNull null
                
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
            val soup = app.get(url, timeout = 120).document

            // Try multiple title selectors
            val title = soup.selectFirst("h1.Title")?.text()?.trim()
                ?: soup.selectFirst("h1")?.text()?.trim()
                ?: soup.selectFirst(".movie-title")?.text()?.trim()
                ?: soup.selectFirst(".title")?.text()?.trim()
                ?: return null
                
            // Try multiple description selectors
            val description = soup.selectFirst(".Description p")?.text()?.trim()
                ?: soup.selectFirst(".synopsis")?.text()?.trim()
                ?: soup.selectFirst(".overview")?.text()?.trim()
                ?: soup.selectFirst("p")?.text()?.trim()
            
            // Try multiple poster selectors
            val poster = soup.selectFirst(".movtv-info div.Image img")?.attr("data-src")
                ?: soup.selectFirst(".poster img")?.attr("data-src")
                ?: soup.selectFirst("img")?.attr("data-src")
                ?: soup.selectFirst(".movtv-info div.Image img")?.attr("src")
                ?: soup.selectFirst(".poster img")?.attr("src")
                ?: soup.selectFirst("img")?.attr("src")
            
            // Extract year
            val year1 = soup.selectFirst("footer p.meta")?.toString() 
                ?: soup.selectFirst(".year")?.text()
                ?: soup.selectFirst(".date")?.text() ?: ""
            val yearRegex = Regex("(\\d{4})")
            val year = yearRegex.find(year1)?.value?.toIntOrNull()

            // Try multiple episode selectors
            val episodes = soup.select(".all-episodes li.TPostMv article, .episodes .episode, .episode-list .episode, li.episode").mapNotNull { li ->
                try {
                    val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    
                    val epThumb = li.selectFirst("div.Image img")?.attr("data-src")
                        ?: li.selectFirst("img.lazy")?.attr("data-src")
                        ?: li.selectFirst("img")?.attr("data-src")
                        ?: li.selectFirst("img")?.attr("src")
                    
                    // Try multiple episode number selectors
                    val seasonEpisodeText = li.selectFirst("span.Year")?.text()
                        ?: li.selectFirst(".episode-number")?.text()
                        ?: li.selectFirst(".number")?.text() ?: ""
                    
                    val seasonEpisode = seasonEpisodeText.split("x").mapNotNull { it.toIntOrNull() }
                    val isValid = seasonEpisode.size == 2
                    val season = if (isValid) seasonEpisode.getOrNull(0) else 1
                    val episode = if (isValid) seasonEpisode.getOrNull(1) else null
                    
                    newEpisode(href) {
                        this.name = null
                        this.season = season
                        this.episode = episode
                        this.posterUrl = epThumb?.let { fixUrl(it) }
                    }
                } catch (e: Exception) {
                    logError(e)
                    null
                }
            }

            val tags = soup.select("ul.InfoList li.AAIco-adjust:contains(Genero) a, .genres a, .genre a").map { it.text() }
            val tvType = if (episodes.isEmpty()) TvType.Movie else TvType.TvSeries
            
            // Try multiple recommendation selectors
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
                    val image = element.selectFirst("figure img")?.attr("data-src")
                        ?: element.selectFirst("img")?.attr("data-src")
                        ?: element.selectFirst("img")?.attr("src")
                    val recUrl = fixUrl(element.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                    
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
            val doc = app.get(data, timeout = 120).document
            
            // Try multiple iframe selectors
            val iframeSelectors = listOf(
                "div.TPlayer.embed_div iframe",
                ".player iframe",
                "iframe",
                ".video-player iframe",
                "[data-src*='http']"
            )
            
            var iframes: org.jsoup.select.Elements? = null
            
            for (selector in iframeSelectors) {
                iframes = doc.select(selector)
                if (iframes.isNotEmpty()) break
            }
            
            iframes?.apmap { iframe ->
                val iframeUrl = iframe.attr("data-src").takeIf { it.isNotEmpty() }
                    ?: iframe.attr("src").takeIf { it.isNotEmpty() }
                    ?: return@apmap
                
                val fullIframeUrl = fixUrl(iframeUrl)
                
                // Handle Cuevana specific embeds
                if (fullIframeUrl.contains("cuevana") && fullIframeUrl.contains("fembed")) {
                    val femregex = Regex("(https.\\/\\/.+\\/fembed\\/\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
                    femregex.findAll(fullIframeUrl).map { femreg ->
                        femreg.value
                    }.toList().apmap { fem ->
                        val key = fem.substringAfter("?h=")
                        val baseUrl = fem.substringBefore("/fembed")
                        val apiUrl = "$baseUrl/fembed/api.php"
                        val host = baseUrl.substringAfter("://").substringBefore("/")
                        
                        try {
                            val response = app.post(
                                apiUrl,
                                allowRedirects = false,
                                headers = mapOf(
                                    "Host" to host,
                                    "User-Agent" to USER_AGENT,
                                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                                    "Accept-Language" to "en-US,en;q=0.5",
                                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "Origin" to baseUrl,
                                    "DNT" to "1",
                                    "Connection" to "keep-alive",
                                ),
                                data = mapOf("h" to key)
                            ).text
                            val json = parseJson<Femcuevana>(response)
                            val link = json.url
                            if (link.contains("fembed")) {
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
