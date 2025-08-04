package com.stormunblessed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class Pelisplus4KProvider : MainAPI() {
    override var mainUrl = "https://ww3.pelisplus.to"
    override var name = "Pelisplus4K"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair("Peliculas", "$mainUrl/peliculas"),
            Pair("Series", "$mainUrl/series"),
            Pair("Doramas", "$mainUrl/doramas"),
            Pair("Animes", "$mainUrl/animes"),
        )

        urls.apmap { (name, url) ->
            val doc = app.get(url).document
            val home = doc.select(".articlesList article").mapNotNull {
                val title = it.selectFirst("a h2")?.text()
                val link = it.selectFirst("a.itemA")?.attr("href")
                val img = it.selectFirst("picture img")?.attr("data-src")
                
                if (title != null && link != null) {
                    newTvSeriesSearchResponse(
                        title,
                        link,
                        TvType.TvSeries,
                    ) {
                        this.posterUrl = img
                    }
                } else null
            }
            items.add(HomePageList(name, home))
        }
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/search/$query"
        val doc = app.get(url).document
        return doc.select("article.item").mapNotNull {
            val title = it.selectFirst("a h2")?.text()
            val link = it.selectFirst("a.itemA")?.attr("href")
            val img = it.selectFirst("picture img")?.attr("data-src")
            
            if (title != null && link != null) {
                newTvSeriesSearchResponse(
                    title,
                    link,
                    TvType.TvSeries,
                ) {
                    this.posterUrl = img
                }
            } else null
        }
    }

    private data class MainTemporadaElement(
        val title: String? = null,
        val image: String? = null,
        val season: Int? = null,
        val episode: Int? = null, // Fixed: was 'newepisode'
        val newepisode: Int? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val tvType = if (url.contains("pelicula")) TvType.Movie else TvType.TvSeries
        val title = doc.selectFirst(".slugh1")?.text() ?: return null
        val backimage = doc.selectFirst("head meta[property=og:image]")?.attr("content") ?: ""
        val poster = backimage.replace("original", "w500")
        val description = doc.selectFirst("div.description")?.text() ?: ""
        val tags = doc.select("div.home__slider .genres:contains(Generos) a").map { it.text() }
        val episodes = ArrayList<Episode>()

        if (tvType == TvType.TvSeries) {
            val script = doc.select("script").firstOrNull { it.html().contains("seasonsJson = ") }?.html()
            if (!script.isNullOrEmpty()) {
                try {
                    val jsonScript = script.substringAfter("seasonsJson = ").substringBefore(";")
                    val json = parseJson<Map<String, List<MainTemporadaElement>>>(jsonScript)
                    
                    json.values.forEach { list ->
                        list.forEach { info ->
                            val epTitle = info.title
                            val seasonNum = info.season
                            val epNum = info.episode ?: info.newepisode
                            val img = info.image
                            val realImg = if (!img.isNullOrEmpty()) {
                                "https://image.tmdb.org/t/p/w342${img.replace("\\/", "/")}"
                            } else null
                            val epUrl = "$url/season/$seasonNum/episode/$epNum"
                            
                            episodes.add(
                                newEpisode(epUrl) {
                                    this.name = epTitle
                                    this.season = seasonNum
                                    this.episode = epNum
                                    this.posterUrl = realImg
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Handle parsing error gracefully
                }
            }
        }

        return when (tvType) {
            TvType.TvSeries -> {
                newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage
                    this.plot = description
                    this.tags = tags
                }
            }
            TvType.Movie -> {
                newMovieLoadResponse(title, url, tvType, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backimage
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
        doc.select("div ul.subselect li").apmap {
            try {
                val encodedOne = it.attr("data-server").toByteArray()
                val encodedTwo = base64Encode(encodedOne)
                val linkRegex = Regex("window\\.location\\.href\\s*=\\s*'(.*?)'")
                val text = app.get("$mainUrl/player/$encodedTwo").text
                val link = linkRegex.find(text)?.destructured?.component1()
                
                if (!link.isNullOrBlank()) {
                    loadExtractor(link, mainUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Handle errors gracefully
            }
        }
        return true
    }
}
