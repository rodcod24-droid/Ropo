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
    override var mainUrl = "https://w3nv.cuevana.pro"
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
        val urls = listOf(
            Pair(mainUrl, "Recientemente actualizadas"),
            Pair("$mainUrl/estrenos/", "Estrenos"),
        )
        
        // Add series section
        try {
            val seriesResponse = app.get("$mainUrl/serie", timeout = 120)
            val seriesElements = seriesResponse.document.select("section.home-series li")
            val seriesList = seriesElements.mapNotNull { element ->
                val title = element.selectFirst("h2.Title")?.text() ?: return@mapNotNull null
                val poster = element.selectFirst("img.lazy")?.attr("data-src") ?: return@mapNotNull null
                val url = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                
                TvSeriesSearchResponse(
                    title,
                    url,
                    this.name,
                    TvType.TvSeries,
                    poster,
                    null,
                    null,
                )
            }
            items.add(HomePageList("Series", seriesList))
        } catch (e: Exception) {
            logError(e)
        }

        // Process other main page sections
        for ((url, name) in urls) {
            try {
                val soup = app.get(url).document
                val home = soup.select("section li.xxx.TPostMv").mapNotNull { element ->
                    val title = element.selectFirst("h2.Title")?.text() ?: return@mapNotNull null
                    val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val posterImg = element.selectFirst("img.lazy")?.attr("data-src") ?: return@mapNotNull null
                    
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
                items.add(HomePageList(name, home))
            } catch (e: Exception) {
                logError(e)
            }
        }

        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("li.xxx.TPostMv").mapNotNull { element ->
            val title = element.selectFirst("h2.Title")?.text() ?: return@mapNotNull null
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val image = element.selectFirst("img.lazy")?.attr("data-src") ?: return@mapNotNull null
            val isSerie = href.contains("/serie/")

            if (isSerie) {
                TvSeriesSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.TvSeries,
                    image,
                    null,
                    null
                )
            } else {
                MovieSearchResponse(
                    title,
                    href,
                    this.name,
                    TvType.Movie,
                    image,
                    null
                )
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url, timeout = 120).document
        val title = soup.selectFirst("h1.Title")?.text() ?: return null
        val description = soup.selectFirst(".Description p")?.text()?.trim()
        val poster = soup.selectFirst(".movtv-info div.Image img")?.attr("data-src")
        
        // Extract year information
        val year1 = soup.selectFirst("footer p.meta")?.html() ?: ""
        val yearRegex = Regex("<span>(\\d+)</span>")
        val yearMatch = yearRegex.find(year1)?.destructured?.component1()
        val year = yearMatch?.toIntOrNull()
        
        // Extract episodes for series
        val episodes = soup.select(".all-episodes li.TPostMv article").mapNotNull { li ->
            val href = li.select("a").attr("href")
            val epThumb = li.selectFirst("div.Image img")?.attr("data-src") 
                ?: li.selectFirst("img.lazy")?.attr("data-src")
            val seasonEpisodeText = li.selectFirst("span.Year")?.text() ?: ""
            
            // Parse season and episode numbers
            val seasonEpisode = seasonEpisodeText.split("x").mapNotNull { it.toIntOrNull() }
            val isValid = seasonEpisode.size == 2
            val episode = if (isValid) seasonEpisode.getOrNull(1) else null
            val season = if (isValid) seasonEpisode.getOrNull(0) else null
            
            if (href.isNotEmpty()) {
                Episode(
                    href,
                    null,
                    season,
                    episode,
                    if (epThumb != null) fixUrl(epThumb) else null
                )
            } else null
        }
        
        val tags = soup.select("ul.InfoList li.AAIco-adjust:contains(Genero) a").map { it.text() }
        val tvType = if (episodes.isEmpty()) TvType.Movie else TvType.TvSeries
        
        // Extract recommendations
        val recelement = if (tvType == TvType.TvSeries) {
            "main section div.series_listado.series div.xxx"
        } else {
            "main section ul.MovieList li"
        }
        
        val recommendations = soup.select(recelement).mapNotNull { element ->
            val recTitle = element.select("h2.Title").text()
            if (recTitle.isEmpty()) return@mapNotNull null
            
            val image = element.select("figure img")?.attr("data-src")
            val recUrl = element.select("a").attr("href")
            if (recUrl.isEmpty()) return@mapNotNull null
            
            MovieSearchResponse(
                recTitle,
                fixUrl(recUrl),
                this.name,
                TvType.Movie,
                image,
                year = null
            )
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
        val iframes = app.get(data).document.select("div.TPlayer.embed_div iframe")
        
        iframes.map { iframe ->
            async {
                try {
                    val iframeUrl = fixUrl(iframe.attr("data-src"))
                    
                    // Process fembed URLs
                    if (iframeUrl.contains("api.cuevana3.me/fembed/")) {
                        processFembedUrls(iframeUrl, data, subtitleCallback, callback)
                    }
                    
                    // Process tomatomatela URLs  
                    if (iframeUrl.contains("tomatomatela")) {
                        processTomatomatelaUrls(iframeUrl, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Continue processing other iframes if one fails
                }
            }
        }.awaitAll()
        
        true
    }

    private suspend fun processFembedUrls(
        iframe: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val femregex = Regex("(https.\\/\\/api\\.cuevana3\\.me\\/fembed\\/\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
        val matches = femregex.findAll(iframe).map { it.value }.toList()
        
        matches.map { fem ->
            async {
                try {
                    val key = fem.replace("https://api.cuevana3.me/fembed/?h=", "")
                    val headers = mapOf(
                        "Host" to "api.cuevana3.me",
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to "https://api.cuevana3.me",
                        "DNT" to "1",
                        "Connection" to "keep-alive",
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "same-origin",
                    )
                    
                    val response = app.post(
                        "https://api.cuevana3.me/fembed/api.php",
                        allowRedirects = false,
                        headers = headers,
                        data = mapOf("h" to key)
                    ).text
                    
                    val json = parseJson<Femcuevana>(response)
                    val link = json.url
                    if (link.contains("fembed")) {
                        loadExtractor(link, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Continue processing other fembed URLs
                }
            }
        }.awaitAll()
    }

    private suspend fun processTomatomatelaUrls(
        iframe: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val tomatoRegex = Regex("(\\/\\/apialfa.tomatomatela.com\\/ir\\/player.php\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
        val matches = tomatoRegex.findAll(iframe).map { it.value }.toList()
        
        matches.map { tom ->
            async {
                try {
                    val tomkey = tom.replace("//apialfa.tomatomatela.com/ir/player.php?h=", "")
                    val headers = mapOf(
                        "Host" to "apialfa.tomatomatela.com",
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Origin" to "null",
                        "DNT" to "1",
                        "Connection" to "keep-alive",
                        "Upgrade-Insecure-Requests" to "1",
                        "Sec-Fetch-Dest" to "iframe",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "same-origin",
                    )
                    
                    val response = app.post(
                        "https://apialfa.tomatomatela.com/ir/rd.php",
                        allowRedirects = false,
                        headers = headers,
                        data = mapOf("url" to tomkey)
                    )
                    
                    val locationHeaders = response.okhttpResponse.headers.values("location")
                    processLocationHeaders(locationHeaders, data, subtitleCallback, callback)
                } catch (e: Exception) {
                    // Continue processing other tomatomatela URLs
                }
            }
        }.awaitAll()
    }

    private suspend fun processLocationHeaders(
        locationHeaders: List<String>,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        locationHeaders.map { loc ->
            async {
                try {
                    when {
                        loc.contains("goto_ddh.php") -> {
                            processGotoDdhUrls(loc, data, subtitleCallback, callback)
                        }
                        loc.contains("index.php?h=") -> {
                            processIndexUrls(loc, data, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    // Continue processing other location headers
                }
            }
        }.awaitAll()
    }

    private suspend fun processGotoDdhUrls(
        loc: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val gotoregex = Regex("(\\/\\/api.cuevana3.me\\/ir\\/goto_ddh.php\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
        val matches = gotoregex.findAll(loc).map { 
            it.value.replace("//api.cuevana3.me/ir/goto_ddh.php?h=", "") 
        }.toList()
        
        matches.map { gotolink ->
            async {
                try {
                    val headers = mapOf(
                        "Host" to "api.cuevana3.me",
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Origin" to "null",
                        "DNT" to "1",
                        "Connection" to "keep-alive",
                        "Upgrade-Insecure-Requests" to "1",
                        "Sec-Fetch-Dest" to "iframe",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "same-origin",
                    )
                    
                    val response = app.post(
                        "https://api.cuevana3.me/ir/redirect_ddh.php",
                        allowRedirects = false,
                        headers = headers,
                        data = mapOf("url" to gotolink)
                    )
                    
                    val redirectHeaders = response.okhttpResponse.headers.values("location")
                    redirectHeaders.forEach { golink ->
                        loadExtractor(golink, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Continue processing other goto URLs
                }
            }
        }.awaitAll()
    }

    private suspend fun processIndexUrls(
        loc: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val indexRegex = Regex("(\\/\\/api.cuevana3.me\\/sc\\/index.php\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
        val matches = indexRegex.findAll(loc).map { 
            it.value.replace("//api.cuevana3.me/sc/index.php?h=", "") 
        }.toList()
        
        matches.map { inlink ->
            async {
                try {
                    val headers = mapOf(
                        "Host" to "api.cuevana3.me",
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Accept-Encoding" to "gzip, deflate, br",
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Origin" to "null",
                        "DNT" to "1",
                        "Connection" to "keep-alive",
                        "Upgrade-Insecure-Requests" to "1",
                        "Sec-Fetch-Dest" to "iframe",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "same-origin",
                        "Sec-Fetch-User" to "?1",
                    )
                    
                    val response = app.post(
                        "https://api.cuevana3.me/sc/r.php",
                        allowRedirects = false,
                        headers = headers,
                        data = mapOf("h" to inlink)
                    )
                    
                    val redirectHeaders = response.okhttpResponse.headers.values("location")
                    redirectHeaders.forEach { link ->
                        loadExtractor(link, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Continue processing other index URLs
                }
            }
        }.awaitAll()
    }
}
