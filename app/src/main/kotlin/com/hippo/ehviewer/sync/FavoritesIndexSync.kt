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
 * it for free, and a sync checks the counts of the 10 slots with one page request (at most once an hour). When they
 * differ (favorites changed on the website), the newest pages of "all" add the favorites added there, and only the
 * slots whose counts still differ are read again page by page. Reading pages (all of them the first time) and looking
 * up Japanese titles take long with many favorites: [CloudFavoritesWork] does it in the background, not the sync.
 */
object FavoritesIndexSync {
    private const val TAG = "FavoritesIndex"
    private const val EXPORT_FOLDER = "ehviewer/export"
    private const val INDEX_FILE = "favorites-index.json.gz"
    const val INDEX_PATH = "$EXPORT_FOLDER/$INDEX_FILE"
    private const val MAX_ATTEMPTS = 3
    private const val CHECK_INTERVAL_MILLIS = 60 * 60_000L
    private const val PAGE_INTERVAL_MILLIS = 1_000L

    /** Pages of "all" (the newest favorites first) a count check reads at most for favorites added on the website. */
    private const val CHECK_MAX_PAGES = 10

    /** Pages read between two saves of the refresh checkpoint. */
    private const val CHECKPOINT_PAGES = 5

    /** An unfinished refresh older than this starts again: its first pages are too old. */
    private const val CHECKPOINT_MAX_AGE_MILLIS = 3 * 24 * 60 * 60_000L

    /** Japanese titles a sync looks up itself (the favorites just added), the rest is left to [CloudFavoritesWork]. */
    private const val TITLE_LOOKUPS_PER_SYNC = 250

    /** [CloudFavoritesWork] uploads the index this often, so other apps see the favorites read so far. */
    private const val WORK_UPLOAD_INTERVAL_MILLIS = 10 * 60_000L

    // One batch of EhEngine.fillGalleryListByApi (5 requests of 25 galleries) and its pause before the next batch
    private const val TITLE_LOOKUP_CHUNK = 125
    private const val TITLE_LOOKUP_INTERVAL_MILLIS = 5_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private val titleMutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // Outside files/onedrive_sync: the cloud favorites belong to the E-Hentai account, not to the OneDrive one
    private val storeFile get() = File(appCtx.filesDir, "cloud_favorites.json.gz")
    private val syncDir get() = File(appCtx.filesDir, "onedrive_sync")
    private val lastIndexFile get() = File(syncDir, "favorites_index.json.gz")
    private val checkpointFile get() = File(appCtx.filesDir, "cloud_favorites_refresh.json.gz")

    @Volatile
    private var cache: CloudFavorites? = null

    /** [SystemClock.elapsedRealtime] of the last count check, 0 before the first one. */
    @Volatile
    private var lastCheck = 0L

    /** E-Hentai account of [lastCheck]: another account is checked right away. */
    @Volatile
    private var lastCheckUser: String? = null

    /** The unfinished refresh, see [CloudRefreshCheckpoint]. Guarded by [mutex], read once from [checkpointFile]. */
    private var checkpoint: CloudRefreshCheckpoint? = null
    private var checkpointLoaded = false

    private val mutableWorking = MutableStateFlow(false)

    /** True while [CloudFavoritesWork] reads the cloud favorites page by page or looks up Japanese titles. */
    val working: StateFlow<Boolean> = mutableWorking.asStateFlow()

    private val mutableWorkProgress = MutableStateFlow<StepProgress?>(null)

    /** What [CloudFavoritesWork] does now, null when it does not run. */
    val workProgress: StateFlow<StepProgress?> = mutableWorkProgress.asStateFlow()

    private val mutableProgress = MutableStateFlow<StepProgress?>(null)

    /** What the favorites index step of a sync does now, null when idle. */
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

    /** A saved change of the list waits for a sync, also when it came from a refresh or a title lookup. */
    private suspend fun markChangedLocked() {
        if (enabled) OneDriveSync.markFavoritesIndexChanged()
    }

    /**
     * Applies [transform] to the list of the signed in E-Hentai account (an empty one after switching accounts) and
     * saves the result; returns true if the list changed. With [user], a change made for another account than the
     * signed in one is dropped. [intended] is the slot the change puts each gallery in ([NO_CLOUD_SLOT] when removed),
     * kept for an unfinished refresh that may have read the gallery but not saved it yet.
     */
    private suspend fun update(
        user: String? = userKey(),
        intended: Map<Long, Int> = emptyMap(),
        // False for a change the index does not show
        markChanged: Boolean = true,
        transform: (CloudFavorites) -> CloudFavorites,
    ): Boolean = withContext(Dispatchers.IO) {
        val changed = mutex.withLock {
            if (userKey() != user) return@withLock false
            val before = FavoritesIndexMerger.forUser(loadLocked(), user)
            val after = transform(before)
            // Pages of an unfinished refresh read before this change must not undo it
            val pending = loadCheckpointLocked()
            if (pending != null && pending.userKey == user) {
                val touched = FavoritesIndexMerger.changedGids(before, after)
                if (!pending.touched.containsAll(touched) || intended.isNotEmpty()) {
                    saveCheckpointLocked(pending.copy(touched = pending.touched + touched, intended = pending.intended + intended))
                }
            }
            saveLocked(after).also { if (it && markChanged) markChangedLocked() }
        }
        changed
    }

    private fun loadCheckpointLocked(): CloudRefreshCheckpoint? {
        if (!checkpointLoaded) {
            checkpointLoaded = true
            checkpoint = runCatching { readGzip(checkpointFile, CloudRefreshCheckpoint.serializer()) }
                .onFailure { if (checkpointFile.exists()) logcat(TAG, it) }
                .getOrNull()
                ?.takeIf { it.version <= FAVORITES_INDEX_VERSION }
        }
        return checkpoint
    }

    private fun saveCheckpointLocked(new: CloudRefreshCheckpoint?) {
        checkpoint = new
        checkpointLoaded = true
        if (new == null) checkpointFile.delete() else writeGzip(checkpointFile, CloudRefreshCheckpoint.serializer(), new)
    }

    private fun CloudRefreshCheckpoint.usableFor(user: String?, now: Long) = userKey == user && now - startedAt in 0..CHECKPOINT_MAX_AGE_MILLIS

    /** A refresh of the cloud favorites of [user] has not read all pages yet. */
    private suspend fun hasUnfinishedRefresh(user: String?): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { loadCheckpointLocked()?.usableFor(user, now()) == true }
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
        intended: Map<Long, Int> = emptyMap(),
        transform: (CloudFavorites) -> CloudFavorites,
    ) {
        scope.launch {
            runCatching { update(user, intended, transform = transform) }
                .onFailure { logcat(TAG, it) }
                .onSuccess { changed -> if (changed && enabled) OneDriveSync.scheduleFavoritesIndexSync() }
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
    private fun pageGalleries(favCat: Int, result: FavParserResult): List<IndexedGallery> = result.galleryInfoList.map { info ->
        val slot = if (FavListUrlBuilder.isValidFavCat(favCat)) favCat else FavoritesIndexMerger.slotOf(info.favoriteName, result.catArray)
        info.toIndexed(slot)
    }

    private fun pageUpdate(favCat: Int, result: FavParserResult): (CloudFavorites) -> CloudFavorites {
        val slotNames = result.catArray
        val galleries = pageGalleries(favCat, result)
        return { cloud ->
            val time = now()
            FavoritesIndexMerger.upsert(FavoritesIndexMerger.withSlotNames(cloud, slotNames, time), galleries, time)
        }
    }

    /** The E-Hentai account to pass to [onPageLoaded], taken when a page is requested. */
    fun accountKey(): String? = userKey()

    /** A page of the cloud favorites requested with the E-Hentai account [user] was loaded in the favorites screen. */
    fun onPageLoaded(favCat: Int, result: FavParserResult, user: String?) = updateLater(user, transform = pageUpdate(favCat, result))

    /** A favorite was changed through [com.hippo.ehviewer.util.FavouriteStatusRouter]. */
    fun onFavoriteChanged(info: GalleryInfo) {
        val slot = info.favoriteSlot
        val gallery = info.toIndexed(slot)
        val intended = if (FavListUrlBuilder.isValidFavCat(slot)) slot else NO_CLOUD_SLOT
        updateLater(intended = mapOf(info.gid to intended)) { cloud ->
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
        // Not a cloud slot: nothing is added, nor removed
        val intended = if (FavListUrlBuilder.isValidFavCat(slot)) galleries.associate { it.gid to slot } else emptyMap()
        updateLater(intended = intended) { cloud -> FavoritesIndexMerger.upsert(cloud, galleries, now()) }
    }

    /** Cloud favorites moved to [slot], or removed when [slot] is not a cloud slot. */
    fun onMoved(gids: Collection<Long>, slot: Int) {
        val list = gids.toList()
        val intended = if (FavListUrlBuilder.isValidFavCat(slot)) slot else NO_CLOUD_SLOT
        updateLater(intended = list.associateWith { intended }) { cloud -> FavoritesIndexMerger.move(cloud, list, slot, now()) }
    }

    // endregion

    // region E-Hentai

    /**
     * The work of [CloudFavoritesWork]: reads the slots of the unfinished refresh page by page ([full]: all slots, a
     * partial refresh is widened to them), then looks up every missing Japanese title, uploading the index on the way.
     */
    suspend fun runWork(full: Boolean): Unit = withContext(Dispatchers.IO) {
        if (!Settings.hasSignedIn.value) return@withContext
        mutableWorking.value = true
        try {
            var lastUpload = SystemClock.elapsedRealtime()
            val uploadIfDue: suspend () -> Unit = {
                if (SystemClock.elapsedRealtime() - lastUpload > WORK_UPLOAD_INTERVAL_MILLIS) {
                    OneDriveSync.uploadFavoritesIndex()
                    lastUpload = SystemClock.elapsedRealtime()
                }
            }
            var widen = full
            var rounds = 0
            // A check of a sync may start another refresh meanwhile: it goes on here
            do {
                if (readPages(widen, uploadIfDue)) OneDriveSync.uploadFavoritesIndex()
                if (widen) OneDriveSyncState.withMutableSnapshot { it[OneDriveSyncState.cloudFavoritesFullRequested] = false }
                widen = false
                lookUpJapaneseTitles(currentIndex(), limit = Int.MAX_VALUE, progress = mutableWorkProgress, wait = true, onBatch = uploadIfDue)
            } while (hasUnfinishedRefresh(userKey()) && ++rounds < 3)
            OneDriveSync.uploadFavoritesIndex()
            // The upload failed (no network, OneDrive unreachable): the work tries again, whatever the auto sync options
            check(!enabled || !OneDriveSyncState.favoritesIndexDirty.value) { appCtx.getString(R.string.onedrive_sync_favorites_index_upload_failed) }
        } finally {
            mutableWorking.value = false
            mutableWorkProgress.value = null
        }
    }

    /**
     * Reads the slots of the unfinished refresh page by page and replaces their known galleries; [full] reads all
     * slots. Returns true when a refresh was completed. [onSaved] runs after every saved checkpoint.
     */
    private suspend fun readPages(full: Boolean, onSaved: suspend () -> Unit): Boolean = refreshMutex.withLock {
        val user = userKey()
        val first = EhEngine.getFavoritesPage(FavListUrlBuilder().build())
        // Each page is checked: another account, even one signed in and out again, makes the pages unusable
        if (userKey() != user) return@withLock false
        val slotNames = first.catArray
        val counts = first.countArray
        updateSettings(first)
        val resumed = withContext(Dispatchers.IO) {
            mutex.withLock {
                val now = now()
                val pending = loadCheckpointLocked()?.takeIf { it.userKey == user }
                val going = pending?.takeIf { it.usableFor(user, now) && !(full && it.slots != ALL_CLOUD_SLOTS) }
                val checkpoint = when {
                    // Slots changed on the website since their pages were read are read again
                    going != null -> FavoritesIndexMerger.resumeCheckpoint(going, counts)
                    // Too old, or widened to all slots: read again from the start
                    pending != null || full -> CloudRefreshCheckpoint(
                        userKey = user,
                        startedAt = now,
                        slots = pending?.slots?.takeUnless { full } ?: ALL_CLOUD_SLOTS,
                        counts = counts,
                    )
                    else -> null
                }
                checkpoint?.also { if (it != going) saveCheckpointLocked(it) }
            }
        } ?: return@withLock false
        var slot = resumed.slot
        var next = resumed.next
        var read = resumed.galleries.size
        val slots = resumed.slots.toHashSet()
        val total = resumed.slots.sumOf { counts.getOrElse(it) { 0 } }
        val pages = ArrayList<IndexedGallery>()
        var pageCount = 0
        logcat(TAG) { "Cloud favorites: reading slots ${resumed.slots} from slot $slot, $read of $total read" }
        mutableWorkProgress.value = StepProgress(R.string.onedrive_sync_step_read_cloud_favorites, read, total)
        while (slot < CLOUD_SLOT_COUNT) {
            if (slot !in slots || counts.getOrElse(slot) { 0 } <= 0) {
                slot++
                next = null
                continue
            }
            delay(PAGE_INTERVAL_MILLIS)
            val cursor = next
            val readSlot = slot
            val url = FavListUrlBuilder(favCat = readSlot).apply { if (cursor != null) setIndex(cursor, isNext = true) }.build()
            val page = EhEngine.getFavoritesPage(url)
            if (userKey() != user) return@withLock false
            page.galleryInfoList.mapTo(pages) { it.toIndexed(readSlot) }
            read += page.galleryInfoList.size
            mutableWorkProgress.value = StepProgress(R.string.onedrive_sync_step_read_cloud_favorites, read, total)
            next = page.next
            if (next == null) slot++
            pageCount++
            if (pageCount % CHECKPOINT_PAGES == 0 || next == null) {
                if (!saveRefreshPages(user, slot, next, pages)) return@withLock false
                pages.clear()
                onSaved()
            }
        }
        completeRefresh(user, slotNames)
    }

    /**
     * Saves the position of the refresh and adds the galleries of its last [pages] to the known list, so the index
     * shows them before the refresh ends. False if the refresh no longer belongs to the signed in account.
     */
    private suspend fun saveRefreshPages(user: String?, slot: Int, next: String?, pages: List<IndexedGallery>): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            val pending = loadCheckpointLocked()
            if (pending == null || pending.userKey != user || userKey() != user) return@withLock false
            saveCheckpointLocked(pending.copy(slot = slot, next = next, galleries = pending.galleries + pages))
            if (saveLocked(FavoritesIndexMerger.withRefreshPages(FavoritesIndexMerger.forUser(loadLocked(), user), pages, pending.touched, now(), pending.intended))) {
                markChangedLocked()
            }
            true
        }
    }

    private suspend fun completeRefresh(user: String?, slotNames: List<String>): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            val pending = loadCheckpointLocked()
            // Signed in to another account meanwhile: these pages belong to the previous one
            if (pending == null || pending.userKey != user || userKey() != user) return@withLock false
            val cloud = FavoritesIndexMerger.forUser(loadLocked(), user)
            val done = FavoritesIndexMerger.completeRefresh(cloud, pending.galleries, pending.touched, slotNames, now(), pending.intended, pending.slots)
            if (saveLocked(done)) markChangedLocked()
            saveCheckpointLocked(null)
            logcat(TAG) { "Cloud favorites refreshed: slots ${pending.slots}, ${pending.galleries.size} galleries" }
            true
        }
    }

    /** Starts a refresh of [slots] unless one is unfinished: the next check finds what that one does not read. */
    private suspend fun startRefresh(user: String?, slots: List<Int>, counts: List<Int>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loadCheckpointLocked()?.usableFor(user, now()) == true) return@withLock
            saveCheckpointLocked(CloudRefreshCheckpoint(userKey = user, startedAt = now(), slots = slots, counts = counts))
            logcat(TAG) { "Cloud favorites: slots $slots to read again" }
        }
    }

    private fun updateSettings(result: FavParserResult) {
        Settings.favCat = result.catArray.toTypedArray()
        Settings.favCount = result.countArray.toIntArray()
        Settings.favCloudCount = result.countArray.sum()
    }

    /**
     * One page request when nothing changed, at most once an hour: the counts of E-Hentai against the known list.
     * Favorites added on the website are taken from the newest pages of "all"; slots whose counts still differ
     * (favorites removed or moved there, or the list was never read) are left to [CloudFavoritesWork].
     */
    private suspend fun checkCloudFavorites() {
        val user = userKey()
        if (hasUnfinishedRefresh(user)) {
            enqueueCloudFavoritesWork(appCtx)
            return
        }
        val elapsed = SystemClock.elapsedRealtime()
        if (lastCheck != 0L && lastCheckUser == user && elapsed - lastCheck < CHECK_INTERVAL_MILLIS) return
        var page = EhEngine.getFavoritesPage(FavListUrlBuilder().build())
        if (userKey() != user) return
        lastCheck = elapsed
        lastCheckUser = user
        updateSettings(page)
        val counts = page.countArray
        var pageCount = 1
        while (true) {
            val before = current()
            val added = FavoritesIndexMerger.newGalleries(before, pageGalleries(FavListUrlBuilder.FAV_CAT_ALL, page))
            update(user, transform = pageUpdate(FavListUrlBuilder.FAV_CAT_ALL, page))
            val cloud = current()
            val slots = FavoritesIndexMerger.slotsToRefresh(cloud, counts)
            if (slots.isEmpty()) return
            val cursor = page.next
            // Never read completely, or the next page cannot hold the difference: read the slots page by page
            if (cloud.refreshedAt == 0L || added.isEmpty() || cursor == null || pageCount >= CHECK_MAX_PAGES) {
                startRefresh(user, slots, counts)
                enqueueCloudFavoritesWork(appCtx)
                return
            }
            mutableProgress.value = StepProgress(R.string.onedrive_sync_step_check_cloud_favorites, pageCount, CHECK_MAX_PAGES, StepProgress.CHECK)
            delay(PAGE_INTERVAL_MILLIS)
            page = EhEngine.getFavoritesPage(FavListUrlBuilder().apply { setIndex(cursor, isNext = true) }.build())
            if (userKey() != user) return
            pageCount++
        }
    }

    /** Reads the cloud favorites and looks up every missing Japanese title again in the background. */
    fun refreshInBackground() = enqueueCloudFavoritesWork(appCtx, full = true)

    /** How far the cloud favorites are read ([read] of [total] when a refresh is [unfinished]) and looked up. */
    data class Status(val unfinished: Boolean = false, val read: Int = 0, val total: Int = 0, val missingTitles: Int = 0)

    suspend fun status(): Status = withContext(Dispatchers.IO) {
        if (!Settings.hasSignedIn.value) return@withContext Status()
        val user = userKey()
        val pending = mutex.withLock { loadCheckpointLocked()?.takeIf { it.userKey == user } }
        val missing = FavoritesIndexMerger.missingJapaneseTitles(currentIndex(), Int.MAX_VALUE, current().defaultTitles).size
        Status(
            unfinished = pending != null,
            read = pending?.galleries?.size ?: 0,
            total = pending?.let { checkpoint -> checkpoint.slots.sumOf { checkpoint.counts.getOrElse(it) { 0 } } } ?: 0,
            missingTitles = missing,
        )
    }

    private suspend fun currentIndex(): FavoritesIndex {
        val local = EhDB.getLocalFavoritesWithGallery().map { (_, gallery) -> gallery.toIndexed(NO_CLOUD_SLOT) }
        return FavoritesIndexMerger.buildIndex(local, current(), deviceId = "", deviceName = "", now = now())
    }

    /**
     * Looks up the Japanese titles the index does not have yet, at most [limit]. With [wait] it waits for a lookup
     * running elsewhere, otherwise it leaves them to that one. [onBatch] runs after every saved batch.
     */
    private suspend fun lookUpJapaneseTitles(
        index: FavoritesIndex,
        limit: Int,
        progress: MutableStateFlow<StepProgress?>,
        part: ClosedFloatingPointRange<Float> = 0f..1f,
        wait: Boolean = false,
        onBatch: suspend () -> Unit = {},
    ) {
        if (wait) {
            titleMutex.lock()
        } else if (!titleMutex.tryLock()) {
            return
        }
        try {
            val missing = FavoritesIndexMerger.missingJapaneseTitles(index, limit, current().defaultTitles, fromEnd = lookUpFromEnd())
            if (missing.isEmpty()) return
            var found = 0
            var requested = false
            for ((i, all) in missing.chunked(TITLE_LOOKUP_CHUNK).withIndex()) {
                // Another device may have uploaded them meanwhile (taken with each upload of the work)
                val chunk = FavoritesIndexMerger.stillMissing(all, current())
                if (chunk.isEmpty()) continue
                if (requested) delay(TITLE_LOOKUP_INTERVAL_MILLIS)
                requested = true
                progress.value = StepProgress(R.string.onedrive_sync_step_japanese_titles, i * TITLE_LOOKUP_CHUNK, missing.size, part)
                val infos = chunk.map { BaseGalleryInfo(gid = it.gid, token = it.token) }
                EhEngine.fillGalleryListByApi(infos)
                // A gallery the API does not return (removed, for one) is not asked again: it has no title to find
                val titles = infos.associate { it.gid to if (it.title.isNullOrEmpty()) "" else it.titleJpn.orEmpty() }
                val defaults = infos.associate { it.gid to it.title.orEmpty() }
                // Saved per batch: a failed request later keeps what was found
                update { cloud -> FavoritesIndexMerger.withJapaneseTitles(cloud, titles, defaults) }
                found += titles.size
                onBatch()
            }
            logcat(TAG) { "Looked up $found of ${missing.size} titles" }
        } finally {
            titleMutex.unlock()
        }
    }

    /** Half of the devices look up from the other end of the list: two at once meet in the middle. */
    private fun lookUpFromEnd() = (OneDriveSyncState.deviceId.value?.hashCode() ?: 0) and 1 == 1

    // endregion

    // region OneDrive

    /**
     * Uploads the index if it changed. Runs inside [OneDriveSync.sync]; a failure of the E-Hentai part does not stop
     * the upload of what is known. [fromWork]: an upload of [CloudFavoritesWork], which checks and looks up itself.
     */
    suspend fun sync(deviceId: String, deviceName: String, fromWork: Boolean = false) {
        try {
            val version = syncIndex(deviceId, deviceName, fromWork)
            OneDriveSync.favoritesIndexSynced(version)
        } finally {
            mutableProgress.value = null
        }
    }

    /** Returns the version of the cloud favorites changes the uploaded index includes. */
    private suspend fun syncIndex(deviceId: String, deviceName: String, fromWork: Boolean): Long {
        if (!OneDriveSyncState.exportFavoritesIndex.value) {
            val version = OneDriveSync.favoritesIndexVersion()
            removeExport()
            return version
        }
        var remote = OneDriveClient.list(EXPORT_FOLDER)[INDEX_FILE]
        adoptRemote(remote)
        val signedIn = Settings.hasSignedIn.value
        if (signedIn && !fromWork) {
            mutableProgress.value = StepProgress(R.string.onedrive_sync_step_check_cloud_favorites, part = StepProgress.CHECK)
            runEhStep { checkCloudFavorites() }
        }
        var version = 0L
        val build = suspend {
            // Before reading: the changes up to this version are in the index built now, a later one keeps it waiting
            version = OneDriveSync.favoritesIndexVersion()
            val local = EhDB.getLocalFavoritesWithGallery().map { (_, gallery) -> gallery.toIndexed(NO_CLOUD_SLOT) }
            // Without an E-Hentai account the cloud favorites are unknown, the names of the slots too
            val cloud = if (signedIn) current() else CloudFavorites()
            FavoritesIndexMerger.buildIndex(local, cloud, deviceId, deviceName, now())
        }
        var index = build()
        if (signedIn) {
            if (!fromWork) {
                runEhStep { lookUpJapaneseTitles(index, TITLE_LOOKUPS_PER_SYNC, mutableProgress, StepProgress.TITLES) }
                index = build()
                // Many favorites without a Japanese title (the first sync of a large list): the work looks them up
                if (FavoritesIndexMerger.missingJapaneseTitles(index, limit = 1, current().defaultTitles).isNotEmpty()) enqueueCloudFavoritesWork(appCtx)
            }
            val listed = index
            // Titles of galleries the index no longer lists: nothing to upload
            update(markChanged = false) { cloud -> FavoritesIndexMerger.pruneTitles(cloud, listed) }
        }

        mutableProgress.value = StepProgress(R.string.onedrive_sync_step_upload_index, part = StepProgress.UPLOAD)
        for (attempt in 0 until MAX_ATTEMPTS) {
            val last = runCatching { readGzip(lastIndexFile, FavoritesIndex.serializer()) }.getOrNull()
            val storedETag = OneDriveSyncState.favoritesIndexETag.value
            if (remote != null && remote.eTag == storedETag && last != null && last.sameContent(index)) {
                logcat(TAG) { "Index unchanged" }
                return version
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
            return version
        }
        throw OneDrivePreconditionException()
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

    /**
     * Takes the cloud part of the index another device uploaded when it is newer than ours, and the titles it looked
     * up in any case: a device that already looked them up spares this one the work.
     */
    private suspend fun adoptRemote(remote: RemoteItem?) {
        if (remote == null || remote.eTag == OneDriveSyncState.favoritesIndexETag.value) return
        val download = File(syncDir, "favorites_index.download")
        try {
            if (!OneDriveClient.download(INDEX_PATH, download)) return
            val index = readGzip(download, FavoritesIndex.serializer())
            OneDriveSyncMerger.checkVersion(index.version)
            update { cloud -> FavoritesIndexMerger.withRemoteTitles(FavoritesIndexMerger.adopt(cloud, index), index) }
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
