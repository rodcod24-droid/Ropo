package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
    override val vpnStatus = VPNStatus.MightBeNeeded // Due to evoload sometimes not loading

    override val mainPage = mainPageOf(
        Pair("$mainUrl/series/page/", "Series"),
        Pair("$mainUrl/peliculas/page/", "Peliculas"),
        Pair("$mainUrl/anime/page/", "Animes"),
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val soup = app.get(url).document
        
        val home = soup.select("ul.list-movie li").mapNotNull { element ->
            val title = element.selectFirst("a.link-title h2")?.text() ?: return@mapNotNull null
            val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val posterImg = element.selectFirst("a.poster img")?.attr("src") ?: return@mapNotNull null
            
            TvSeriesSearchResponse(
                title,
                link,
                this.name,
                if (link.contains("/pelicula/")) TvType.Movie else TvType.TvSeries,
                posterImg,
                null,
                null,
            )
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("li.xxx.TPostMv").mapNotNull { element ->
            val title = element.selectFirst("h2.Title")?.text() ?: return@mapNotNull null
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val image = element.selectFirst("img.lazy")?.attr("data-src") ?: return@mapNotNull null
            val isMovie = href.contains("/pelicula/")

            if (isMovie) {
                MovieSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.Movie,
                    image,
                    null
                )
            } else {
                TvSeriesSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.TvSeries,
                    image,
                    null,
                    null
                )
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url, timeout = 120).document

        val title = soup.selectFirst("h1.title-post")?.text() ?: return null
        val description = soup.selectFirst("p.text-content:nth-child(3)")?.text()?.trim()
        val poster = soup.selectFirst("article.TPost img.lazy")?.attr("data-src")
        
        val episodes = soup.select(".TPostMv article").mapNotNull { li ->
            // Try multiple selectors for the episode link
            val href = li.select("a").attr("href")
                .takeIf { it.isNotEmpty() }
                ?: li.select(".C a").attr("href")
                .takeIf { it.isNotEmpty() }
                ?: li.select("article a").attr("href")
                .takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            
            val epThumb = li.selectFirst("div.Image img")?.attr("data-src")
            val seasonEpisodeText = li.selectFirst("span.Year")?.text() ?: ""
            
            // Parse season and episode numbers
            val seasonEpisode = seasonEpisodeText.split("x").mapNotNull { subStr -> 
                subStr.toIntOrNull() 
            }
            val isValid = seasonEpisode.size == 2
            val episode = if (isValid) seasonEpisode.getOrNull(1) else null
            val season = if (isValid) seasonEpisode.getOrNull(0) else null
            
            Episode(
                href,
                null,
                season,
                episode,
                if (epThumb?.contains("svg") == true) null else epThumb
            )
        }
        
        val tvType = if (url.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
        
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
        val iframes = doc.select("div.TPlayer.embed_div iframe")
        
        iframes.map { iframe ->
            async {
                try {
                    val iframeUrl = fixUrl(iframe.attr("data-src"))
                    loadExtractor(iframeUrl, data, subtitleCallback, callback)
                } catch (e: Exception) {
                    // Continue processing other iframes if one fails
                }
            }
        }.awaitAll()
        
        true
    }
}
