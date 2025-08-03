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
    override var mainUrl = "https://cuevana.pro"
    override var name = "Cuevana"
    override var lang = "es"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val items = ArrayList<HomePageList>()
        val urls = listOf(
            Pair(mainUrl, "Recientemente actualizadas"),
            Pair("$mainUrl/estrenos/", "Estrenos"),
        )
        items.add(
            HomePageList(
                "Series",
                app.get("$mainUrl/serie", timeout = 120).document.select("section.home-series li")
                    .map {
                        val title = it.selectFirst("h2.Title")!!.text()
                        val poster = it.selectFirst("img.lazy")!!.attr("data-src")
                        val url = it.selectFirst("a")!!.attr("href")
                        TvSeriesSearchResponse(
                            title,
                            url,
                            this.name,
                            TvType.TvSeries,
                            poster,
                            null,
                            null,
                        )
                    })
        )
        for ((url, name) in urls) {
            try {
                val soup = app.get(url).document
                val home = soup.select("section li.xxx.TPostMv").map {
                    val title = it.selectFirst("h2.Title")!!.text()
                    val link = it.selectFirst("a")!!.attr("href")
                    TvSeriesSearchResponse(
                        title,
                        link,
                        this.name,
                        if (link.contains("/pelicula/")) TvType.Movie else TvType.TvSeries,
                        it.selectFirst("img.lazy")!!.attr("data-src"),
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
        val url = "$mainUrl/?s=${query}"
        val document = app.get(url).document

        return document.select("li.xxx.TPostMv").map {
            val title = it.selectFirst("h2.Title")!!.text()
            val href = it.selectFirst("a")!!.attr("href")
            val image = it.selectFirst("img.lazy")!!.attr("data-src")
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
        val title = soup.selectFirst("h1.Title")!!.text()
        val description = soup.selectFirst(".Description p")?.text()?.trim()
        val poster: String? = soup.selectFirst(".movtv-info div.Image img")!!.attr("data-src")
        val year1 = soup.selectFirst("footer p.meta").toString()
        val yearRegex = Regex("<span>(\\d+)</span>")
        val yearf =
            yearRegex.find(year1)?.destructured?.component1()?.replace(Regex("<span>|</span>"), "")
        val year = if (yearf.isNullOrBlank()) null else yearf.toIntOrNull()
        val episodes = soup.select(".all-episodes li.TPostMv article").map { li ->
            val href = li.select("a").attr("href")
            val epThumb =
                li.selectFirst("div.Image img")?.attr("data-src") ?: li.selectFirst("img.lazy")!!
                    .attr("data-srcc")
            val seasonid = li.selectFirst("span.Year")!!.text().let { str ->
                str.split("x").mapNotNull { subStr -> subStr.toIntOrNull() }
            }
            val isValid = seasonid.size == 2
            val episode = if (isValid) seasonid.getOrNull(1) else null
            val season = if (isValid) seasonid.getOrNull(0) else null
            Episode(
                href,
                null,
                season,
                episode,
                fixUrl(epThumb)
            )
        }
        val tags = soup.select("ul.InfoList li.AAIco-adjust:contains(Genero) a").map { it.text() }
        val tvType = if (episodes.isEmpty()) TvType.Movie else TvType.TvSeries
        val recelement =
            if (tvType == TvType.TvSeries) "main section div.series_listado.series div.xxx"
            else "main section ul.MovieList li"
        val recommendations =
            soup.select(recelement).mapNotNull { element ->
                val recTitle = element.select("h2.Title").text() ?: return@mapNotNull null
                val image = element.select("figure img")?.attr("data-src")
                val recUrl = fixUrl(element.select("a").attr("href"))
                MovieSearchResponse(
                    recTitle,
                    recUrl,
                    this.name,
                    TvType.Movie,
                    image,
                    year = null
                )
            }

        return when (tvType) {
            TvType.TvSeries -> {
                TvSeriesLoadResponse(
                    title,
                    url,
                    this.name,
                    tvType,
                    episodes,
                    poster,
                    year,
                    description,
                    tags = tags,
                    recommendations = recommendations
                )
            }
            TvType.Movie -> {
                MovieLoadResponse(
                    title,
                    url,
                    this.name,
                    tvType,
                    url,
                    poster,
                    year,
                    description,
                    tags = tags,
                    recommendations = recommendations
                )
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
    ): Boolean {
        app.get(data).document.select("div.TPlayer.embed_div iframe").apmap {
            val iframe = fixUrl(it.attr("data-src"))
            if (iframe.contains("api.cuevana3.me/fembed/") || iframe.contains("cuevana.pro/fembed/")) {
                val femregex =
                    Regex("(https.\\/\\/(api\\.cuevana3\\.me|cuevana\\.pro)\\/fembed\\/\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
                femregex.findAll(iframe).map { femreg ->
                    femreg.value
                }.toList().apmap { fem ->
                    val key = fem.replace(Regex("https://(api\\.cuevana3\\.me|cuevana\\.pro)/fembed/\\?h="), "")
                    val apiUrl = if (fem.contains("cuevana.pro")) "https://cuevana.pro/fembed/api.php" else "https://api.cuevana3.me/fembed/api.php"
                    val hostHeader = if (fem.contains("cuevana.pro")) "cuevana.pro" else "api.cuevana3.me"
                    val originHeader = if (fem.contains("cuevana.pro")) "https://cuevana.pro" else "https://api.cuevana3.me"
                    
                    val url = app.post(
                        apiUrl,
                        allowRedirects = false,
                        headers = mapOf(
                            "Host" to hostHeader,
                            "User-Agent" to USER_AGENT,
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                            "Accept-Language" to "en-US,en;q=0.5",
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to originHeader,
                            "DNT" to "1",
                            "Connection" to "keep-alive",
                            "Sec-Fetch-Dest" to "empty",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Site" to "same-origin",
                        ),
                        data = mapOf(Pair("h", key))
                    ).text
                    val json = parseJson<Femcuevana>(url)
                    val link = json.url
                    if (link.contains("fembed")) {
                        loadExtractor(link, data, subtitleCallback, callback)
                    }
                }
            }
            if (iframe.contains("tomatomatela")) {
                val tomatoRegex =
                    Regex("(\\/\\/apialfa.tomatomatela.com\\/ir\\/player.php\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
                tomatoRegex.findAll(iframe).map { tomreg ->
                    tomreg.value
                }.toList().apmap { tom ->
                    val tomkey = tom.replace("//apialfa.tomatomatela.com/ir/player.php?h=", "")
                    app.post(
                        "https://apialfa.tomatomatela.com/ir/rd.php", allowRedirects = false,
                        headers = mapOf(
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
                        ),
                        data = mapOf(Pair("url", tomkey))
                    ).okhttpResponse.headers.values("location").apmap { loc ->
                        if (loc.contains("goto_ddh.php")) {
                            val gotoregex =
                                Regex("(\\/\\/(api.cuevana3.me|cuevana.pro)\\/ir\\/goto_ddh.php\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
                            gotoregex.findAll(loc).map { goreg ->
                                goreg.value.replace(Regex("//(?:api\\.cuevana3\\.me|cuevana\\.pro)/ir/goto_ddh\\.php\\?h="), "")
                            }.toList().apmap { gotolink ->
                                val redirectUrl = if (loc.contains("cuevana.pro")) "https://cuevana.pro/ir/redirect_ddh.php" else "https://api.cuevana3.me/ir/redirect_ddh.php"
                                val hostHeader = if (loc.contains("cuevana.pro")) "cuevana.pro" else "api.cuevana3.me"
                                
                                app.post(
                                    redirectUrl,
                                    allowRedirects = false,
                                    headers = mapOf(
                                        "Host" to hostHeader,
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
                                    ),
                                    data = mapOf(Pair("url", gotolink))
                                ).okhttpResponse.headers.values("location").apmap { golink ->
                                    loadExtractor(golink, data, subtitleCallback, callback)
                                }
                            }
                        }
                        if (loc.contains("index.php?h=")) {
                            val indexRegex =
                                Regex("(\\/\\/(api.cuevana3.me|cuevana.pro)\\/sc\\/index.php\\?h=[a-zA-Z0-9]{0,8}[a-zA-Z0-9_-]+)")
                            indexRegex.findAll(loc).map { indreg ->
                                indreg.value.replace(Regex("//(?:api\\.cuevana3\\.me|cuevana\\.pro)/sc/index\\.php\\?h="), "")
                            }.toList().apmap { inlink ->
                                val scUrl = if (loc.contains("cuevana.pro")) "https://cuevana.pro/sc/r.php" else "https://api.cuevana3.me/sc/r.php"
                                val hostHeader = if (loc.contains("cuevana.pro")) "cuevana.pro" else "api.cuevana3.me"
                                
                                app.post(
                                    scUrl, allowRedirects = false,
                                    headers = mapOf(
                                        "Host" to hostHeader,
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
                                    ),
                                    data = mapOf(Pair("h", inlink))
                                ).okhttpResponse.headers.values("location").apmap { link ->
                                    loadExtractor(link, data, subtitleCallback, callback)
                                }
                            }
                        }
                    }
                }
            }
        }
        return true
    }
}
