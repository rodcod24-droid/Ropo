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
    override var mainUrl = "https://cuevana3.ch"
    override var name = "Cuevana"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        Pair("$mainUrl/movies", "Películas"),
        Pair("$mainUrl/series", "Series"),
        Pair("$mainUrl/trending", "Tendencias"),
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "${request.data}/page/$page"
        val soup = app.get(url).document
        
        val home = soup.select("div.movie-item, div.item, article").mapNotNull { element ->
            val title = element.selectFirst("h3, h2, .title, .movie-title")?.text()?.trim() ?: return@mapNotNull null
            val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val fullLink = if (link.startsWith("http")) link else "$mainUrl$link"
            val posterImg = element.selectFirst("img")?.attr("src") ?: element.selectFirst("img")?.attr("data-src") ?: return@mapNotNull null
            
            if (request.name.contains("Películas") || fullLink.contains("/movie/") || fullLink.contains("/pelicula/")) {
                newMovieSearchResponse(title, fullLink) {
                    this.posterUrl = posterImg
                }
            } else {
                newTvSeriesSearchResponse(title, fullLink) {
                    this.posterUrl = posterImg
                }
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${query.replace(" ", "%20")}"
        val document = app.get(url).document

        return document.select("div.movie-item, div.item, article").mapNotNull { element ->
            val title = element.selectFirst("h3, h2, .title, .movie-title")?.text()?.trim() ?: return@mapNotNull null
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
            val image = element.selectFirst("img")?.attr("src") ?: element.selectFirst("img")?.attr("data-src") ?: return@mapNotNull null

            if (fullHref.contains("/movie/") || fullHref.contains("/pelicula/")) {
                newMovieSearchResponse(title, fullHref) {
                    this.posterUrl = image
                }
            } else {
                newTvSeriesSearchResponse(title, fullHref) {
                    this.posterUrl = image
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url).document

        val title = soup.selectFirst("h1, .movie-title, .title")?.text()?.trim() ?: return null
        val description = soup.selectFirst(".description, .overview, .synopsis")?.text()?.trim()
        val poster = soup.selectFirst(".poster img, .movie-poster img, img")?.attr("src") 
            ?: soup.selectFirst(".poster img, .movie-poster img, img")?.attr("data-src")
        
        val episodes = soup.select(".episode-item, .episode, li").mapNotNull { li ->
            val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
            val epThumb = li.selectFirst("img")?.attr("src") ?: li.selectFirst("img")?.attr("data-src")
            val name = li.selectFirst(".episode-title, .title")?.text()?.trim() ?: "Episode"
            
            val seasonEpisodeText = li.selectFirst(".episode-number, .number")?.text() ?: ""
            val match = Regex("S(\\d+)E(\\d+)|Season\\s+(\\d+)\\s+Episode\\s+(\\d+)|(\\d+)x(\\d+)").find(seasonEpisodeText)
            val season = match?.let { 
                it.groupValues.getOrNull(1)?.toIntOrNull() 
                    ?: it.groupValues.getOrNull(3)?.toIntOrNull() 
                    ?: it.groupValues.getOrNull(5)?.toIntOrNull() 
            } ?: 1
            val episode = match?.let { 
                it.groupValues.getOrNull(2)?.toIntOrNull() 
                    ?: it.groupValues.getOrNull(4)?.toIntOrNull() 
                    ?: it.groupValues.getOrNull(6)?.toIntOrNull() 
            }
            
            newEpisode(fullHref) {
                this.name = name
                this.season = season
                this.episode = episode
                this.posterUrl = epThumb
            }
        }

        val tvType = if (url.contains("/movie/") || url.contains("/pelicula/") || episodes.isEmpty()) TvType.Movie else TvType.TvSeries
        
        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = description
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
        val doc = app.get(data).document

        // Look for video players
        doc.select("iframe, video, [data-src*='http'], [src*='http']").map { element ->
            async {
                try {
                    val src = element.attr("data-src").takeIf { it.startsWith("http") }
                        ?: element.attr("src").takeIf { it.startsWith("http") }
                        ?: return@async
                    
                    loadExtractor(src, data, subtitleCallback, callback)
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }.awaitAll()

        // Look for JavaScript embedded URLs
        doc.select("script").map { script ->
            async {
                try {
                    val content = script.data()
                    val urlRegex = Regex("https?://[^\\s\"'<>]+\\.(mp4|m3u8|mkv|avi)")
                    urlRegex.findAll(content).forEach { match ->
                        val videoUrl = match.value
                        callback(
                            ExtractorLink(
                                source = name,
                                name = name,
                                url = videoUrl,
                                referer = data,
                                quality = Qualities.Unknown.value,
                                isM3u8 = videoUrl.contains("m3u8")
                            )
                        )
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }.awaitAll()

        true
    }
}
