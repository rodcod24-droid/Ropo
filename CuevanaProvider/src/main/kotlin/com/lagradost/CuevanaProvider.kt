package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class CuevanaProvider : MainAPI() {
    override var mainUrl = "https://cuevana3.vip"
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
        val document = app.get(mainUrl).document
        
        try {
            // Try multiple selectors for movies/series
            val movieElements = document.select("section.home-movies .MovieList .TPostMv, .MovieList article, article.TPost.C")
            if (movieElements.isNotEmpty()) {
                val movies = movieElements.mapNotNull { element ->
                    val title = element.selectFirst("h2.Title, .title, h3")?.text()?.trim() ?: return@mapNotNull null
                    val link = fixUrl(element.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                    val image = getImageUrl(element.selectFirst("img"))
                    
                    newMovieSearchResponse(title, link) {
                        this.posterUrl = image
                    }
                }
                if (movies.isNotEmpty()) {
                    items.add(HomePageList("Películas", movies))
                }
            }
            
            val seriesElements = document.select("section.home-series .MovieList .TPostMv, section.home-series li")
            if (seriesElements.isNotEmpty()) {
                val series = seriesElements.mapNotNull { element ->
                    val title = element.selectFirst("h2.Title, .title, h3")?.text()?.trim() ?: return@mapNotNull null
                    val link = fixUrl(element.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                    val image = getImageUrl(element.selectFirst("img"))
                    
                    newTvSeriesSearchResponse(title, link) {
                        this.posterUrl = image
                    }
                }
                if (series.isNotEmpty()) {
                    items.add(HomePageList("Series", series))
                }
            }
            
        } catch (e: Exception) {
            logError(e)
        }

        if (items.isEmpty()) throw ErrorLoadingException()
        return HomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document

        return document.select(".MovieList .TPostMv, article.TPost").mapNotNull { element ->
            val title = element.selectFirst("h2.Title, .title")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(element.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val image = getImageUrl(element.selectFirst("img"))
            
            val isSerie = href.contains("/serie/") || href.contains("/series/")
            
            if (isSerie) {
                newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = image
                }
            } else {
                newMovieSearchResponse(title, href) {
                    this.posterUrl = image
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val soup = app.get(url).document

        val title = soup.selectFirst("h1.Title, h1")?.text()?.trim() ?: return null
        val description = soup.selectFirst(".Description p, .overview")?.text()?.trim()
        val poster = getImageUrl(soup.selectFirst(".movtv-info .Image img, .poster img"))
        val year = soup.selectFirst(".Date, .year")?.text()?.let { 
            Regex("(\\d{4})").find(it)?.value?.toIntOrNull() 
        }

        val episodes = soup.select(".all-episodes .TPostMv, .episodios li").mapNotNull { li ->
            val epLink = fixUrl(li.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val epThumb = getImageUrl(li.selectFirst("img"))
            
            val seasonEpisode = li.selectFirst(".Year")?.text()?.split("x")?.mapNotNull { it.trim().toIntOrNull() }
            val season = seasonEpisode?.getOrNull(0) ?: 1
            val episode = seasonEpisode?.getOrNull(1) ?: 1
            
            newEpisode(epLink) {
                this.season = season
                this.episode = episode
                this.posterUrl = epThumb
            }
        }

        val tvType = if (episodes.isNotEmpty()) TvType.TvSeries else TvType.Movie
        val tags = soup.select(".genres a, .genre").map { it.text() }

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

    data class FemcuevanaResponse(
        @JsonProperty("url") val url: String,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        
        val doc = app.get(data).document
        
        // Method 1: Look for player buttons
        doc.select(".TPlayerTb .Button, .aa-cn").forEach { button ->
            val dataPost = button.attr("data-tplayernv")
            if (dataPost.isNotEmpty()) {
                try {
                    val postResponse = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        headers = mapOf(
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to data
                        ),
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to dataPost,
                            "nume" to "1",
                            "type" to "movie"
                        )
                    )
                    
                    val embedUrl = postResponse.document.selectFirst("iframe")?.attr("src")
                    if (!embedUrl.isNullOrEmpty()) {
                        val fullUrl = fixUrl(embedUrl)
                        loadExtractor(fullUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }
        
        // Method 2: Direct iframes
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.startsWith("about:")) {
                try {
                    val fullUrl = fixUrl(src)
                    
                    // Handle fembed specifically
                    if (fullUrl.contains("fembed") && fullUrl.contains("?h=")) {
                        val key = fullUrl.substringAfter("?h=")
                        val baseUrl = fullUrl.substringBefore("/fembed")
                        
                        try {
                            val response = app.post(
                                "$baseUrl/fembed/api.php",
                                headers = mapOf(
                                    "Content-Type" to "application/x-www-form-urlencoded",
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "Referer" to data
                                ),
                                data = mapOf("h" to key)
                            )
                            
                            val json = parseJson<FemcuevanaResponse>(response.text)
                            if (json.url.startsWith("http")) {
                                loadExtractor(json.url, data, subtitleCallback, callback)
                                foundLinks = true
                            }
                        } catch (e: Exception) {
                            logError(e)
                        }
                    } else {
                        loadExtractor(fullUrl, data, subtitleCallback, callback)
                        foundLinks = true
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }
        
        return foundLinks
    }
    
    private fun getImageUrl(img: org.jsoup.nodes.Element?): String? {
        return img?.let {
            val src = it.attr("data-src").takeIf { s -> s.isNotEmpty() }
                ?: it.attr("data-lazy-src").takeIf { s -> s.isNotEmpty() }
                ?: it.attr("src").takeIf { s -> s.isNotEmpty() }
            src?.let { fixUrl(it) }
        }
    }
    
    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }
}
