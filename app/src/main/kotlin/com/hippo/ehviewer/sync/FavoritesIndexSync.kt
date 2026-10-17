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
import com.hippo.ehviewer.client.exception.NoHitsFoundException
import com.hippo.ehviewer.client.parser.FavParserResult
import java.io.File
import java.io.IOException
import java.lang.ref.SoftReference
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
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
import splitties.init.appCtx

/**
 * Keeps the list of E-Hentai cloud favorites and writes the sharded export `ehviewer/export/favorites/` (see
 * [FavoritesManifest]) at the end of every OneDrive sync.
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

    // Fork: the single file of format 1, replaced by the shards of SHARDS_FOLDER and deleted
    private const val LEGACY_INDEX_PATH = "$EXPORT_FOLDER/favorites-index.json.gz"
    private const val SHARDS_FOLDER = "$EXPORT_FOLDER/favorites"
    private const val MANIFEST_PATH = "$SHARDS_FOLDER/${FavoritesExportCache.MANIFEST_FILE}"
    private const val MAX_ATTEMPTS = 3

    /** A shard no manifest lists is deleted once it is this old, see [syncIndex]. */
    private val SHARD_GRACE = 1.hours
    private const val CHECK_INTERVAL_MILLIS = 60 * 60_000L

    // Fork: with auto download on, new favorites are looked for this often, see checkForAutoDownload
    private const val AUTO_DOWNLOAD_CHECK_INTERVAL_MILLIS = 10 * 60_000L
    private const val PAGE_INTERVAL_MILLIS = 1_000L

    /** Pages of "all" (the newest favorites first) a count check reads at most for favorites added on the website. */
    private const val CHECK_MAX_PAGES = 10

    /** Pages read between two saves of the refresh checkpoint. */
    private const val CHECKPOINT_PAGES = 5

    /** An unfinished refresh older than this starts again: its first pages are too old. */
    private const val CHECKPOINT_MAX_AGE_MILLIS = 3 * 24 * 60 * 60_000L

    /** Japanese titles a sync looks up itself (the favorites just added), the rest is left to [CloudFavoritesWork]. */
    private const val TITLE_LOOKUPS_PER_SYNC = 250

    // panda.chaika.moe is a small site: one search every 3 seconds
    private const val PANDA_INTERVAL_MILLIS = 3_000L
    private const val PANDA_MAX_PAGES = 10

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

    // Outside files/onedrive_sync: the cloud favorites belong to the E-Hentai account, not to the OneDrive one.
    // Fork: kept in shards, see CloudFavoritesStore; the single files of before are read once and deleted
    private val legacyStoreFile get() = File(appCtx.filesDir, "cloud_favorites.json.gz")
    private val store by lazy { CloudFavoritesStore(File(appCtx.filesDir, "cloud_favorites"), json) }
    private val syncDir get() = File(appCtx.filesDir, "onedrive_sync")
    private val legacyIndexFile get() = File(syncDir, "favorites_index.json.gz")
    private val exportCache get() = FavoritesExportCache(File(syncDir, "favorites_export"), json)
    private val legacyCheckpointFile get() = File(appCtx.filesDir, "cloud_favorites_refresh.json.gz")
    private val checkpointStore by lazy { CheckpointStore(File(appCtx.filesDir, "cloud_favorites_refresh"), json) }

    // Fork: the pages of each slot as last read, see SlotVerifier
    private val segmentStore by lazy { SegmentStore(File(appCtx.filesDir, "cloud_favorites_segments"), json) }

    /** The single file of format 1 was deleted from the OneDrive account of this process, see [onCloudStateReset]. */
    @Volatile
    private var legacyIndexRemoved = false

    /**
     * Fork: soft, so the cloud favorites stay in memory unless it runs short: a sync with a hundred thousand favorites
     * ran out of it. Saved on every change, they are read again from [store].
     */
    @Volatile
    private var cacheRef: SoftReference<CloudFavorites>? = null

    private var cache: CloudFavorites?
        get() = cacheRef?.get()
        set(value) {
            cacheRef = value?.let(::SoftReference)
        }

    /** [SystemClock.elapsedRealtime] of the last count check, 0 before the first one. */
    @Volatile
    private var lastCheck = 0L

    /** E-Hentai account of [lastCheck]: another account is checked right away. */
    @Volatile
    private var lastCheckUser: String? = null

    // A check of a sync and one of checkForAutoDownload at once would both read the pages
    private val checkMutex = Mutex()

    /** The unfinished refresh, see [CloudRefreshCheckpoint]. Guarded by [mutex], read once from [checkpointStore]. */
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

    private fun loadLocked(): CloudFavorites {
        cache?.let { return it }
        val stored = try {
            store.load()
        } catch (e: Throwable) {
            logcat(TAG, e)
            // Fork: a full memory, a failed read, or any failure once the list was read in this process (read again
            // after the soft cache was taken back) is no empty list: saved, it would replace the shards. Only an
            // unreadable store found at the first read starts over empty, as before
            if (e is Error || e is IOException || storeRead) throw e
            null
        }
        storeRead = true
        return (stored ?: loadLegacyStoreLocked() ?: CloudFavorites()).also { cache = it }
    }

    /** Fork: [store] was read in this process, see [loadLocked]. Guarded by [mutex]. */
    private var storeRead = false

    /** The single file of before the shards: read once, saved in shards and deleted. */
    private fun loadLegacyStoreLocked(): CloudFavorites? {
        if (!legacyStoreFile.exists()) return null
        val cloud = runCatching { readGzip(legacyStoreFile, CloudFavorites.serializer()) }
            .onFailure { logcat(TAG, it) }
            .getOrNull()
            ?.takeIf { it.version <= FAVORITES_INDEX_VERSION }
            ?: return null
        runCatching {
            store.save(cloud)
            legacyStoreFile.delete()
        }.onFailure { logcat(TAG, it) }
        return cloud
    }

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
        // Fork: false for what the website shows (found by checking segments), which pages read later may correct
        userEdit: Boolean = true,
        transform: (CloudFavorites) -> CloudFavorites,
    ): Boolean = withContext(Dispatchers.IO) {
        val changed = mutex.withLock {
            if (userKey() != user) return@withLock false
            val loaded = loadLocked()
            val before = FavoritesIndexMerger.forUser(loaded, user)
            val after = transform(before)
            // Pages of an unfinished refresh read before this change must not undo it
            val pending = loadCheckpointLocked()
            if (pending != null && pending.userKey == user && userEdit) {
                val touched = FavoritesIndexMerger.changedGids(before, after)
                if (!pending.touched.containsAll(touched) || intended.isNotEmpty()) {
                    saveCheckpointLocked(pending.copy(touched = pending.touched + touched, intended = pending.intended + intended))
                }
            }
            saveLocked(after, loaded).also { if (it && markChanged) markChangedLocked() }
        }
        changed
    }

    private fun loadCheckpointLocked(): CloudRefreshCheckpoint? {
        if (!checkpointLoaded) {
            checkpointLoaded = true
            checkpoint = runCatching { checkpointStore.load() ?: loadLegacyCheckpointLocked() }
                .onFailure { logcat(TAG, it) }
                .getOrNull()
                ?.takeIf { it.version <= FAVORITES_INDEX_VERSION }
        }
        return checkpoint
    }

    /** The single file of before the page files: read once, saved again and deleted. */
    private fun loadLegacyCheckpointLocked(): CloudRefreshCheckpoint? {
        if (!legacyCheckpointFile.exists()) return null
        val old = runCatching { readGzip(legacyCheckpointFile, CloudRefreshCheckpoint.serializer()) }.onFailure { logcat(TAG, it) }.getOrNull()
        checkpointStore.save(old)
        legacyCheckpointFile.delete()
        return old
    }

    private fun saveCheckpointLocked(new: CloudRefreshCheckpoint?) {
        checkpoint = new
        checkpointLoaded = true
        // Fork: only the galleries read since the last save are written, see CheckpointStore
        checkpointStore.save(new)
    }

    private fun CloudRefreshCheckpoint.usableFor(user: String?, now: Long) = userKey == user && now - startedAt in 0..CHECKPOINT_MAX_AGE_MILLIS

    /** A refresh of the cloud favorites of [user] has not read all pages yet. */
    private suspend fun hasUnfinishedRefresh(user: String?): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { loadCheckpointLocked()?.usableFor(user, now()) == true }
    }

    /**
     * [loaded]: the cloud favorites [new] was made from. Fork: passed on, not loaded again: the soft cache may be gone
     * by then, and reading it again held a second whole copy.
     */
    private fun saveLocked(new: CloudFavorites, loaded: CloudFavorites): Boolean {
        if (new == loaded) return false
        cache = new
        // Fork: only the shards the change touches are written, see CloudFavoritesStore
        store.save(new)
        AutoDownload.onCloudFavoritesSaved(new)
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

    /** The cloud favorites known for the signed in account, see [AutoDownload]. */
    suspend fun cloudFavorites(): CloudFavorites = current()

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
            lookUpPandaTitles(onBatch = uploadIfDue)
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
        var resumed = withContext(Dispatchers.IO) {
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
                }?.let { if (full && !it.full) it.copy(full = true) else it }
                checkpoint?.also { if (it != going) saveCheckpointLocked(it) }
            }
        } ?: return@withLock false
        // Fork: slots whose count changed are first checked by segments, from both ends; only those the segments
        // cannot explain are read page by page
        if (!resumed.full) {
            val verified = verifySlots(user, resumed, counts)
            if (userKey() != user) return@withLock false
            if (verified.isNotEmpty()) {
                val left = resumed.slots - verified
                val saved = withContext(NonCancellable + Dispatchers.IO) {
                    mutex.withLock {
                        val pending = loadCheckpointLocked()
                        if (pending == null || pending.userKey != user) return@withLock false
                        saveCheckpointLocked(if (left.isEmpty()) null else pending.copy(slots = pending.slots - verified))
                        true
                    }
                }
                if (!saved) return@withLock false
                if (left.isEmpty()) return@withLock true
                resumed = resumed.copy(slots = left)
            }
        }
        var slot = resumed.slot
        var next = resumed.next
        var read = resumed.galleries.size
        val slots = resumed.slots.toHashSet()
        val total = resumed.slots.sumOf { counts.getOrElse(it) { 0 } }
        val pages = ArrayList<IndexedGallery>()
        val marks = ArrayList<PageMark>()
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
            // Fork: where the page was read from, see SlotSegments
            marks += PageMark(readSlot, cursor, page.galleryInfoList.size)
            read += page.galleryInfoList.size
            mutableWorkProgress.value = StepProgress(R.string.onedrive_sync_step_read_cloud_favorites, read, total)
            next = page.next
            if (next == null) slot++
            pageCount++
            if (pageCount % CHECKPOINT_PAGES == 0 || next == null) {
                if (!saveRefreshPages(user, slot, next, pages, marks)) return@withLock false
                pages.clear()
                marks.clear()
                onSaved()
            }
        }
        completeRefresh(user, slotNames)
    }

    /**
     * Saves the position of the refresh and adds the galleries of its last [pages] to the known list, so the index
     * shows them before the refresh ends. False if the refresh no longer belongs to the signed in account.
     */
    private suspend fun saveRefreshPages(user: String?, slot: Int, next: String?, pages: List<IndexedGallery>, marks: List<PageMark>): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            val pending = loadCheckpointLocked()
            if (pending == null || pending.userKey != user || userKey() != user) return@withLock false
            saveCheckpointLocked(pending.copy(slot = slot, next = next, galleries = pending.galleries + pages, marks = pending.marks + marks))
            val loaded = loadLocked()
            if (saveLocked(FavoritesIndexMerger.withRefreshPages(FavoritesIndexMerger.forUser(loaded, user), pages, pending.touched, now(), pending.intended), loaded)) {
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
            val loaded = loadLocked()
            val cloud = FavoritesIndexMerger.forUser(loaded, user)
            val refreshed = FavoritesIndexMerger.completeRefresh(cloud, pending.galleries, pending.touched, slotNames, now(), pending.intended, pending.slots)
            // Fork: all slots read: shown as the last complete read, with what it found
            val done = if (pending.slots.containsAll(ALL_CLOUD_SLOTS)) {
                refreshed.copy(fullReadAt = refreshed.refreshedAt, fullReadCount = refreshed.galleries.size)
            } else {
                refreshed
            }
            if (saveLocked(done, loaded)) markChangedLocked()
            // Fork: the pages of the slots read, for the next change of their counts; an empty slot is one empty segment
            val read = SlotVerifier.segmentsOf(pending.marks, pending.galleries)
            val readBySlot = pending.galleries.groupingBy { it.slot }.eachCount()
            pending.slots.forEach { slot ->
                val segments = read[slot].orEmpty()
                runCatching {
                    when {
                        // A refresh started by an older version has no marks: the next one records them
                        segments.sumOf { it.gids.size } != (readBySlot[slot] ?: 0) -> segmentStore.delete(slot)
                        segments.isEmpty() -> segmentStore.save(SlotSegments(userKey = user, slot = slot, segments = listOf(Segment())))
                        else -> segmentStore.save(SlotSegments(userKey = user, slot = slot, segments = segments))
                    }
                }.onFailure { logcat(TAG, it) }
            }
            saveCheckpointLocked(null)
            logcat(TAG) { "Cloud favorites refreshed: slots ${pending.slots}, ${pending.galleries.size} galleries" }
            true
        }
    }

    /**
     * Fork: the slots of the refresh [checkpoint] not started yet whose changes their segments explain, see
     * [SlotVerifier]: the changes found are saved, these slots need no reading page by page. A slot without segments
     * (never read completely since they are kept) or whose segments no longer fit is left to the refresh.
     */
    private suspend fun verifySlots(user: String?, checkpoint: CloudRefreshCheckpoint, counts: List<Int>): Set<Int> {
        if (current().refreshedAt == 0L) return emptySet()
        val done = HashSet<Int>()
        for (slot in checkpoint.slots) {
            val started = slot < checkpoint.slot || slot == checkpoint.slot && checkpoint.next != null
            if (started) continue
            val stored = withContext(Dispatchers.IO) { runCatching { segmentStore.load(slot) }.onFailure { logcat(TAG, it) }.getOrNull() }
                ?.takeIf { it.userKey == user }
                ?: continue
            val known = current().galleries.filter { it.slot == slot }.mapTo(HashSet()) { it.gid }
            val difference = counts.getOrElse(slot) { 0 } - known.size
            if (difference == 0) {
                // Counts match again (changed back, or by the app meanwhile)
                done += slot
                continue
            }
            if (counts.getOrElse(slot) { 0 } == 0) {
                // Emptied on the website: no page to read
                update(user, userEdit = false) { cloud -> FavoritesIndexMerger.remove(cloud, known, now()) }
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { segmentStore.save(stored.copy(segments = listOf(Segment()))) }.onFailure { logcat(TAG, it) }
                }
                done += slot
                continue
            }
            var otherAccount = false
            val result = SlotVerifier.verify(
                segments = stored.segments,
                known = known,
                difference = difference,
                onChecked = { checked, total -> mutableWorkProgress.value = StepProgress(R.string.onedrive_sync_step_check_cloud_favorites, checked, total) },
            ) { after ->
                if (otherAccount) return@verify SlotVerifier.Page(emptyList(), null)
                delay(PAGE_INTERVAL_MILLIS)
                val url = FavListUrlBuilder(favCat = slot).apply { if (after != null) setIndex(after, isNext = true) }.build()
                // Nothing after the cursor (the galleries after it all removed): an empty last page
                val page = try {
                    EhEngine.getFavoritesPage(url)
                } catch (_: NoHitsFoundException) {
                    return@verify SlotVerifier.Page(emptyList(), null)
                }
                otherAccount = userKey() != user
                SlotVerifier.Page(page.galleryInfoList.map { it.toIndexed(slot) }, page.next)
            }
            if (otherAccount || userKey() != user) return emptySet()
            if (result == null) {
                logcat(TAG) { "Cloud favorites: slot $slot does not fit its segments, read page by page" }
                continue
            }
            update(user, userEdit = false) { cloud ->
                val gone = cloud.galleries.filter { it.gid in result.removed && it.slot == slot }.map { it.gid }
                val time = now()
                FavoritesIndexMerger.upsert(FavoritesIndexMerger.remove(cloud, gone, time), result.added, time)
            }
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { segmentStore.save(stored.copy(segments = result.segments)) }.onFailure { logcat(TAG, it) }
            }
            logcat(TAG) {
                "Cloud favorites: slot $slot checked in ${result.checked} of ${stored.segments.size} segments, " +
                    "${result.added.size} added, ${result.removed.size} removed"
            }
            done += slot
        }
        return done
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
    private suspend fun checkCloudFavorites() = checkMutex.withLock { checkCloudFavoritesLocked() }

    private suspend fun checkCloudFavoritesLocked() {
        val user = userKey()
        if (hasUnfinishedRefresh(user)) {
            enqueueCloudFavoritesWork(appCtx)
            return
        }
        val elapsed = SystemClock.elapsedRealtime()
        val interval = if (AutoDownload.isOn) AUTO_DOWNLOAD_CHECK_INTERVAL_MILLIS else CHECK_INTERVAL_MILLIS
        if (lastCheck != 0L && lastCheckUser == user && elapsed - lastCheck < interval) return
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

    /**
     * Fork: with auto download on, looks for new favorites (one page request, at most every ten minutes) when the app
     * comes to the front, without waiting for a sync: they used to be found only by the check of a sync (at most once
     * an hour) or by opening their slot. A change found schedules a sync of the index like any other.
     */
    suspend fun checkForAutoDownload() {
        if (!Settings.hasSignedIn.value || !AutoDownload.isOn) return
        val before = current()
        runEhStep { checkCloudFavorites() }
        // Left for the next step when a sync runs
        if (!OneDriveSync.running.value) mutableProgress.value = null
        if (current() != before && enabled) OneDriveSync.scheduleFavoritesIndexSync()
    }

    /** Reads the cloud favorites and looks up every missing Japanese title again in the background. */
    fun refreshInBackground() = enqueueCloudFavoritesWork(appCtx, full = true)

    /** How far the cloud favorites are read ([read] of [total] when a refresh is [unfinished]) and looked up. */
    data class Status(
        val unfinished: Boolean = false,
        val read: Int = 0,
        val total: Int = 0,
        val missingTitles: Int = 0,
        // Fork: the cloud favorites were read before (completely or not), and the last read of all slots (0: none
        // since it is recorded) with the favorites it found
        val readBefore: Boolean = false,
        val fullReadAt: Long = 0,
        val fullReadCount: Int = 0,
    )

    suspend fun status(): Status = withContext(Dispatchers.IO) {
        if (!Settings.hasSignedIn.value) return@withContext Status()
        val user = userKey()
        val pending = mutex.withLock { loadCheckpointLocked()?.takeIf { it.userKey == user } }
        val missing = FavoritesIndexMerger.missingJapaneseTitles(currentIndex(), Int.MAX_VALUE, current().defaultTitles).size
        val cloud = current()
        Status(
            readBefore = cloud.refreshedAt > 0L,
            fullReadAt = cloud.fullReadAt,
            fullReadCount = cloud.fullReadCount,
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

    /**
     * Default titles of the galleries the API has none for (removed), from the raw copies panda.chaika.moe archived:
     * few, one search each. The site unreachable, the rest waits for the next work.
     */
    private suspend fun lookUpPandaTitles(onBatch: suspend () -> Unit) {
        val missing = FavoritesIndexMerger.missingPandaTitles(currentIndex(), current())
        for ((i, gallery) in missing.withIndex()) {
            if (i != 0) delay(PANDA_INTERVAL_MILLIS)
            mutableWorkProgress.value = StepProgress(R.string.onedrive_sync_step_panda_titles, i, missing.size)
            val query = FavoritesIndexMerger.pandaQuery(gallery) ?: continue
            // The match may be on a later page of a common title: a miss is kept only after the last page (or many)
            var title = ""
            var page = 1
            while (true) {
                val result = try {
                    PandaChaika.search(query, page)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat(TAG, e)
                    return
                }
                title = FavoritesIndexMerger.pandaDefaultTitle(gallery, result.archives.map { it.title to it.titleJpn })
                if (title.isNotEmpty() || !result.hasNext || page >= PANDA_MAX_PAGES) break
                page++
                delay(PANDA_INTERVAL_MILLIS)
            }
            update { cloud -> FavoritesIndexMerger.withPandaTitles(cloud, mapOf(gallery.gid to title)) }
            onBatch()
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
        // Fork: the manifest and the shards are listed at once, shards already there are not uploaded again
        var listing = adoptLatest(OneDriveClient.list(SHARDS_FOLDER))
        var remote = listing[FavoritesExportCache.MANIFEST_FILE]
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
                val cloud = current()
                if (FavoritesIndexMerger.missingJapaneseTitles(index, limit = 1, cloud.defaultTitles).isNotEmpty() ||
                    FavoritesIndexMerger.missingPandaTitles(index, cloud).isNotEmpty()
                ) {
                    enqueueCloudFavoritesWork(appCtx)
                }
            }
            val listed = index
            // Titles of galleries the index no longer lists: nothing to upload
            update(markChanged = false) { cloud -> FavoritesIndexMerger.pruneTitles(cloud, listed) }
        }

        mutableProgress.value = StepProgress(R.string.onedrive_sync_step_upload_index, part = StepProgress.UPLOAD)
        val cache = exportCache
        for (attempt in 0 until MAX_ATTEMPTS) {
            val last = withContext(Dispatchers.IO) { cache.manifest() }
            val built = index
            val manifest = withContext(Dispatchers.IO) { cache.build(built) }
            val storedETag = OneDriveSyncState.favoritesIndexETag.value
            val uploaded = listing.keys.mapNotNullTo(HashSet()) { FavoritesExportCache.hashOf(it) }
            if (remote != null && remote.eTag == storedETag && last != null && last.sameContent(manifest) && manifest.shards.all { it.hash in uploaded }) {
                logcat(TAG) { "Index unchanged" }
                removeLegacyIndex()
                return version
            }
            // Shards first: whoever reads the new manifest finds all of its shards. A shard is named by its content, one
            // already there (written by another device too) is the same file
            val missing = manifest.shards.filter { it.hash !in uploaded }
            for ((i, shard) in missing.withIndex()) {
                mutableProgress.value = StepProgress(R.string.onedrive_sync_step_upload_index, i, missing.size, StepProgress.UPLOAD)
                try {
                    OneDriveClient.upload("$SHARDS_FOLDER/${GidShards.fileName(FavoritesExportCache.SHARD_PREFIX, shard.hash)}", cache.shardFile(shard.hash), eTag = null)
                } catch (_: OneDrivePreconditionException) {
                    // Uploaded meanwhile
                }
            }
            val output = File(syncDir, "favorites_manifest.upload")
            output.writeText(json.encodeToString(FavoritesManifest.serializer(), manifest))
            val eTag = try {
                OneDriveClient.upload(MANIFEST_PATH, output, remote?.eTag)
            } catch (e: OneDrivePreconditionException) {
                if (attempt == MAX_ATTEMPTS - 1) throw e
                // Another device wrote its index meanwhile: take its newer cloud part, then write again
                listing = adoptLatest(OneDriveClient.list(SHARDS_FOLDER))
                remote = listing[FavoritesExportCache.MANIFEST_FILE]
                index = build()
                continue
            } finally {
                output.delete()
            }
            withContext(Dispatchers.IO) { cache.saveManifest(manifest) }
            OneDriveSyncState.withMutableSnapshot {
                it[OneDriveSyncState.favoritesIndexETag] = eTag
                it[OneDriveSyncState.favoritesIndexRemoved] = false
            }
            // Shards neither this manifest nor the one it replaced lists: a reader of that one may still be downloading
            // its shards, they go with the next upload. Only shards unchanged for a while: another device syncing at
            // the same time may have just uploaded one for its manifest to come. A shard a manifest lists that is gone
            // all the same is uploaded again by the next sync (the check above sees it missing)
            val keep = manifest.shards.mapTo(HashSet()) { it.hash }
            last?.shards?.mapTo(keep) { it.hash }
            val oldBefore = Clock.System.now() - SHARD_GRACE
            listing.values.forEach { item ->
                val hash = FavoritesExportCache.hashOf(item.name) ?: return@forEach
                val modified = item.lastModified?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@forEach
                if (hash !in keep && modified < oldBefore) {
                    runCatching { OneDriveClient.delete("$SHARDS_FOLDER/${item.name}") }.onFailure { logcat(TAG, it) }
                }
            }
            withContext(Dispatchers.IO) { cache.cleanUp(manifest.shards.mapTo(HashSet()) { it.hash }) }
            // Only now: until the manifest is up, readers of format 1 keep the single file
            removeLegacyIndex()
            logcat(TAG) { "Index uploaded: ${manifest.count} galleries, ${missing.size} of ${manifest.shards.size} shards" }
            return version
        }
        throw OneDrivePreconditionException()
    }

    /**
     * The single file of format 1 is replaced by the shards: deleted once per account and run of the app (gone, it costs
     * nothing). A failure leaves it to the next upload, the shards are up already.
     */
    private suspend fun removeLegacyIndex() {
        if (legacyIndexRemoved) return
        try {
            OneDriveClient.delete(LEGACY_INDEX_PATH)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(TAG, e)
            return
        }
        withContext(Dispatchers.IO) { legacyIndexFile.delete() }
        legacyIndexRemoved = true
    }

    /**
     * Sharing was turned off: delete the index, so other apps stop seeing the favorites. Done once after turning it
     * off, also when this device never uploaded one (a reinstall, another OneDrive account that already has one).
     */
    private suspend fun removeExport() {
        if (OneDriveSyncState.favoritesIndexRemoved.value) return
        OneDriveClient.delete(SHARDS_FOLDER)
        OneDriveClient.delete(LEGACY_INDEX_PATH)
        withContext(Dispatchers.IO) {
            exportCache.delete()
            legacyIndexFile.delete()
        }
        OneDriveSyncState.withMutableSnapshot {
            it[OneDriveSyncState.favoritesIndexETag] = null
            it[OneDriveSyncState.favoritesIndexRemoved] = true
        }
        logcat(TAG) { "Index deleted" }
    }

    /**
     * [adoptRemote] with the manifest of [listing]. Fork: when it cannot be read whole, the folder is listed again: a
     * newer manifest is read (its upload replaced shards of the one listed), the same one is missing shards for good
     * (deleted by hand) and nobody can read it, it is written over. Returns the listing the upload goes on with.
     */
    private suspend fun adoptLatest(listing: Map<String, RemoteItem>): Map<String, RemoteItem> {
        val listed = listing[FavoritesExportCache.MANIFEST_FILE]
        if (adoptRemote(listed)) return listing
        val again = OneDriveClient.list(SHARDS_FOLDER)
        val now = again[FavoritesExportCache.MANIFEST_FILE]
        if (now != null && now.eTag == listed?.eTag) {
            logcat(TAG) { "Index on OneDrive incomplete, written again" }
            return again
        }
        // Never written over with what this device knows while another device is writing
        if (!adoptRemote(now)) throw IOException("The favorites index on OneDrive changed while it was read")
        return again
    }

    /**
     * Takes the cloud part of the index another device uploaded when it is newer than ours, and the titles it looked
     * up in any case: a device that already looked them up spares this one the work. Fork: only the shards not in the
     * cache are downloaded. False when it could not be read whole: gone meanwhile, a shard missing, invalid.
     */
    private suspend fun adoptRemote(remote: RemoteItem?): Boolean {
        if (remote == null || remote.eTag == OneDriveSyncState.favoritesIndexETag.value) return true
        val cache = exportCache
        val download = File(syncDir, "favorites_manifest.download")
        try {
            if (!OneDriveClient.download(MANIFEST_PATH, download)) return false
            val manifest = json.decodeFromString(FavoritesManifest.serializer(), download.readText())
            if (manifest.version > FAVORITES_EXPORT_VERSION) throw SyncVersionException(manifest.version)
            // The hashes name files here: a manifest with anything else is not taken
            if (!manifest.shards.all { FavoritesExportCache.isValidHash(it.hash) }) {
                logcat(TAG) { "Index with invalid shard names" }
                return false
            }
            for (shard in manifest.shards) {
                val file = cache.shardFile(shard.hash)
                if (file.exists()) continue
                val temp = File(file.parentFile, "${file.name}.download")
                if (!OneDriveClient.download("$SHARDS_FOLDER/${file.name}", temp)) {
                    temp.delete()
                    logcat(TAG) { "Index shard ${shard.hash} missing" }
                    return false
                }
                if (!temp.renameTo(file)) {
                    temp.copyTo(file, overwrite = true)
                    temp.delete()
                }
            }
            val index = withContext(Dispatchers.IO) { cache.index(manifest) }
            update { cloud -> FavoritesIndexMerger.withRemoteTitles(FavoritesIndexMerger.adopt(cloud, index), index) }
            // Its shard ranges are kept by the next write
            withContext(Dispatchers.IO) { cache.saveManifest(manifest) }
            return true
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
        // The other account may hold a single file of format 1 too
        legacyIndexRemoved = false
    }

    // endregion
}
