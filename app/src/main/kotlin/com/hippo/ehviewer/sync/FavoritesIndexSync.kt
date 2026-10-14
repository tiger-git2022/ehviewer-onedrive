package com.hippo.ehviewer.sync

import android.os.SystemClock
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.network.EhCookieStore
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.data.FavListUrlBuilder
import com.hippo.ehviewer.client.parser.FavParserResult
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import splitties.init.appCtx

/**
 * Keeps the list of E-Hentai cloud favorites and writes `ehviewer/export/favorites-index.json.gz` (see
 * [FavoritesIndex]) at the end of every OneDrive sync.
 *
 * The cloud list costs few requests: favorite changes made in the app and the favorites pages the user opens update
 * it for free, a sync checks the counts of the 10 slots with one page request (at most once an hour), and only when
 * they differ from the list (favorites changed on the website), the list is 7 days old or was never read, all slots
 * are read again page by page.
 */
object FavoritesIndexSync {
    private const val TAG = "FavoritesIndex"
    private const val EXPORT_FOLDER = "ehviewer/export"
    private const val INDEX_FILE = "favorites-index.json.gz"
    const val INDEX_PATH = "$EXPORT_FOLDER/$INDEX_FILE"
    private const val MAX_ATTEMPTS = 3
    private const val CHECK_INTERVAL_MILLIS = 60 * 60_000L
    private const val PAGE_INTERVAL_MILLIS = 1_000L
    private const val TITLE_LOOKUPS_PER_SYNC = 500
    private const val TITLE_LOOKUP_CHUNK = 100

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val refreshMutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // Outside files/onedrive_sync: the cloud favorites belong to the E-Hentai account, not to the OneDrive one
    private val storeFile get() = File(appCtx.filesDir, "cloud_favorites.json.gz")
    private val syncDir get() = File(appCtx.filesDir, "onedrive_sync")
    private val lastIndexFile get() = File(syncDir, "favorites_index.json.gz")

    @Volatile
    private var cache: CloudFavorites? = null

    /** [SystemClock.elapsedRealtime] of the last count check, 0 before the first one. */
    @Volatile
    private var lastCheck = 0L

    /** E-Hentai account of [lastCheck]: another account is checked right away. */
    @Volatile
    private var lastCheckUser: String? = null

    /**
     * Changes made while a full refresh reads its pages, replayed on top of its result so the older pages do not undo
     * them. Null when no refresh runs. Guarded by [mutex].
     */
    private var journal: MutableList<(CloudFavorites) -> CloudFavorites>? = null

    private val mutableRefreshing = MutableStateFlow(false)

    /** True while the cloud favorites are read page by page. */
    val refreshing: StateFlow<Boolean> = mutableRefreshing.asStateFlow()

    private val mutableProgress = MutableStateFlow<StepProgress?>(null)

    /** What the favorites index step of a sync or a refresh of the cloud favorites does now, null when idle. */
    val progress: StateFlow<StepProgress?> = mutableProgress.asStateFlow()

    private fun now() = Clock.System.now().toEpochMilliseconds()

    private val enabled: Boolean
        get() = OneDriveSyncState.isSignedIn && OneDriveSyncState.exportFavoritesIndex.value

    // region Cloud favorites store

    private fun <T> readGzip(file: File, serializer: KSerializer<T>): T = GZIPInputStream(file.inputStream().buffered()).use { json.decodeFromStream(serializer, it) }

    private fun <T> writeGzip(file: File, serializer: KSerializer<T>, value: T) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        GZIPOutputStream(temp.outputStream().buffered()).use { json.encodeToStream(serializer, value, it) }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun loadLocked(): CloudFavorites = cache ?: runCatching { readGzip(storeFile, CloudFavorites.serializer()) }
        .onFailure { if (storeFile.exists()) logcat(TAG, it) }
        .getOrNull()
        ?.takeIf { it.version <= FAVORITES_INDEX_VERSION }
        .let { it ?: CloudFavorites() }
        .also { cache = it }

    /** Hash of the E-Hentai member id, null when signed out. The id itself is not written anywhere. */
    private fun userKey(): String? = EhCookieStore.getUserId()?.takeIf { it.isNotBlank() }?.let { id ->
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    private suspend fun current(): CloudFavorites = withContext(Dispatchers.IO) {
        mutex.withLock { FavoritesIndexMerger.forUser(loadLocked(), userKey()) }
    }

    /**
     * Applies [transform] to the list of the signed in E-Hentai account (an empty one after switching accounts) and
     * saves the result; returns true if the list changed. With [user], a change made for another account than the
     * signed in one is dropped.
     */
    private suspend fun update(
        user: String? = userKey(),
        transform: (CloudFavorites) -> CloudFavorites,
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (userKey() != user) return@withLock false
            journal?.add(transform)
            saveLocked(transform(FavoritesIndexMerger.forUser(loadLocked(), userKey())))
        }
    }

    private fun saveLocked(new: CloudFavorites): Boolean {
        if (new == loadLocked()) return false
        cache = new
        writeGzip(storeFile, CloudFavorites.serializer(), new)
        return true
    }

    /**
     * Updates the store off the caller's coroutine, and schedules a sync of the index if it changed. The store is kept
     * up to date while the export is off too: the count check cannot see a favorite replaced by another in one slot.
     */
    private fun updateLater(
        // The account the change was made with, not the one signed in when it runs
        user: String? = userKey(),
        transform: (CloudFavorites) -> CloudFavorites,
    ) {
        scope.launch {
            runCatching { update(user, transform) }
                .onFailure { logcat(TAG, it) }
                .onSuccess { changed -> if (changed && enabled) OneDriveSync.notifyFavoritesIndexChanged() }
        }
    }

    private fun GalleryInfo.toIndexed(slot: Int) = IndexedGallery(
        gid = gid,
        token = token,
        title = title,
        titleJpn = titleJpn?.takeIf { it.isNotEmpty() },
        uploader = uploader,
        category = category,
        slot = slot,
    )

    /** The galleries of a favorites page of [favCat] (a slot, or "all" where the slot is known by its name). */
    private fun pageUpdate(favCat: Int, result: FavParserResult): (CloudFavorites) -> CloudFavorites {
        val slotNames = result.catArray
        val galleries = result.galleryInfoList.map { info ->
            val slot = if (FavListUrlBuilder.isValidFavCat(favCat)) favCat else FavoritesIndexMerger.slotOf(info.favoriteName, slotNames)
            info.toIndexed(slot)
        }
        return { cloud ->
            val time = now()
            FavoritesIndexMerger.upsert(FavoritesIndexMerger.withSlotNames(cloud, slotNames, time), galleries, time)
        }
    }

    /** The E-Hentai account to pass to [onPageLoaded], taken when a page is requested. */
    fun accountKey(): String? = userKey()

    /** A page of the cloud favorites requested with the E-Hentai account [user] was loaded in the favorites screen. */
    fun onPageLoaded(favCat: Int, result: FavParserResult, user: String?) = updateLater(user, pageUpdate(favCat, result))

    /** A favorite was changed through [com.hippo.ehviewer.util.FavouriteStatusRouter]. */
    fun onFavoriteChanged(info: GalleryInfo) {
        val slot = info.favoriteSlot
        val gallery = info.toIndexed(slot)
        updateLater { cloud ->
            if (FavListUrlBuilder.isValidFavCat(slot)) {
                FavoritesIndexMerger.upsert(cloud, listOf(gallery), now())
            } else {
                FavoritesIndexMerger.remove(cloud, listOf(info.gid), now())
            }
        }
    }

    /** Galleries added to cloud slot [slot] from the local favorites. */
    fun onAdded(infos: Collection<GalleryInfo>, slot: Int) {
        val galleries = infos.map { it.toIndexed(slot) }
        updateLater { cloud -> FavoritesIndexMerger.upsert(cloud, galleries, now()) }
    }

    /** Cloud favorites moved to [slot], or removed when [slot] is not a cloud slot. */
    fun onMoved(gids: Collection<Long>, slot: Int) {
        val list = gids.toList()
        updateLater { cloud -> FavoritesIndexMerger.move(cloud, list, slot, now()) }
    }

    // endregion

    // region E-Hentai

    /** Reads all cloud favorites again, page by page. Called by a sync or by the user. */
    suspend fun refreshCloudFavorites(): Unit = withContext(Dispatchers.IO) {
        // A refresh asked while one runs is answered by the running one
        if (refreshMutex.isLocked) {
            refreshMutex.withLock { }
            return@withContext
        }
        refreshMutex.withLock { refreshLocked() }
    }

    private suspend fun refreshLocked() {
        mutableRefreshing.value = true
        withContext(Dispatchers.IO) { mutex.withLock { journal = ArrayList() } }
        try {
            val user = userKey()
            val first = EhEngine.getFavoritesPage(FavListUrlBuilder().build())
            // Each page is checked: another account, even one signed in and out again, makes the pages unusable
            if (userKey() != user) return
            val slotNames = first.catArray
            updateSettings(first)
            val galleries = ArrayList<IndexedGallery>()
            val total = first.countArray.sum()
            mutableProgress.value = StepProgress(R.string.onedrive_sync_step_read_cloud_favorites, 0, total)
            first.countArray.forEachIndexed { slot, count ->
                if (count <= 0) return@forEachIndexed
                var next: String? = null
                do {
                    delay(PAGE_INTERVAL_MILLIS)
                    val cursor = next
                    val url = FavListUrlBuilder(favCat = slot).apply { if (cursor != null) setIndex(cursor, isNext = true) }.build()
                    val page = EhEngine.getFavoritesPage(url)
                    if (userKey() != user) return
                    page.galleryInfoList.mapTo(galleries) { it.toIndexed(slot) }
                    mutableProgress.value = StepProgress(R.string.onedrive_sync_step_read_cloud_favorites, galleries.size, total)
                    next = page.next
                } while (next != null)
            }
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val changes = journal.orEmpty()
                    journal = null
                    // Signed in to another account meanwhile: these pages belong to the previous one
                    if (userKey() != user) return@withLock
                    val refreshed = FavoritesIndexMerger.replaceAll(FavoritesIndexMerger.forUser(loadLocked(), user), galleries, slotNames, now())
                    saveLocked(changes.fold(refreshed) { cloud, change -> change(cloud) })
                }
            }
            logcat(TAG) { "Cloud favorites refreshed: ${galleries.size} galleries" }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { mutex.withLock { journal = null } }
            mutableRefreshing.value = false
            mutableProgress.value = null
        }
    }

    private fun updateSettings(result: FavParserResult) {
        Settings.favCat = result.catArray.toTypedArray()
        Settings.favCount = result.countArray.toIntArray()
        Settings.favCloudCount = result.countArray.sum()
    }

    /** One page request: refresh everything only if the counts of E-Hentai do not match the known list. */
    private suspend fun checkCloudFavorites() {
        val elapsed = SystemClock.elapsedRealtime()
        val user = userKey()
        if (lastCheck != 0L && lastCheckUser == user && elapsed - lastCheck < CHECK_INTERVAL_MILLIS) return
        val first = EhEngine.getFavoritesPage(FavListUrlBuilder().build())
        if (userKey() != user) return
        lastCheck = elapsed
        lastCheckUser = user
        updateSettings(first)
        // The newest favorites are on the first page of "all"
        update(user, pageUpdate(FavListUrlBuilder.FAV_CAT_ALL, first))
        if (FavoritesIndexMerger.needsRefresh(current(), first.countArray, now())) {
            refreshCloudFavorites()
        }
    }

    /** Looks up the Japanese titles the index does not have yet, a limited number per sync. */
    private suspend fun lookUpJapaneseTitles(index: FavoritesIndex) {
        val missing = FavoritesIndexMerger.missingJapaneseTitles(index, TITLE_LOOKUPS_PER_SYNC)
        if (missing.isEmpty()) return
        val infos = missing.map { BaseGalleryInfo(gid = it.gid, token = it.token) }
        infos.chunked(TITLE_LOOKUP_CHUNK).forEachIndexed { index, chunk ->
            mutableProgress.value = StepProgress(R.string.onedrive_sync_step_japanese_titles, index * TITLE_LOOKUP_CHUNK, infos.size)
            EhEngine.fillGalleryListByApi(chunk)
        }
        val titles = infos.filter { !it.title.isNullOrEmpty() }.associate { it.gid to it.titleJpn.orEmpty() }
        update { cloud -> FavoritesIndexMerger.withJapaneseTitles(cloud, titles) }
        logcat(TAG) { "Looked up ${titles.size} Japanese titles" }
    }

    // endregion

    // region OneDrive

    /**
     * Uploads the index if it changed. Runs inside [OneDriveSync.sync]; a failure of the E-Hentai part does not stop
     * the upload of what is known.
     */
    suspend fun sync(deviceId: String, deviceName: String) {
        try {
            syncIndex(deviceId, deviceName)
        } finally {
            mutableProgress.value = null
        }
    }

    private suspend fun syncIndex(deviceId: String, deviceName: String) {
        if (!OneDriveSyncState.exportFavoritesIndex.value) {
            removeExport()
            return
        }
        var remote = OneDriveClient.list(EXPORT_FOLDER)[INDEX_FILE]
        adoptRemote(remote)
        val signedIn = Settings.hasSignedIn.value
        if (signedIn) {
            mutableProgress.value = StepProgress(R.string.onedrive_sync_step_check_cloud_favorites)
            runEhStep { checkCloudFavorites() }
        }
        val local = EhDB.getLocalFavoritesWithGallery().map { (_, gallery) -> gallery.toIndexed(NO_CLOUD_SLOT) }
        val build = suspend {
            // Without an E-Hentai account the cloud favorites are unknown, the names of the slots too
            val cloud = if (signedIn) current() else CloudFavorites()
            FavoritesIndexMerger.buildIndex(local, cloud, deviceId, deviceName, now())
        }
        var index = build()
        if (signedIn) {
            runEhStep { lookUpJapaneseTitles(index) }
            index = build()
            val listed = index
            update { cloud -> FavoritesIndexMerger.pruneTitles(cloud, listed) }
        }

        mutableProgress.value = StepProgress(R.string.onedrive_sync_step_upload_index)
        for (attempt in 0 until MAX_ATTEMPTS) {
            val last = runCatching { readGzip(lastIndexFile, FavoritesIndex.serializer()) }.getOrNull()
            val storedETag = OneDriveSyncState.favoritesIndexETag.value
            if (remote != null && remote.eTag == storedETag && last != null && last.sameContent(index)) {
                logcat(TAG) { "Index unchanged" }
                return
            }
            val output = File(syncDir, "favorites_index.upload")
            writeGzip(output, FavoritesIndex.serializer(), index)
            val eTag = try {
                OneDriveClient.upload(INDEX_PATH, output, remote?.eTag)
            } catch (e: OneDrivePreconditionException) {
                if (attempt == MAX_ATTEMPTS - 1) throw e
                // Another device wrote its index meanwhile: take its newer cloud part, then write again
                remote = OneDriveClient.list(EXPORT_FOLDER)[INDEX_FILE]
                adoptRemote(remote)
                index = build()
                continue
            }
            if (!output.renameTo(lastIndexFile)) {
                output.copyTo(lastIndexFile, overwrite = true)
                output.delete()
            }
            OneDriveSyncState.withMutableSnapshot {
                it[OneDriveSyncState.favoritesIndexETag] = eTag
                it[OneDriveSyncState.favoritesIndexRemoved] = false
            }
            logcat(TAG) { "Index uploaded: ${index.galleries.size} galleries" }
            return
        }
    }

    /**
     * Sharing was turned off: delete the index, so other apps stop seeing the favorites. Done once after turning it
     * off, also when this device never uploaded one (a reinstall, another OneDrive account that already has one).
     */
    private suspend fun removeExport() {
        if (OneDriveSyncState.favoritesIndexRemoved.value) return
        OneDriveClient.delete(INDEX_PATH)
        lastIndexFile.delete()
        OneDriveSyncState.withMutableSnapshot {
            it[OneDriveSyncState.favoritesIndexETag] = null
            it[OneDriveSyncState.favoritesIndexRemoved] = true
        }
        logcat(TAG) { "Index deleted" }
    }

    /** Takes the cloud part of the index another device uploaded, when it is newer than ours. */
    private suspend fun adoptRemote(remote: RemoteItem?) {
        if (remote == null || remote.eTag == OneDriveSyncState.favoritesIndexETag.value) return
        val download = File(syncDir, "favorites_index.download")
        try {
            if (!OneDriveClient.download(INDEX_PATH, download)) return
            val index = readGzip(download, FavoritesIndex.serializer())
            OneDriveSyncMerger.checkVersion(index.version)
            update { cloud -> FavoritesIndexMerger.adopt(cloud, index) }
        } finally {
            download.delete()
        }
    }

    private suspend fun runEhStep(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // E-Hentai unreachable or signed out: upload what is known
            logcat(TAG, e)
        }
    }

    /** The OneDrive account changed: the index of the new account is unknown. */
    fun onCloudStateReset() {
        lastCheck = 0L
        lastCheckUser = null
    }

    // endregion
}
