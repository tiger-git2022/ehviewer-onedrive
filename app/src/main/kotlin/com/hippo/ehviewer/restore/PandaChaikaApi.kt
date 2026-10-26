package com.hippo.ehviewer.restore

import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.URL_PREFIX_THUMB_E
import com.hippo.ehviewer.client.executeSafely
import com.hippo.ehviewer.client.getThumbKey
import com.hippo.ehviewer.client.parser.ParserUtils
import com.hippo.ehviewer.util.bodyAsUtf8Text
import com.hippo.ehviewer.util.ensureSuccess
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Fork: the JSON API of panda.chaika.moe, a backup of galleries E-Hentai removed (mostly English and raw Japanese). It
 * keeps the metadata (titles, tags, pages, rating, uploader) and an archive of the images, no previews and no comments.
 * Its archives download without an account.
 */
object PandaChaikaApi {
    const val BASE_URL = "https://panda.chaika.moe"
    private const val API_URL = "$BASE_URL/api"
    private const val GALLERIES_URL = "$BASE_URL/galleries/"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val linkRegex = Regex("""/g/(\d+)/([0-9a-f]+)""")

    @Serializable
    data class Archive(val link: String? = null, val download: String? = null) {
        /** Where the archive downloads from. */
        val url: String?
            get() = (link ?: download)?.let { if (it.startsWith("http")) it else BASE_URL + it }
    }

    /** A gallery as /api?gid= and the gallery search list it; the search has a few more fields. */
    @Serializable
    data class Gallery(
        // A string in both, but a number would do
        @SerialName("gid") val gidValue: JsonPrimitive? = null,
        val token: String? = null,
        val link: String? = null,
        val title: String? = null,
        @SerialName("title_jpn") val titleJpn: String? = null,
        val category: String? = null,
        val uploader: String? = null,
        val posted: Long = 0,
        val filecount: Int = 0,
        val filesize: Long = 0,
        // A number from /api, a string from the search
        @SerialName("rating") val ratingValue: JsonPrimitive? = null,
        val tags: List<String> = emptyList(),
        // The description; filled for the galleries of some other sources, empty for those of E-Hentai
        val comment: String? = null,
        @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
        val thumbnail: String? = null,
        val disowned: Boolean = false,
        val archives: List<Archive> = emptyList(),
    ) {
        val gid: Long?
            get() = gidValue?.contentOrNull?.toLongOrNull() ?: link?.let { linkRegex.find(it) }?.groupValues?.get(1)?.toLongOrNull()

        val ehToken: String?
            get() = token?.takeIf { it.isNotBlank() } ?: link?.let { linkRegex.find(it) }?.groupValues?.get(2)

        val rating: Float
            get() = ratingValue?.contentOrNull?.toFloatOrNull() ?: 0f

        val archiveUrl: String?
            get() = archives.firstNotNullOfOrNull { it.url }

        /** Tags as E-Hentai writes them: spaces, not underscores. */
        val ehTags: List<String>
            get() = tags.map { it.replace('_', ' ') }

        /** The cover: the one of E-Hentai when known (ehgt.org still serves it), else that of PandaChika. */
        val thumbKey: String?
            get() = thumbnailUrl?.takeIf { it.startsWith(URL_PREFIX_THUMB_E) }?.let(::getThumbKey)
                ?: thumbnail?.let { if (it.startsWith("http")) it else BASE_URL + it }

        /** As a gallery of a list, null without a gid or a token. */
        fun toGalleryInfo(): BaseGalleryInfo? {
            val gid = gid ?: return null
            val token = ehToken ?: return null
            return BaseGalleryInfo(
                gid = gid,
                token = token,
                title = title,
                titleJpn = titleJpn?.ifBlank { null },
                thumbKey = thumbKey,
                category = EhUtils.getCategory(category),
                posted = ParserUtils.formatDate(posted * 1000),
                uploader = uploader,
                disowned = disowned,
                rating = rating,
                simpleTags = ehTags,
                pages = filecount,
            ).apply { generateSLang() }
        }
    }

    @Serializable
    data class GalleryPage(
        val galleries: List<Gallery> = emptyList(),
        @SerialName("has_next") val hasNext: Boolean = false,
    )

    /** The backup of the E-Hentai gallery [gid], null when PandaChika has none. */
    suspend fun gallery(gid: Long): Gallery? = ktorClient.prepareGet(API_URL) {
        parameter("gid", gid)
    }.executeSafely {
        it.status.ensureSuccess()
        decodeGallery(it.bodyAsUtf8Text())
    }

    /** A response of /api?gid=, null for {"result": "Gallery does not exist."}. */
    internal fun decodeGallery(text: String): Gallery? {
        val element = json.parseToJsonElement(text).jsonObject
        return if ("result" in element && "title" !in element) null else json.decodeFromJsonElement(Gallery.serializer(), element)
    }

    internal fun decodePage(text: String) = json.decodeFromString(GalleryPage.serializer(), text)

    /**
     * Page [page] (from 1, 100 galleries) of the galleries whose title (default or Japanese) contains [title], with all
     * of [tags] ("ns:tag", "-ns:tag" to leave one out) and from [uploader], newest posted first.
     */
    suspend fun search(title: String?, tags: List<String>, uploader: String?, page: Int = 1): GalleryPage = ktorClient.prepareGet(GALLERIES_URL) {
        if (!title.isNullOrBlank()) parameter("title", title)
        if (tags.isNotEmpty()) parameter("tags", tags.joinToString(","))
        if (!uploader.isNullOrBlank()) parameter("uploader", uploader)
        parameter("sort", "posted")
        parameter("asc_desc", "desc")
        parameter("json", "")
        if (page > 1) parameter("page", page)
    }.executeSafely {
        it.status.ensureSuccess()
        decodePage(it.bodyAsUtf8Text())
    }
}
