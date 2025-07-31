package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class PelisplusHDProvider : MainAPI() {
    override var mainUrl = "https://pelisplushd.mx"
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
        map.forEach {
            items.add(HomePageList(
                it.key,
                document.select(it.value).select("a.Posters-link").map { element ->
                    element.toSearchResult()
                }
            ))
        }
        return HomePageResponse(items)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select(".listing-content p").text()
        val href = this.select("a").attr("href")
        val posterUrl = this.select(".Posters-img").attr("src")
        val isMovie = href.contains("/pelicula/")
        return if (isMovie) {
            MovieSearchResponse(
                title,
                href,
                name,
                TvType.Movie,
                posterUrl,
                null
            )
        } else {
            TvSeriesSearchResponse(
                title,
                href,
                name,
                TvType.TvSeries,
                posterUrl,
                null,
                null
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?s=${query}"
        val document = app.get(url).document

        return document.select("a.Posters-link").map {
            val title = it.selectFirst(".listing-content p")!!.text()
            val href = it.selectFirst("a")!!.attr("href")
            val image = it.selectFirst(".Posters-img")!!.attr("src")
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

        val title = soup.selectFirst(".m-b-5")!!.text()
        val description = soup.selectFirst("div.text-large")?.text()?.trim()
        val poster: String? = soup.selectFirst(".img-fluid")!!.attr("src")
        val episodes = soup.select("div.tab-pane .btn").map { li ->
            val href = li.selectFirst("a")!!.attr("href")
            val name = li.selectFirst(".btn-primary.btn-block")!!.text()
            val seasonid = href.replace("/capitulo/", "-")
                .replace(Regex("$mainUrl/.*/.*/temporada/"), "").let { str ->
                    str.split("-").mapNotNull { subStr -> subStr.toIntOrNull() }
                }
            val isValid = seasonid.size == 2
            val episode = if (isValid) seasonid.getOrNull(1) else null
            val season = if (isValid) seasonid.getOrNull(0) else null
            Episode(
                href,
                name,
                season,
                episode,
            )
        }

        val year = soup.selectFirst(".p-r-15 .text-semibold")!!.text().toIntOrNull()
        val tvType = if (url.contains("/pelicula/")) TvType.Movie else TvType.TvSeries
        val tags = soup.select(".p-h-15.text-center a span.font-size-18.text-info.text-semibold")
            .map { it?.text()?.trim().toString().replace(", ", "") }

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
    }

    private fun extractVideoUrls(text: String): List<String> {
        val urls = mutableListOf<String>()
        
        // Extract various video URL patterns including fembed URLs
        val patterns = listOf(
            Regex("\"(https?://[^\"]*\\.(mp4|m3u8|mkv)[^\"]*)\"|'(https?://[^']*\\.(mp4|m3u8|mkv)[^']*)'"),
            Regex("file:\\s*[\"'](https?://[^\"']+)[\"']"),
            Regex("src:\\s*[\"'](https?://[^\"']+)[\"']"),
            Regex("url:\\s*[\"'](https?://[^\"']+)[\"']"),
            Regex("(https?://(?:www\\.)?(?:fembed|embedsb|streamtape|doodstream|uqload|mixdrop|upstream|voe)\\.(?:com|net|org|io|to|me)/[^\\s\"'<>]+)"),
            Regex("\"($mainUrl/fembed\\.php\\?url=[^\"]+)\"")
        )
        
        patterns.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                val url = match.groupValues.find { it.startsWith("http") }
                if (url != null && !urls.contains(url)) {
                    urls.add(url)
                }
            }
        }
        
        return urls
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        app.get(data).document.select("div.player > script").map { script ->
            val scriptData = script.data()
            extractVideoUrls(scriptData).map { url ->
                // Convert fembed.php URLs to direct fembed URLs
                if (url.contains("$mainUrl/fembed.php?url=")) {
                    url.replace("$mainUrl/fembed.php?url=", "https://www.fembed.com/v/")
                } else {
                    url
                }
            }.apmap {
                loadExtractor(it, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
