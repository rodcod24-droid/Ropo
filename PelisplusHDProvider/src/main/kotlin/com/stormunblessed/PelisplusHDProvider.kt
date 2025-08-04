package com.stormunblessed

import android.webkit.URLUtil
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class PelisplusHDProvider : MainAPI() {
    override var mainUrl = "https://pelisplushd.bz"
    override var name = "PelisplusHD"
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
        val document = app.get(mainUrl).document
        val map = mapOf(
            "Películas" to "#default-tab-1",
            "Series" to "#default-tab-2",
            "Anime" to "#default-tab-3",
            "Doramas" to "#default-tab-4",
        )
        
        map.forEach { (name, selector) ->
            val elements = document.select(selector).select("a.Posters-link").mapNotNull { element ->
                element.toSearchResult()
            }
            items.add(HomePageList(name, elements))
        }
        return HomePageResponse(items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".listing-content p")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst(".Posters-img")?.attr("src")?.let { fixUrl(it) }
        val isMovie = href.contains("/pelicula/")
        
        return if (isMovie) {
            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                href,
                TvType.TvSeries
            ) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=${query}"
        val document = app.get(url).document

        return document.select("a.Posters-link").mapNotNull { element ->
            val title = element.selectFirst(".listing-content p")?.text() ?: return@mapNotNull null
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val posterUrl = element.selectFirst(".Posters-img")?.attr("src")?.let { fixUrl(it) }
            val isMovie = href.contains("/pelicula/")

            if (isMovie) {
                newMovieSearchResponse(
                    title,
                    href,
                    TvType.Movie
                ) {
                    this.posterUrl = posterUrl
                }
            } else {
                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst(".m-b-5")?.text() ?: return null
        val description = doc.selectFirst("div.text-large")?.text()?.trim()
        val poster = doc.selectFirst(".img-fluid")?.attr("src")?.let { fixUrl(it) }
        
        val episodes = doc.select("div.tab-pane .btn").mapNotNull { li ->
            val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val name = li.selectFirst(".btn-primary.btn-block")?.text()
                ?.replace(Regex("(T(\\d+).*E(\\d+):)"), "")?.trim()
            
            val seasonInfo = href.substringAfter("temporada/").replace("/capitulo/", "-")
            val seasonId = seasonInfo.split("-").mapNotNull { it.toIntOrNull() }
            
            val isValid = seasonId.size == 2
            val episode = if (isValid) seasonId.getOrNull(1) else null
            val season = if (isValid) seasonId.getOrNull(0) else null
            
            newEpisode(href) {
                this.name = name
                this.season = season
                this.episode = episode
            }
        }

        val year = doc.selectFirst(".p-r-15 .text-semibold")?.text()?.toIntOrNull()
        val tvType = if (url.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
        val tags = doc.select(".p-h-15.text-center a span.font-size-18.text-info.text-semibold")
            .map { it.text().trim().replace(", ", "") }

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                    this.tags = tags
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = description
                    this.tags = tags
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
    ): Boolean {
        val doc = app.get(data).document
        
        doc.select("div.player").forEach { script ->
            val playerData = script.data()
                .replace("https://api.mycdn.moe/furl.php?id=", "https://www.fembed.com/v/")
                .replace("https://api.mycdn.moe/sblink.php?id=", "https://streamsb.net/e/")
            
            fetchUrls(playerData).apmap { link ->
                processPlayerLink(link, data, subtitleCallback, callback)
            }
        }
        return true
    }

    private suspend fun processPlayerLink(
        link: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val regex = """(go_to_player|go_to_playerVast)\('(.*?)'""".toRegex()
            val linkDoc = app.get(link).document.html()
            
            regex.findAll(linkDoc).forEach { match ->
                val current = match.groupValues.getOrNull(2) ?: return@forEach
                var extractorLink: String? = null
                
                if (URLUtil.isValidUrl(current)) {
                    extractorLink = fixUrl(current)
                } else {
                    try {
                        extractorLink = base64Decode(match.groupValues.getOrNull(1) ?: "")
                    } catch (e: Exception) {
                        // Handle decode error
                    }
                }

                if (!extractorLink.isNullOrBlank()) {
                    if (extractorLink.contains("https://api.mycdn.moe/video/") || 
                        extractorLink.contains("https://api.mycdn.moe/embed.php?customid")) {
                        
                        processMycdnLink(extractorLink, link, subtitleCallback, callback)
                    } else {
                        loadExtractor(extractorLink, data, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            // Handle errors gracefully
        }
    }

    private suspend fun processMycdnLink(
        link: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(link).document
            doc.select("div.ODDIV li").apmap { element ->
                val linkEncoded = element.attr("data-r")
                val linkDecoded = base64Decode(linkEncoded)
                    .replace(Regex("https://owodeuwu.xyz|https://sypl.xyz"), "https://embedsito.com")
                    .replace(Regex(".poster.*"), "")
                
                val secondLink = element.attr("onclick")
                    .substringAfter("go_to_player('")
                    .substringBefore("',")
                
                loadExtractor(linkDecoded, referer, subtitleCallback, callback)
                
                try {
                    val response = app.get(
                        "https://api.mycdn.moe/player/?id=$secondLink",
                        allowRedirects = false
                    ).document
                    
                    val thirdLink = response.selectFirst("body > iframe")?.attr("src")
                        ?.replace(Regex("https://owodeuwu.xyz|https://sypl.xyz"), "https://embedsito.com")
                        ?.replace(Regex(".poster.*"), "")
                    
                    if (!thirdLink.isNullOrBlank()) {
                        loadExtractor(thirdLink, referer, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Handle nested request error
                }
            }
        } catch (e: Exception) {
            // Handle mycdn processing error
        }
    }

    // Helper function that was missing
    private fun fetchUrls(text: String): List<String> {
        val urlRegex = Regex("https?://[^\\s\"'<>]+")
        return urlRegex.findAll(text).map { it.value }.toList()
    }
}
