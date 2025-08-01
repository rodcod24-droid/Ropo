package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.*

class AnimeflvnetProvider : MainAPI() {
    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Especial")) TvType.OVA
            else if (t.contains("Película")) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getDubStatus(title: String): DubStatus {
            return if (title.contains("Latino") || title.contains("Castellano"))
                DubStatus.Dubbed
            else DubStatus.Subbed
        }
    }

    override var mainUrl = "https://www3.animeflv.net"
    override var name = "Animeflv.net"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Anime,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urls = listOf(
            Pair("$mainUrl/browse?type[]=movie&order=updated", "Películas"),
            Pair("$mainUrl/browse?status[]=2&order=default", "Animes"),
            Pair("$mainUrl/browse?status[]=1&order=rating", "En emision"),
        )
        val items = ArrayList<HomePageList>()
        
        // Get latest episodes first
        try {
            val latestEpisodes = app.get(mainUrl).document.select("main.Main ul.ListEpisodios li").mapNotNull {
                val title = it.selectFirst("strong.Title")?.text() ?: return@mapNotNull null
                val poster = it.selectFirst("span img")?.attr("src") ?: return@mapNotNull null
                val epRegex = Regex("(-(\\d+)\$)")
                val url = it.selectFirst("a")?.attr("href")?.replace(epRegex, "")
                    ?.replace("ver/", "anime/") ?: return@mapNotNull null
                val epNum = it.selectFirst("span.Capi")?.text()?.replace("Episodio ", "")?.toIntOrNull()
                
                newAnimeSearchResponse(title, url) {
                    this.posterUrl = fixUrl(poster)
                    addDubStatus(getDubStatus(title), epNum)
                }
            }
            items.add(HomePageList("Últimos episodios", latestEpisodes))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Process other categories
        for ((url, name) in urls) {
            try {
                val doc = app.get(url).document
                val home = doc.select("ul.ListAnimes li article").mapNotNull { element ->
                    val title = element.selectFirst("h3.Title")?.text() ?: return@mapNotNull null
                    val poster = element.selectFirst("figure img")?.attr("src") ?: return@mapNotNull null
                    val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    
                    newAnimeSearchResponse(title, fixUrl(href)) {
                        this.posterUrl = fixUrl(poster)
                        addDubStatus(getDubStatus(title))
                    }
                }
                items.add(HomePageList(name, home))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        if (items.size <= 0) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    data class SearchObject(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("last_id") val lastId: String,
        @JsonProperty("slug") val slug: String
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "https://www3.animeflv.net/api/animes/search",
            data = mapOf(Pair("value", query))
        ).text
        val json = parseJson<List<SearchObject>>(response)
        return json.map { searchr ->
            val title = searchr.title
            val href = "$mainUrl/anime/${searchr.slug}"
            val image = "$mainUrl/uploads/animes/covers/${searchr.id}.jpg"
            newAnimeSearchResponse(title, href) {
                this.posterUrl = fixUrl(image)
                if (title.contains("Latino") || title.contains("Castellano")) {
                    addDubStatus(DubStatus.Dubbed)
                } else {
                    addDubStatus(DubStatus.Subbed)
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val episodes = ArrayList<Episode>()
        val title = doc.selectFirst("h1.Title")?.text() ?: throw ErrorLoadingException("Title not found")
        val poster = doc.selectFirst("div.AnimeCover div.Image figure img")?.attr("src") ?: ""
        val description = doc.selectFirst("div.Description p")?.text()
        val type = doc.selectFirst("span.Type")?.text() ?: ""
        val status = when (doc.selectFirst("p.AnmStts span")?.text()) {
            "En emision" -> ShowStatus.Ongoing
            "Finalizado" -> ShowStatus.Completed
            else -> null
        }
        val genre = doc.select("nav.Nvgnrs a").map { it.text().trim() }

        // Extract episodes from script
        doc.select("script").forEach { script ->
            if (script.data().contains("var episodes = [")) {
                val data = script.data().substringAfter("var episodes = [").substringBefore("];")
                data.split("],").forEach { episodeData ->
                    try {
                        val epNum = episodeData.removePrefix("[").substringBefore(",")
                        val animeid = doc.selectFirst("div.Strs.RateIt")?.attr("data-id")
                        val epthumb = "https://cdn.animeflv.net/screenshots/$animeid/$epNum/th_3.jpg"
                        val link = url.replace("/anime/", "/ver/") + "-$epNum"
                        episodes.add(
                            newEpisode(link) {
                                this.posterUrl = epthumb
                                this.episode = epNum.toIntOrNull()
                            }
                        )
                    } catch (e: Exception) {
                        // Skip malformed episode data
                    }
                }
            }
        }

        return newAnimeLoadResponse(title, url, getType(type)) {
            posterUrl = fixUrl(poster)
            addEpisodes(DubStatus.Subbed, episodes.reversed())
            showStatus = status
            plot = description
            tags = genre
        }
    }

    private fun extractVideoUrls(text: String): List<String> {
        val urls = mutableListOf<String>()
        
        // Define regex patterns for different video URL formats
        val patterns = listOf(
            Regex("\"(https?://[^\"]*\\.(mp4|m3u8|mkv)[^\"]*)\"|'(https?://[^']*\\.(mp4|m3u8|mkv)[^']*)'"),
            Regex("file:\\s*[\"'](https?://[^\"']+)[\"']"),
            Regex("src:\\s*[\"'](https?://[^\"']+)[\"']"),
            Regex("url:\\s*[\"'](https?://[^\"']+)[\"']"),
            Regex("(https?://(?:www\\.)?(?:fembed|embedsb|streamtape|doodstream|uqload|mixdrop|upstream|voe)\\.(?:com|net|org|io|to|me)/[^\\s\"'<>]+)")
        )
        
        // Extract URLs using each pattern
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
    ): Boolean = coroutineScope {
        // Process scripts that contain video information
        val scriptElements = app.get(data).document.select("script")
        
        scriptElements.map { script ->
            async {
                val scriptData = script.data()
                if (scriptData.contains("var videos = {") || 
                    scriptData.contains("var anime_id =") || 
                    scriptData.contains("server")) {
                    
                    val videos = scriptData.replace("\\/", "/")
                    val extractedUrls = extractVideoUrls(videos).map { url ->
                        url.replace("https://embedsb.com/e/", "https://watchsb.com/e/")
                           .replace("https://ok.ru", "http://ok.ru")
                    }
                    
                    // Load each extracted URL
                    extractedUrls.map { url ->
                        async {
                            try {
                                loadExtractor(url, data, subtitleCallback, callback)
                            } catch (e: Exception) {
                                // Continue with other URLs if one fails
                            }
                        }
                    }.awaitAll()
                }
            }
        }.awaitAll()
        
        true
    }
}
