package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element

class PelisplusHDProvider : MainAPI() {
    override var mainUrl = "https://www.pelisplushd.ms"
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
        try {
            val items = ArrayList<HomePageList>()
            val document = app.get(mainUrl, timeout = 120).document
            
            // Try multiple approaches to find content sections
            val sectionMaps = listOf(
                mapOf(
                    "Películas" to "#default-tab-1",
                    "Series" to "#default-tab-2",
                    "Anime" to "#default-tab-3",
                    "Doramas" to "#default-tab-4",
                ),
                mapOf(
                    "Películas" to ".movies-section",
                    "Series" to ".series-section",
                    "Anime" to ".anime-section",
                ),
                mapOf(
                    "Destacadas" to ".featured-content",
                    "Recientes" to ".recent-content",
                )
            )
            
            for (sectionMap in sectionMaps) {
                var foundContent = false
                
                sectionMap.forEach { (sectionName, selector) ->
                    try {
                        val sectionContent = document.select(selector)
                        if (sectionContent.isNotEmpty()) {
                            // Try multiple selectors for content items within sections
                            val itemSelectors = listOf(
                                "a.Posters-link",
                                ".movie-item a",
                                ".content-item a",
                                ".poster-link",
                                "article a"
                            )
                            
                            for (itemSelector in itemSelectors) {
                                val elements = sectionContent.select(itemSelector)
                                if (elements.isNotEmpty()) {
                                    val sectionItems = elements.mapNotNull { element ->
                                        element.toSearchResult()
                                    }
                                    if (sectionItems.isNotEmpty()) {
                                        items.add(HomePageList(sectionName, sectionItems))
                                        foundContent = true
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
                
                if (foundContent) break
            }
            
            // Fallback: try to get any content from the main page
            if (items.isEmpty()) {
                val fallbackSelectors = listOf(
                    "a.Posters-link",
                    ".movie-item",
                    ".content-item",
                    "article.TPost"
                )
                
                for (selector in fallbackSelectors) {
                    val elements = document.select(selector)
                    if (elements.isNotEmpty()) {
                        val fallbackItems = elements.mapNotNull { element ->
                            try {
                                element.toSearchResult()
                            } catch (e: Exception) {
                                logError(e)
                                null
                            }
                        }
                        if (fallbackItems.isNotEmpty()) {
                            items.add(HomePageList("Contenido", fallbackItems))
                            break
                        }
                    }
                }
            }
            
            return newHomePageResponse(items)
        } catch (e: Exception) {
            logError(e)
            return newHomePageResponse(emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        return try {
            // Try multiple selectors for title
            val title = this.selectFirst(".listing-content p, .title, h2, h3")?.text()?.trim()
                ?: return null
            
            // Try multiple selectors for link
            val href = this.selectFirst("a")?.attr("href") ?: this.attr("href")
            if (href.isEmpty()) return null
            
            val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
            
            // Try multiple selectors for poster
            val posterUrl = this.selectFirst(".Posters-img, img")?.run {
                attr("data-src").takeIf { it.isNotEmpty() }
                    ?: attr("data-lazy-src").takeIf { it.isNotEmpty() }
                    ?: attr("src").takeIf { it.isNotEmpty() }
            }
            
            val isMovie = fullHref.contains("/pelicula/") || fullHref.contains("/movie/")
            
            if (isMovie) {
                newMovieSearchResponse(title, fullHref) {
                    this.posterUrl = posterUrl
                }
            } else {
                newTvSeriesSearchResponse(title, fullHref) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        try {
            val searchUrls = listOf(
                "$mainUrl/search?s=$query",
                "$mainUrl/?s=$query"
            )
            
            for (url in searchUrls) {
                try {
                    val document = app.get(url, timeout = 120).document
                    
                    val contentSelectors = listOf(
                        "a.Posters-link",
                        ".search-result",
                        ".movie-item",
                        ".content-item",
                        "article.TPost"
                    )
                    
                    for (selector in contentSelectors) {
                        val results = document.select(selector).mapNotNull { element ->
                            try {
                                val title = element.selectFirst(".listing-content p, .title, h2, h3")?.text()?.trim()
                                    ?: return@mapNotNull null
                                val href = element.selectFirst("a")?.attr("href") ?: element.attr("href")
                                if (href.isEmpty()) return@mapNotNull null
                                
                                val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                                
                                val image = element.selectFirst(".Posters-img, img")?.run {
                                    attr("data-src").takeIf { it.isNotEmpty() }
                                        ?: attr("src").takeIf { it.isNotEmpty() }
                                }
                                
                                val isMovie = fullHref.contains("/pelicula/") || fullHref.contains("/movie/")
                                
                                if (isMovie) {
                                    newMovieSearchResponse(title, fullHref) {
                                        this.posterUrl = image
                                    }
                                } else {
                                    newTvSeriesSearchResponse(title, fullHref) {
                                        this.posterUrl = image
                                    }
                                }
                            } catch (e: Exception) {
                                logError(e)
                                null
                            }
                        }
                        
                        if (results.isNotEmpty()) return results
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
            
            return emptyList()
        } catch (e: Exception) {
            logError(e)
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val soup = app.get(url, timeout = 120).document

            // Try multiple selectors for title
            val title = soup.selectFirst(".m-b-5, h1, .title, .movie-title")?.text()?.trim()
                ?: return null
            
            // Try multiple selectors for description
            val description = soup.selectFirst("div.text-large, .description, .overview, .plot")?.text()?.trim()
            
            // Try multiple selectors for poster
            val poster = soup.selectFirst(".img-fluid, .poster img, img")?.run {
                attr("data-src").takeIf { it.isNotEmpty() }
                    ?: attr("src").takeIf { it.isNotEmpty() }
            }
            
            // Extract episodes for series
            val episodeSelectors = listOf(
                "div.tab-pane .btn",
                ".episodes-list .episode",
                ".season-episodes .episode",
                ".episode-list li"
            )
            
            var episodes: List<Episode> = emptyList()
            
            for (selector in episodeSelectors) {
                val episodeElements = soup.select(selector)
                if (episodeElements.isNotEmpty()) {
                    episodes = episodeElements.mapNotNull { li ->
                        try {
                            val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                            val fullHref = if (href.startsWith("http")) href else "$mainUrl$href"
                            
                            val name = li.selectFirst(".btn-primary.btn-block, .episode-title")?.text()?.trim()
                            
                            // Extract season and episode from URL or text
                            val seasonEpisodeText = href.replace("/capitulo/", "-")
                                .replace(Regex("$mainUrl/.*/.*/temporada/"), "")
                            
                            val seasonEpisode = seasonEpisodeText.split("-", "/").mapNotNull { subStr -> 
                                subStr.replace(Regex("[^\\d]"), "").toIntOrNull() 
                            }
                            
                            val isValid = seasonEpisode.size >= 2
                            val episode = if (isValid) seasonEpisode.getOrNull(1) else null
                            val season = if (isValid) seasonEpisode.getOrNull(0) else null
                            
                            newEpisode(fullHref) {
                                this.name = name
                                this.season = season
                                this.episode = episode
                            }
                        } catch (e: Exception) {
                            logError(e)
                            null
                        }
                    }
                    if (episodes.isNotEmpty()) break
                }
            }

            // Extract year
            val yearText = soup.selectFirst(".p-r-15 .text-semibold, .year, .release-date")?.text() ?: ""
            val year = Regex("(\\d{4})").find(yearText)?.value?.toIntOrNull()
            
            val tvType = if (url.contains("/pelicula/") || url.contains("/movie/")) TvType.Movie else TvType.TvSeries
            
            // Extract tags/genres
            val tags = soup.select(".p-h-15.text-center a span.font-size-18.text-info.text-semibold, .genres a, .genre a")
                .map { it.text().trim().replace(", ", "") }
                .filter { it.isNotEmpty() }

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

    private fun extractVideoUrls(text: String): List<String> {
        val urls = mutableListOf<String>()
        
        // Extract various video URL patterns
        val patterns = listOf(
            Regex("\"(https?://[^\"]*\\.(mp4|m3u8|mkv)[^\"]*)\"|'(https?://[^']*\\.(mp4|m3u8|mkv)[^']*)'"),
            Regex("file:\\s*[\"'](https?://[^\"']+)[\"']"),
            Regex("src:\\s*[\"'](https?://[^\"']+)[\"']"),
            Regex("url:\\s*[\"'](https?://[^\"']+)[\"']"),
            Regex("(https?://(?:www\\.)?(?:fembed|embedsb|streamtape|doodstream|uqload|mixdrop|upstream|voe|streamwish|filemoon)\\.(?:com|net|org|io|to|me|co)/[^\\s\"'<>]+)"),
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
    ): Boolean = coroutineScope {
        try {
            val doc = app.get(data, timeout = 120).document
            
            // Try multiple selectors for player scripts and iframes
            val playerSelectors = listOf(
                "div.player > script",
                ".video-player script",
                "script[src*='player']",
                "script:containsData(http)",
                "iframe[src*='embed']",
                ".player iframe",
                "iframe"
            )
            
            val tasks = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
            
            // First, try to extract from scripts
            for (selector in playerSelectors.filter { it.contains("script") }) {
                val scripts = doc.select(selector)
                if (scripts.isNotEmpty()) {
                    scripts.forEach { script ->
                        tasks.add(async {
                            try {
                                val scriptData = script.data()
                                if (scriptData.isNotEmpty()) {
                                    extractVideoUrls(scriptData).forEach { url ->
                                        val processedUrl = if (url.contains("$mainUrl/fembed.php?url=")) {
                                            url.replace("$mainUrl/fembed.php?url=", "https://www.fembed.com/v/")
                                        } else {
                                            url
                                        }
                                        loadExtractor(processedUrl, data, subtitleCallback, callback)
                                    }
                                }
                            } catch (e: Exception) {
                                logError(e)
                            }
                        })
                    }
                    break
                }
            }
            
            // Then, try iframes
            for (selector in playerSelectors.filter { it.contains("iframe") }) {
                val iframes = doc.select(selector)
                if (iframes.isNotEmpty()) {
                    iframes.forEach { iframe ->
                        tasks.add(async {
                            try {
                                val iframeUrl = iframe.attr("data-src").takeIf { it.isNotEmpty() }
                                    ?: iframe.attr("src").takeIf { it.isNotEmpty() }
                                    ?: return@async
                                
                                val fullUrl = if (iframeUrl.startsWith("http")) iframeUrl else "$mainUrl$iframeUrl"
                                loadExtractor(fullUrl, data, subtitleCallback, callback)
                            } catch (e: Exception) {
                                logError(e)
                            }
                        })
                    }
                    break
                }
            }
            
            tasks.awaitAll()
            true
        } catch (e: Exception) {
            logError(e)
            false
        }
    }
}
