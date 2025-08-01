package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class CinecalidadProvider : MainAPI() {
    override var mainUrl = "https://cinecalidad.lol"
    override var name = "Cinecalidad"
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
        Pair("$mainUrl/ver-serie/page/", "Series"),
        Pair("$mainUrl/page/", "Peliculas"),
        Pair("$mainUrl/genero-de-la-pelicula/peliculas-en-calidad-4k/page/", "4K UHD"),
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val soup = app.get(url).document
        
        val home = soup.select(".item.movies").mapNotNull { element ->
            val title = element.selectFirst("div.in_title")?.text() ?: return@mapNotNull null
            val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val posterImg = element.selectFirst(".poster.custom img")?.attr("data-src") ?: return@mapNotNull null
            
            if (link.contains("/ver-pelicula/")) {
                newMovieSearchResponse(title, link) {
                    this.posterUrl = posterImg
                }
            } else {
                newTvSeriesSearchResponse(title, link) {
                    this.posterUrl = posterImg
                }
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("article").mapNotNull { element ->
            val title = element.selectFirst("div.in_title")?.text() ?: return@mapNotNull null
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val image = element.selectFirst(".poster.custom img")?.attr("data-src") ?: return@mapNotNull null
            val isMovie = href.contains("/ver-pelicula/")

            if (isMovie) {
                newMovieSearchResponse(title, href) {
                    this.posterUrl = image
                }
            } else {
                newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = image
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url, timeout = 120).document

        val title = soup.selectFirst(".single_left h1")?.text() ?: return null
        val description = soup.selectFirst("div.single_left table tbody tr td p")?.text()?.trim()
        val poster = soup.selectFirst(".alignnone")?.attr("data-src")
        
        val episodes = soup.select("div.se-c div.se-a ul.episodios li").mapNotNull { li ->
            val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val epThumb = li.selectFirst("img.lazy")?.attr("data-src")
            val name = li.selectFirst(".episodiotitle a")?.text() ?: "Episode"
            
            // Parse season and episode numbers from the episode numbering
            val seasonEpisodeText = li.selectFirst(".numerando")?.text()?.replace(Regex("(S|E)"), "") ?: ""
            val seasonEpisode = seasonEpisodeText.split("-").mapNotNull { it.toIntOrNull() }
            val isValid = seasonEpisode.size == 2
            val episode = if (isValid) seasonEpisode.getOrNull(1) else null
            val season = if (isValid) seasonEpisode.getOrNull(0) else null
            
            newEpisode(href) {
                this.name = name
                this.season = season
                this.episode = episode
                this.posterUrl = if (epThumb?.contains("svg") == true) null else epThumb
            }
        }

        val tvType = if (url.contains("/ver-pelicula/")) TvType.Movie else TvType.TvSeries
        
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
        val response = app.get(data)
        val doc = response.document
        val datatext = response.text

        // Process player options
        val playerOptions = doc.select(".dooplay_player_option")
        playerOptions.map { element ->
            async {
                try {
                    val url = element.attr("data-option")
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                    
                    // Handle special Cinecalidad URLs
                    if (url.startsWith("https://cinecalidad.lol")) {
                        processCinecalidadUrl(url, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Continue processing other options if one fails
                }
            }
        }.awaitAll()

        // Handle Spanish content if available
        if (datatext.contains("en castellano")) {
            try {
                val spanishDoc = app.get("$data?ref=es").document
                val spanishOptions = spanishDoc.select(".dooplay_player_option")
                
                spanishOptions.map { element ->
                    async {
                        try {
                            val url = element.attr("data-option")
                            loadExtractor(url, mainUrl, subtitleCallback, callback)
                            
                            if (url.startsWith("https://cinecalidad.lol")) {
                                processCinecalidadUrl(url, data, subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                            // Continue processing
                        }
                    }
                }.awaitAll()
            } catch (e: Exception) {
                // Spanish content processing failed, continue
            }
        }

        // Process subtitle downloads
        if (datatext.contains("Subtítulo LAT") || datatext.contains("Forzados LAT")) {
            processSubtitles(doc, data, subtitleCallback)
        }

        true
    }

    private suspend fun processCinecalidadUrl(
        url: String,
        refererData: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        val cineurlregex = Regex("(https:\\/\\/cinecalidad\\.lol\\/play\\/\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
        val matches = cineurlregex.findAll(url).map { 
            it.value.replace("/play/", "/play/r.php") 
        }.toList()
        
        matches.map { processedUrl ->
            async {
                try {
                    val headers = mapOf(
                        "Host" to "cinecalidad.lol",
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "DNT" to "1",
                        "Connection" to "keep-alive",
                        "Referer" to refererData,
                        "Upgrade-Insecure-Requests" to "1",
                        "Sec-Fetch-Dest" to "iframe",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Site" to "same-origin",
                        "Sec-Fetch-User" to "?1",
                    )
                    
                    val response = app.get(processedUrl, headers = headers, allowRedirects = false)
                    val locationHeaders = response.okhttpResponse.headers.values("location")
                    
                    locationHeaders.map { extractedUrl ->
                        async {
                            if (extractedUrl.contains("cinestart")) {
                                loadExtractor(extractedUrl, mainUrl, subtitleCallback, callback)
                            }
                        }
                    }.awaitAll()
                } catch (e: Exception) {
                    // URL processing failed, continue
                }
            }
        }.awaitAll()
    }

    private suspend fun processSubtitles(
        doc: org.jsoup.nodes.Document,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) = coroutineScope {
        val subtitleLinks = doc.select("#panel_descarga.pane a")
        
        subtitleLinks.map { element ->
            async {
                try {
                    val link = if (data.contains("serie") || data.contains("episodio")) {
                        "$data${element.attr("href")}"
                    } else {
                        element.attr("href")
                    }
                    
                    val subtitleDoc = app.get(link)
                    val subtitlePage = subtitleDoc.document
                    val subtitleText = subtitleDoc.text
                    
                    if (subtitleText.contains("Subtítulo") || subtitleText.contains("Forzados")) {
                        val langregex = Regex("(Subtítulo.*\$|Forzados.*\$)")
                        val langdoc = subtitlePage.selectFirst("div.titulo h3")?.text() ?: ""
                        val reallang = langregex.find(langdoc)?.destructured?.component1()
                        
                        if (reallang != null) {
                            val subtitleDownloadLinks = subtitlePage.select("a.link")
                            subtitleDownloadLinks.forEach { downloadLink ->
                                val sublink = if (data.contains("serie") || data.contains("episodio")) {
                                    "$data${downloadLink.attr
