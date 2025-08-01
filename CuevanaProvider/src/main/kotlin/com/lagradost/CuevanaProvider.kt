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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        
        try {
            // Get main page content
            val document = app.get(mainUrl, timeout = 120).document
            
            // Try to find movies section
            val moviesSection = document.select("section.home-movies li, .movies-section li, section li.xxx.TPostMv").take(20)
            if (moviesSection.isNotEmpty()) {
                val moviesList = moviesSection.mapNotNull { element ->
                    try {
                        val title = element.selectFirst("h2.Title, h2, .title")?.text()?.trim() ?: return@mapNotNull null
                        val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                        val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                        val poster = element.selectFirst("img")?.run {
                            attr("data-src").takeIf { it.isNotEmpty() }
                                ?: attr("data-lazy-src").takeIf { it.isNotEmpty() }
                                ?: attr("src").takeIf { it.isNotEmpty() }
                        }
                        
                        if (fullLink.contains("/pelicula/")) {
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
                if (moviesList.isNotEmpty()) {
                    items.add(HomePageList("Películas", moviesList))
                }
            }
            
            // Try to get series from dedicated series page
            try {
                val seriesDoc = app.get("$mainUrl/serie", timeout = 120).document
                val seriesList = seriesDoc.select("section.home-series li, section li.xxx.TPostMv").take(20).mapNotNull { element ->
                    try {
                        val title = element.selectFirst("h2.Title, h2, .title")?.text()?.trim() ?: return@mapNotNull null
                        val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                        val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                        val poster = element.selectFirst("img")?.run {
                            attr("data-src").takeIf { it.isNotEmpty() }
                                ?: attr("data-lazy-src").takeIf { it.isNotEmpty() }
                                ?: attr("src").takeIf { it.isNotEmpty() }
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
            
            // Try to get releases/estrenos
            try {
                val estrenosDoc = app.get("$mainUrl/estrenos", timeout = 120).document
                val estrenosList = estrenosDoc.select("section li.xxx.TPostMv, .movies-list li").take(20).mapNotNull { element ->
                    try {
                        val title = element.selectFirst("h2.Title, h2, .title")?.text()?.trim() ?: return@mapNotNull null
                        val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                        val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                        val poster = element.selectFirst("img")?.run {
                            attr("data-src").takeIf { it.isNotEmpty() }
                                ?: attr("data-lazy-src").takeIf { it.isNotEmpty() }
                                ?: attr("src").takeIf { it.isNotEmpty() }
                        }
                        
                        if (fullLink.contains("/pelicula/")) {
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
                if (estrenosList.isNotEmpty()) {
                    items.add(HomePageList("Estrenos", estrenosList))
                }
            } catch (e: Exception) {
                logError(e)
            }
            
            // Fallback: get any content from main page
            if (items.isEmpty()) {
                val fallbackContent = document.select("li.xxx.TPostMv, .movie-item, .content-item").take(30).mapNotNull { element ->
                    try {
                        val title = element.selectFirst("h2.Title, h2, h3, .title")?.text()?.trim() ?: return@mapNotNull null
                        val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                        val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
                        val poster = element.selectFirst("img")?.run {
                            attr("data-src").takeIf { it.isNotEmpty() }
                                ?: attr("data-lazy-src").takeIf { it.isNotEmpty() }
                                ?: attr("src").takeIf { it.isNotEmpty() }
                        }
                        
                        if (fullLink.contains("/pelicula/")) {
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
                if (fallbackContent.isNotEmpty()) {
                    items.add(HomePageList("Contenido", fallbackContent))
                }
            }
            
        } catch (e: Exception) {
            logError(e)
        }

        if (items.isEmpty()) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val url = "$mainUrl/explorar?s=$query"
            val document = app.get(url, timeout = 120).document
//https://cuevana.pro/explorar?s=
            return document.select("li.xxx.TPostMv, .search-item, .movie-item").mapNotNull { element ->
                try {
                    val title = element.selectFirst("h2.Title, h2, h3, .title")?.text()?.trim() ?: return@mapNotNull null
                    val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                    val image = element.selectFirst("img")?.run {
                        attr("data-src").takeIf { it.isNotEmpty() }
                            ?: attr("data-lazy-src").takeIf { it.isNotEmpty() }
                            ?: attr("src").takeIf { it.isNotEmpty() }
                    }
                    val isSerie = fullHref.contains("/serie/")

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
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val soup = app.get(url, timeout = 120).document
            val title = soup.selectFirst("h1.Title, h1")?.text()?.trim() ?: return null
            val description = soup.selectFirst(".Description p, .description")?.text()?.trim()
            val poster = soup.selectFirst(".movtv-info div.Image img, .poster img")?.run {
                attr("data-src").takeIf { it.isNotEmpty() } ?: attr("src")
            }
            
            // Extract year
            val year1 = soup.selectFirst("footer p.meta, .year")?.html() ?: ""
            val yearRegex = Regex("<span>(\\d+)</span>|(\\d{4})")
            val yearMatch = yearRegex.find(year1)?.destructured?.component1() ?: yearRegex.find(year1)?.destructured?.component2()
            val year = yearMatch?.toIntOrNull()
            
            // Extract episodes for series
            val episodes = soup.select(".all-episodes li.TPostMv article, .episodes-list .episode").mapNotNull { li ->
                try {
                    val href = li.select("a").attr("href")
                    if (href.isEmpty()) return@mapNotNull null
                    val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                    
                    val epThumb = li.selectFirst("div.Image img, img")?.run {
                        attr("data-src").takeIf { it.isNotEmpty() } ?: attr("src")
                    }
                    val seasonEpisodeText = li.selectFirst("span.Year, .episode-number")?.text() ?: ""
                    
                    // Parse season and episode numbers
                    val seasonEpisode = seasonEpisodeText.split("x").mapNotNull { it.toIntOrNull() }
                    val isValid = seasonEpisode.size == 2
                    val episode = if (isValid) seasonEpisode.getOrNull(1) else null
                    val season = if (isValid) seasonEpisode.getOrNull(0) else null
                    
                    newEpisode(fullHref) {
                        this.season = season
                        this.episode = episode
                        this.posterUrl = epThumb?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
                    }
                } catch (e: Exception) {
                    null
                }
            }
            
            val tags = soup.select("ul.InfoList li.AAIco-adjust:contains(Genero) a, .genres a").map { it.text() }
            val tvType = if (episodes.isEmpty()) TvType.Movie else TvType.TvSeries
            
            return when (tvType) {
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
    ): Boolean = coroutineScope {
        try {
            val doc = app.get(data, timeout = 120).document
            val iframes = doc.select("div.TPlayer.embed_div iframe, .player iframe")
            
            iframes.map { iframe ->
                async {
                    try {
                        val iframeUrl = iframe.attr("data-src").takeIf { it.isNotEmpty() }
                            ?: iframe.attr("src")
                        if (iframeUrl.isNotEmpty()) {
                            val fullUrl = if (iframeUrl.startsWith("http")) iframeUrl else "$mainUrl$iframeUrl"
                            loadExtractor(fullUrl, data, subtitleCallback, callback)
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
}
