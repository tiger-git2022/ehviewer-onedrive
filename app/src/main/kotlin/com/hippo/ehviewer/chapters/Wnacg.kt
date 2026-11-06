package com.hippo.ehviewer.chapters

import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.client.executeSafely
import com.hippo.ehviewer.util.bodyAsUtf8Text
import com.hippo.ehviewer.util.ensureSuccess
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode

/**
 * Fork: 紳士漫畫 (wnacg), where many Chinese translations gone from E-H still are, with a thumbnail of each page: a
 * favorite gone from E-H is compared through its album there, see [ChapterCompare].
 */
object Wnacg {
    const val BASE = "https://www.wnacg.com"

    /** The site (or its thumbnails) asks for its check: passed once in a page of the app, see [WnacgCheck]. */
    class BlockedException : Exception("wnacg asks for a check")

    fun isBlocked(code: Int) = code == HttpStatusCode.Forbidden.value || code == HttpStatusCode.ServiceUnavailable.value

    private fun HttpResponse.ensureNotBlocked() {
        if (isBlocked(status.value)) throw BlockedException()
    }

    /** An album of a search: its id, its title and its page count (0 when not told). */
    data class Album(val aid: Long, val title: String, val pages: Int)

    suspend fun search(query: String): List<Album> = ktorClient.prepareGet("$BASE/search/index.php") {
        parameter("q", query)
        parameter("m", "")
        parameter("syn", "yes")
        parameter("f", "_all")
        parameter("s", "create_time_DESC")
    }.executeSafely {
        it.ensureNotBlocked()
        it.status.ensureSuccess()
        parseSearch(it.bodyAsUtf8Text())
    }

    /** Page [index] (from 1) of the thumbnails of album [aid]: (page from 1, thumbnail URL). */
    suspend fun thumbnails(aid: Long, index: Int): List<Pair<Int, String>> {
        val url = if (index <= 1) "$BASE/photos-index-aid-$aid.html" else "$BASE/photos-index-page-$index-aid-$aid.html"
        return ktorClient.prepareGet(url).executeSafely {
            it.ensureNotBlocked()
            it.status.ensureSuccess()
            parseThumbnails(it.bodyAsUtf8Text())
        }
    }

    private val ITEM = Regex("""photos-index-aid-(\d+)\.html"\s+title="([^"]*)"""")
    private val PAGES = Regex("""(\d+)\s*張圖片""")
    private val THUMB = Regex("""<img\s+src="([^"]+)"\s+alt="[^"]*_(\d+)"""")

    fun parseSearch(html: String): List<Album> = html.split("gallary_item").drop(1).mapNotNull { item ->
        val match = ITEM.find(item) ?: return@mapNotNull null
        val pages = PAGES.find(item)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        Album(match.groupValues[1].toLong(), unescape(match.groupValues[2]), pages)
    }.distinctBy { it.aid }

    /** The thumbnails of a page of an album; one also in a comment of the page is taken once. */
    fun parseThumbnails(html: String): List<Pair<Int, String>> = THUMB.findAll(html).map { match ->
        val url = match.groupValues[1].let { if (it.startsWith("//")) "https:$it" else it }
        match.groupValues[2].toInt() to url
    }.filter { it.second.startsWith("http") }.distinctBy { it.first }.toList()

    private fun unescape(text: String) = text.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&amp;", "&")
}
