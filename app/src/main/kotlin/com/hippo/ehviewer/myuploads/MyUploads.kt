package com.hippo.ehviewer.myuploads

import androidx.core.text.HtmlCompat
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryComment
import com.ehviewer.core.network.EhCookieStore
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.ehRequest
import com.hippo.ehviewer.client.executeSafely
import com.hippo.ehviewer.client.exception.NotLoggedInException
import com.hippo.ehviewer.sync.OneDriveSync
import com.hippo.ehviewer.sync.SyncCategory
import com.hippo.ehviewer.util.bodyAsUtf8Text
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splitties.init.appCtx

/** A comment under one of my galleries, as plain text. */
@Serializable
data class UploadComment(
    val id: Long,
    val user: String,
    /** When it was posted, ms. */
    val time: Long,
    val score: Int,
    val text: String,
)

/** A gallery of 「我的上传」, with its comments, newest first, the one of the uploader left out. */
@Serializable
data class MyUpload(
    val info: BaseGalleryInfo,
    val favorites: Int = 0,
    val ratingCount: Int = 0,
    val comments: List<UploadComment> = emptyList(),
    /** When the page of the gallery was read last, ms; 0 for never. */
    val fetched: Long = 0,
) {
    val gid get() = info.gid
}

@Serializable
data class MyUploadsData(
    val items: List<MyUpload> = emptyList(),
    /** When the list of 「我的上传」 was read last, ms. */
    val listFetched: Long = 0,
    /** By gid, the newest comment read. No entry: the comments are read the first time, all taken as read then. */
    val seen: Map<Long, Long> = emptyMap(),
    /** 「只看有新评论」. */
    val onlyNew: Boolean = false,
)

/** What a refresh did. */
data class MyUploadsProgress(val done: Int, val total: Int)

/**
 * Fork: 「我的上传」, the galleries published from this account, with their comments shown in the list, the new ones
 * marked, like the page 「已上传」 of EH 上传助手 (tiger-git2022/EHUploader). The list comes from the gallery manager of
 * upload.e-hentai.org, the comments, rating and favorites from the page of each gallery, read again by how lively it
 * is ([MyUploadsRules.isDue]). Kept in files/my_uploads.json, not in the database; the comments read are synced with OneDrive
 * ([seen], merged by the newest), so a gallery read on one device is read on the others.
 */
object MyUploads {
    private const val TAG = "MyUploads"
    private const val MANAGE_URL = "https://upload.e-hentai.org/manage?ss=d&sd=d"

    private val file get() = File(appCtx.filesDir, "my_uploads.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val dataLock = Mutex()
    private val refreshLock = Mutex()

    private val _data = MutableStateFlow(load())
    val data: StateFlow<MyUploadsData> = _data.asStateFlow()

    private val _progress = MutableStateFlow<MyUploadsProgress?>(null)

    /** The pages read so far by the refresh running, null while none runs. */
    val progress: StateFlow<MyUploadsProgress?> = _progress.asStateFlow()

    /** Whether the list was ever read: the work in the background only runs then. */
    val used get() = _data.value.listFetched > 0

    private fun load(): MyUploadsData = runCatching {
        if (file.exists()) json.decodeFromString<MyUploadsData>(file.readText()) else MyUploadsData()
    }.onFailure { logcat(TAG, it) }.getOrDefault(MyUploadsData())

    private suspend fun update(transform: (MyUploadsData) -> MyUploadsData) = dataLock.withLock {
        val new = transform(_data.value)
        if (new != _data.value) {
            _data.value = new
            runCatching {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(json.encodeToString(MyUploadsData.serializer(), new))
                tmp.renameTo(file)
            }.onFailure { logcat(TAG, it) }
        }
    }

    // region Read and new

    /** The comments of [upload] not read yet. */
    fun newComments(upload: MyUpload, seen: Map<Long, Long>): List<UploadComment> {
        val last = seen[upload.gid] ?: return emptyList()
        return upload.comments.filter { it.id > last }
    }

    fun newCount(data: MyUploadsData) = data.items.sumOf { newComments(it, data.seen).size }

    suspend fun markRead(gid: Long) {
        update { d ->
            val upload = d.items.find { it.gid == gid } ?: return@update d
            val max = upload.comments.maxOfOrNull { it.id } ?: return@update d
            if ((d.seen[gid] ?: -1) >= max) d else d.copy(seen = d.seen + (gid to max))
        }
        OneDriveSync.notifyChanged(SyncCategory.MY_UPLOADS)
    }

    suspend fun markAllRead() {
        update { d ->
            val seen = d.seen.toMutableMap()
            d.items.forEach { u -> u.comments.maxOfOrNull { it.id }?.let { max -> if ((seen[u.gid] ?: -1) < max) seen[u.gid] = max } }
            d.copy(seen = seen)
        }
        OneDriveSync.notifyChanged(SyncCategory.MY_UPLOADS)
    }

    suspend fun setOnlyNew(onlyNew: Boolean) = update { it.copy(onlyNew = onlyNew) }

    /** The comments read, for the sync. */
    fun seen(): Map<Long, Long> = _data.value.seen

    /** The comments read, as merged by the sync: the newest of here and there. */
    suspend fun applySynced(merged: Map<Long, Long>) = update { d -> d.copy(seen = mergeSeen(d.seen, merged)) }

    fun mergeSeen(a: Map<Long, Long>, b: Map<Long, Long>) = MyUploadsRules.mergeSeen(a, b)

    // endregion

    // region Refresh

    /**
     * Reads the list of 「我的上传」 again, then the pages of the galleries due ([MyUploadsRules.isDue]), or of all with [all]. Waits
     * for one running already, then does what is still due. Throws when the list cannot be read.
     */
    suspend fun refresh(all: Boolean = false) = refreshLock.withLock {
        try {
            refreshLocked(all)
        } finally {
            _progress.value = null
        }
    }

    private suspend fun refreshLocked(all: Boolean) {
        if (!EhCookieStore.hasSignedIn()) throw NotLoggedInException()
        _progress.value = MyUploadsProgress(0, 0)
        val pairs = MyUploadsRules.parseManagePage(ehRequest(MANAGE_URL, EhUrl.REFERER_E).executeSafely { it.bodyAsUtf8Text() })
            ?: throw NotLoggedInException()
        val known = _data.value.items.associateBy { it.gid }
        val new = pairs.filter { it.first !in known }.map { (gid, token) -> BaseGalleryInfo(gid = gid, token = token) }
        // Titles and covers of the new ones at once, from the API; their comments come below
        if (new.isNotEmpty()) runCatching { EhEngine.fillGalleryListByApi(new, EhUrl.REFERER_E) }.onFailure { logcat(TAG, it) }
        val newByGid = new.associateBy { it.gid }
        val now = System.currentTimeMillis()
        update { d ->
            val byGid = d.items.associateBy { it.gid }
            val items = pairs.map { (gid, _) -> byGid[gid] ?: MyUpload(newByGid.getValue(gid)) }
            d.copy(items = items, listFetched = now)
        }
        val due = _data.value.items.filter { all || MyUploadsRules.isDue(it, now) }
        due.forEachIndexed { index, upload ->
            _progress.value = MyUploadsProgress(index, due.size)
            if (index > 0) delay(Random.nextLong(1500, 3000))
            runCatching { fetchGallery(upload.info) }.onFailure { logcat(TAG, it) }
        }
    }

    /** Posts [text] under the gallery [gid], reads its comments again and takes them as read: answered. */
    suspend fun postComment(gid: Long, text: String) {
        val info = _data.value.items.find { it.gid == gid }?.info ?: return
        EhEngine.commentGallery(EhUrl.getGalleryDetailUrl(gid, info.token), text)
        fetchGallery(info)
        markRead(gid)
    }

    private suspend fun fetchGallery(info: BaseGalleryInfo) {
        val detail = EhEngine.getGalleryDetailAllComments(EhUrl.getGalleryDetailUrl(info.gid, info.token, allComment = true))
        val comments = detail.comments.comments.filterNot { it.uploader }.map { it.toUploadComment() }.sortedByDescending { it.id }
        val now = System.currentTimeMillis()
        update { d ->
            val seen = if (info.gid in d.seen) d.seen else d.seen + (info.gid to (comments.maxOfOrNull { it.id } ?: 0))
            d.copy(
                items = d.items.map {
                    if (it.gid != info.gid) {
                        it
                    } else {
                        it.copy(info = detail.galleryInfo, favorites = detail.favoriteCount, ratingCount = detail.ratingCount, comments = comments, fetched = now)
                    }
                },
                seen = seen,
            )
        }
    }

    private fun GalleryComment.toUploadComment() = UploadComment(
        id = id,
        user = user.orEmpty(),
        time = time,
        score = score,
        text = HtmlCompat.fromHtml(comment, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().replace("￼", "").trim(),
    )

    // endregion
}
