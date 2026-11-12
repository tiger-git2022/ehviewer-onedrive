package com.hippo.ehviewer.chapters

import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.client.executeSafely
import com.hippo.ehviewer.util.bodyAsUtf8Text
import com.hippo.ehviewer.util.ensureSuccess
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Fork: 紳士漫畫 (wnacg), where many Chinese translations gone from E-H still are, with a thumbnail of each page: a
 * favorite gone from E-H is compared through its album there, see [ChapterCompare]. Its albums also show among the
 * results of a search, open with their details and read or download from there, see wnacg/.
 */
object Wnacg {
    const val BASE = "https://www.wnacg.com"

    /** The site (or its thumbnails) asks for its check: passed once in a page of the app, see [WnacgCheck]. */
    class BlockedException : Exception("wnacg asks for a check")

    fun isBlocked(code: Int) = code == HttpStatusCode.Forbidden.value || code == HttpStatusCode.ServiceUnavailable.value

    private fun HttpResponse.ensureNotBlocked() {
        if (isBlocked(status.value)) throw BlockedException()
    }

    /**
     * An album of a search: its id, its title and its page count (0 when not told); its cover, when it was put up (ms,
     * 0 when not told) and its category (the N of "cate-N", 0 when not told).
     */
    data class Album(
        val aid: Long,
        val title: String,
        val pages: Int,
        val cover: String? = null,
        val created: Long = 0,
        val category: Int = 0,
    )

    /** A page of a search: its albums, newest first, and whether a page comes after it. */
    data class SearchPage(val albums: List<Album>, val hasNext: Boolean)

    suspend fun search(query: String): List<Album> = searchPage(query, 1).albums

    /** Page [page] (from 1) of the albums whose title or tags have [query], newest first. */
    suspend fun searchPage(query: String, page: Int): SearchPage = ktorClient.prepareGet("$BASE/search/index.php") {
        parameter("q", query)
        parameter("m", "")
        parameter("syn", "yes")
        parameter("f", "_all")
        parameter("s", "create_time_DESC")
        if (page > 1) parameter("p", page)
    }.executeSafely {
        it.ensureNotBlocked()
        it.status.ensureSuccess()
        val html = it.bodyAsUtf8Text()
        val albums = parseSearch(html)
        SearchPage(albums, albums.isNotEmpty() && hasPage(html, page + 1))
    }

    /** The first page of album [aid]: its details, its first thumbnails and its first comments. */
    suspend fun album(aid: Long): AlbumPage = ktorClient.prepareGet("$BASE/photos-index-aid-$aid.html").executeSafely {
        it.ensureNotBlocked()
        it.status.ensureSuccess()
        parseAlbum(aid, it.bodyAsUtf8Text())
    }

    /** All the comments of album [aid]. */
    suspend fun comments(aid: Long): List<Comment> = ktorClient.prepareGet("$BASE/comment-index-aid-$aid.html").executeSafely {
        it.ensureNotBlocked()
        it.status.ensureSuccess()
        parseComments(it.bodyAsUtf8Text())
    }

    /** The images of album [aid], in order: what its reader shows. */
    suspend fun images(aid: Long): List<String> = ktorClient.prepareGet("$BASE/photos-gallery-aid-$aid.html").executeSafely {
        it.ensureNotBlocked()
        it.status.ensureSuccess()
        parseImages(it.bodyAsUtf8Text())
    }.also { check(it.isNotEmpty()) { "wnacg listed no image of $aid" } }

    /** The page of an album that shows on [url], for its Referer. */
    fun albumUrl(aid: Long) = "$BASE/photos-index-aid-$aid.html"

    /**
     * Page [index] (from 1) of the thumbnails of album [aid]: (page from 1, thumbnail URL). [perPage] is how many the
     * first page has, as all but the last.
     */
    suspend fun thumbnails(aid: Long, index: Int, perPage: Int): List<Pair<Int, String>> {
        val url = if (index <= 1) "$BASE/photos-index-aid-$aid.html" else "$BASE/photos-index-page-$index-aid-$aid.html"
        return ktorClient.prepareGet(url).executeSafely {
            it.ensureNotBlocked()
            it.status.ensureSuccess()
            parseThumbnails(it.bodyAsUtf8Text(), (index - 1) * perPage + 1)
        }
    }

    /** The details of an album, from its first page. [created] and [cover] are null or 0 when not told. */
    data class AlbumPage(
        val aid: Long,
        val title: String,
        val cover: String?,
        val category: Int,
        val pages: Int,
        val tags: List<String>,
        val description: String,
        val uploader: String?,
        val created: Long,
        val thumbnails: List<Pair<Int, String>>,
        val comments: List<Comment>,
        val commentCount: Int,
    )

    /** A comment: who wrote it, when (ms), what, and the votes for it. */
    data class Comment(val id: Long, val user: String, val time: Long, val text: String, val score: Int)

    /** Thumbnails on a page of an album. */
    const val THUMBNAILS_PER_PAGE = 12

    private val ITEM = Regex("""photos-index-aid-(\d+)\.html"\s+title="([^"]*)"""")
    private val PAGES = Regex("""(\d+)\s*張圖片""")
    // The first image of an item of the thumbnails, the comments of the page left out
    private val THUMB = Regex("""gallary_item[\s\S]*?<img\s[^>]*?src="([^"]+)"""")
    private val HTML_COMMENT = Regex("""<!--[\s\S]*?-->""")
    private val COVER = Regex("""<img[^>]*\ssrc="([^"]+)"""")
    private val CREATED = Regex("""創建於\s*(\d{4}-\d{2}-\d{2})(?:\s+(\d{2}:\d{2}(?::\d{2})?))?""")
    private val UPLOADED = Regex("""上傳於\s*(\d{4}-\d{2}-\d{2})""")
    private val CATEGORY = Regex("""cate-(\d+)""")
    private val H2 = Regex("""<h2>([\s\S]*?)</h2>""")
    private val PAGES_LABEL = Regex("""頁數：\s*(\d+)""")
    private val CATEGORY_LABEL = Regex("""分類：\s*([^<]+)""")
    private val TAG = Regex("""<a[^>]*class="tagshow"[^>]*>([^<]*)</a>""")
    private val DESCRIPTION = Regex("""簡介：([\s\S]*?)</p>""")
    private val UPLOADER = Regex("""class="asTBcell uwuinfo"[\s\S]*?<p>([^<]*)</p>""")
    private val PREVIEW_COMMENT = Regex(
        """class="pl_pv_n">([\s\S]*?)</div>\s*<div class="pl_pv_c">([\s\S]*?)</div>\s*<div class="pl_pv_m">\s*([\d-]+)[\s\S]*?</svg>\s*(-?\d+)""",
    )
    private val COMMENT_COUNT = Regex("""評論\s*\((\d+)\)""")
    private val COMMENT = Regex("""class="plItem" data-id="(\d+)"[\s\S]*?class="plName">([\s\S]*?)</span>\s*<span class="plTime">([^<]*)</span>[\s\S]*?class="plText">([\s\S]*?)</div>""")
    private val COMMENT_UP = Regex("""class="plUp"[^>]*>[\s\S]*?<i>(-?\d+)</i>""")
    private val COMMENT_DOWN = Regex("""class="plDown"[^>]*>[\s\S]*?<i>(-?\d+)</i>""")
    // An image and what asks for it after "?" ("?verify=..."): the site refuses an image without it (403)
    private val IMAGE = Regex("""(?:https?:)?//[^\s"'\\<>]+?\.(?:jpe?g|png|webp|gif|avif|bmp)(?:\?[^\s"'\\<>]*)?""", RegexOption.IGNORE_CASE)
    private val IMAGE_HOST = Regex("""fast_img_host\s*=\s*["']([^"']*)["']""")
    private val IMAGE_PATH = Regex("""url:\s*fast_img_host\s*\+\s*["'](/[^"']+)["']""")
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("""<[^>]+>""")
    private val CHINA = ZoneOffset.ofHours(8)

    fun parseSearch(html: String): List<Album> = html.split("gallary_item").drop(1).mapNotNull { item ->
        val match = ITEM.find(item) ?: return@mapNotNull null
        val pages = PAGES.find(item)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val cover = COVER.find(item)?.groupValues?.get(1)?.let(::absolute)?.takeIf { it.startsWith("http") }
        val created = CREATED.find(item)?.let { time(it.groupValues[1], it.groupValues[2]) } ?: 0
        val category = CATEGORY.find(item)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        Album(match.groupValues[1].toLong(), unescape(match.groupValues[2]), pages, cover, created, category)
    }.distinctBy { it.aid }

    /** Whether the pages of a search link to page [page]. */
    fun hasPage(html: String, page: Int) = Regex("""[?&](?:amp;)?p=$page(?:[&"]|$)""").containsMatchIn(html)

    fun parseAlbum(aid: Long, html: String): AlbumPage {
        val header = html.substringAfter("<h2>", "").substringBefore("class=\"comment_wrap\"")
        val title = H2.find(html)?.groupValues?.get(1)?.let(::text).orEmpty()
        val cover = html.substringAfter("uwthumb", "").let { COVER.find(it)?.groupValues?.get(1) }?.let(::absolute)
            ?.takeIf { it.startsWith("http") }
        val category = CATEGORY_LABEL.find(header)?.groupValues?.get(1)?.trim()?.let(::categoryOf) ?: 0
        val pages = PAGES_LABEL.find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val tags = header.substringAfter("addtags", "").substringBefore("</div>").let { TAG.findAll(it) }
            .map { text(it.groupValues[1]) }.filter { it.isNotBlank() }.distinct().toList()
        val description = DESCRIPTION.find(header)?.groupValues?.get(1)?.let(::text).orEmpty()
        val uploader = UPLOADER.find(html)?.groupValues?.get(1)?.let(::text)?.ifBlank { null }
        val created = UPLOADED.find(html)?.let { time(it.groupValues[1], "") } ?: 0
        val wrap = html.substringAfter("class=\"comment_wrap\"", "")
        val comments = PREVIEW_COMMENT.findAll(wrap).mapIndexed { index, match ->
            Comment(
                id = index.toLong(),
                user = text(match.groupValues[1]),
                time = time(match.groupValues[3], "") ?: 0,
                text = text(match.groupValues[2]),
                score = match.groupValues[4].toIntOrNull() ?: 0,
            )
        }.toList()
        val commentCount = COMMENT_COUNT.find(wrap)?.groupValues?.get(1)?.toIntOrNull() ?: comments.size
        return AlbumPage(aid, title, cover, category, pages, tags, description, uploader, created, parseThumbnails(html), comments, commentCount)
    }

    fun parseComments(html: String): List<Comment> = html.split("class=\"plItem\"").drop(1).mapNotNull { part ->
        val item = "class=\"plItem\"$part"
        val match = COMMENT.find(item) ?: return@mapNotNull null
        val up = COMMENT_UP.find(item)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val down = COMMENT_DOWN.find(item)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        Comment(
            id = match.groupValues[1].toLong(),
            user = text(match.groupValues[2]),
            time = time(match.groupValues[3].trim(), "") ?: 0,
            text = text(match.groupValues[4]),
            score = up - down,
        )
    }

    /**
     * The images of the reader of an album, from the script that lists them (written out by document.writeln, its
     * quotes escaped): each one is the host told in "fast_img_host" (often empty) followed by its address, which
     * starts with "//" or a path. Each address keeps what follows its "?" ("verify=..."), without which the site
     * refuses the image. The last one, a picture asking to bookmark the site, is a path of the site itself and is not
     * taken.
     */
    fun parseImages(script: String): List<String> {
        val text = script.replace("\\/", "/").replace("\\\"", "\"").replace("\\'", "'")
        val host = IMAGE_HOST.find(text)?.groupValues?.get(1)?.trimEnd('/').orEmpty()
        val listed = IMAGE_PATH.findAll(text).map { absolute(host + it.groupValues[1]) }.toList()
        val urls = listed.ifEmpty { IMAGE.findAll(text).map { absolute(it.value) }.toList() }
        return urls.filter { it.startsWith("http") && "/themes/" !in it && "shoucang" !in it }.distinct()
    }

    /** The category of E-Hentai closest to the one named [name] on the site ("同人誌／漢化"...), as a cate-N. */
    private fun categoryOf(name: String): Int {
        val main = name.substringBefore('／').substringBefore('/').trim()
        val sub = name.substringAfter('／', name.substringAfter('/', "")).trim()
        return when (main) {
            "同人誌" -> when (sub) {
                "漢化" -> 1
                "日語" -> 12
                "English" -> 16
                "CG畫集" -> 2
                "Cosplay" -> 3
                "3D漫畫" -> 22
                "AI圖集" -> 37
                else -> 5
            }
            "單行本" -> when (sub) {
                "漢化" -> 9
                "日語" -> 13
                "English" -> 17
                else -> 6
            }
            "雜誌&短篇", "雜誌&amp;短篇" -> when (sub) {
                "漢化" -> 10
                "日語" -> 14
                "English" -> 18
                else -> 7
            }
            "韓漫" -> 19
            else -> 0
        }
    }

    private fun time(date: String, clock: String): Long? = runCatching {
        val dateTime = "${date}T${clock.ifBlank { "00:00:00" }.let { if (it.length == 5) "$it:00" else it }}"
        LocalDateTime.parse(dateTime).toInstant(CHINA).toEpochMilli()
    }.getOrNull()

    private fun absolute(url: String) = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> BASE + url
        else -> url
    }

    private fun text(html: String) = unescape(html.replace(BR, "\n").replace(HTML_TAG, "")).trim()

    /**
     * The thumbnails of a page of an album, in their order from page [first]: the names of the files ("…_0001") are
     * those the uploader gave, not always numbers nor one after the other.
     */
    fun parseThumbnails(html: String, first: Int = 1): List<Pair<Int, String>> = THUMB.findAll(html.replace(HTML_COMMENT, ""))
        .map { match -> match.groupValues[1].let { if (it.startsWith("//")) "https:$it" else it } }
        .filter { it.startsWith("http") }
        .mapIndexed { i, url -> first + i to url }
        .toList()

    private fun unescape(text: String) = text.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&amp;", "&")
}
