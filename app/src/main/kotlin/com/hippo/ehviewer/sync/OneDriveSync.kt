package com.hippo.ehviewer.sync

import android.app.Activity
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.ehviewer.core.database.model.DownloadArtist
import com.ehviewer.core.database.model.DownloadEntity
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.spider.downloadDirname
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import splitties.init.appCtx
import splitties.systemservices.connectivityManager

enum class SyncTrigger {
    MANUAL,
    LAUNCH,
    CHANGE,
    BACKGROUND,
}

enum class SyncAction {
    SKIPPED,
    UPLOADED,
    MERGED,
}

data class CategoryResult(val category: SyncCategory, val action: SyncAction, val counts: SyncCounts)

data class SyncReport(
    val categories: List<CategoryResult>,
    val imported: Int,
    val devicesError: Throwable?,
    val indexError: Throwable? = null,
)

data class RemoteDevice(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val downloadCount: Int,
    val subscribed: Boolean,
)

data class DownloadImportResult(val imported: Int, val skipped: Int)

/**
 * OneDrive sync engine. All the shared categories live in `ehviewer/v1/` of the OneDrive app folder, one
 * gzip JSON file each with its own ETag, the download list of every device in `ehviewer/devices/`.
 *
 * Every sync runs under [mutex] on the IO dispatcher. A sync first lists `ehviewer/v1` once to get all
 * ETags, then per category either skips it (nothing changed anywhere), merges against the cached cloud
 * copy (only this device changed) or downloads the new cloud copy first. The cached copy of the last
 * synced state doubles as the baseline of the three-way merge.
 */
object OneDriveSync {
    private const val TAG = "OneDriveSync"
    private const val ROOT_FOLDER = "ehviewer"
    private const val SHARED_FOLDER = "$ROOT_FOLDER/v1"
    private const val DEVICES_FOLDER = "$ROOT_FOLDER/devices"
    private const val DEVICE_FILE_SUFFIX = ".json.gz"
    private const val MAX_ATTEMPTS = 4 // First try and 3 retries after a precondition failure
    private const val DEBOUNCE_MILLIS = 60_000L
    private const val MAX_WAIT_MILLIS = 5 * 60_000L
    private const val CHANGED_AT_PERSIST_INTERVAL = 60_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val syncDir get() = File(appCtx.filesDir, "onedrive_sync")
    private val cacheDir get() = File(syncDir, "cache")
    private val deviceCacheDir get() = File(syncDir, "devices")
    private val ownDeviceCache get() = File(syncDir, "own_device.json.gz")

    /** Marks writes done by the sync itself, they must not count as local changes. */
    private class SyncWrite : AbstractCoroutineContextElement(SyncWrite) {
        companion object Key : CoroutineContext.Key<SyncWrite>
    }

    // region Local change tracking

    private val dirtyVersions = SyncCategory.entries.associateWith { AtomicLong() }
    private val downloadsVersion = AtomicLong()
    private val changedAt = ConcurrentHashMap<SyncCategory, Long>()
    private val persistedChangedAt = ConcurrentHashMap<SyncCategory, Long>()

    /** Setting values written by the sync, so the change listener does not take them for user changes. */
    private val expectedSettings = ConcurrentHashMap<String, Any>()
    private val removedSetting = Any()
    private val settingsTimeMutex = Mutex()

    private fun now() = Clock.System.now().toEpochMilliseconds()

    /** Called by EhDB writes of synced data. */
    suspend fun notifyChanged(category: SyncCategory) {
        if (currentCoroutineContext()[SyncWrite] != null) return
        markDirty(category)
        scheduleChangeSync()
    }

    /** Called when the cloud favorites known to [FavoritesIndexSync] changed. */
    fun notifyFavoritesIndexChanged() {
        scheduleChangeSync()
    }

    /** Called by EhDB writes of the download list. */
    suspend fun notifyDownloadsChanged() {
        if (currentCoroutineContext()[SyncWrite] != null) return
        downloadsVersion.incrementAndGet()
        if (!OneDriveSyncState.downloadsDirty.value) {
            OneDriveSyncState.withMutableSnapshot { it[OneDriveSyncState.downloadsDirty] = true }
        }
        if (OneDriveSyncState.uploadDownloads.value) scheduleChangeSync()
    }

    private suspend fun markDirty(category: SyncCategory) {
        dirtyVersions.getValue(category).incrementAndGet()
        val time = now()
        changedAt[category] = time
        val persisted = persistedChangedAt[category] ?: 0
        if (!OneDriveSyncState.dirty(category).value || time - persisted > CHANGED_AT_PERSIST_INTERVAL) {
            persistedChangedAt[category] = time
            OneDriveSyncState.withMutableSnapshot {
                it[OneDriveSyncState.dirty(category)] = true
                it[OneDriveSyncState.changedAt(category)] = time
            }
        }
    }

    private fun localChangedAt(category: SyncCategory) = changedAt[category] ?: OneDriveSyncState.changedAt(category).value.takeIf { it > 0 } ?: now()

    private fun valueEquals(a: Any?, b: Any?) = if (a is ByteArray && b is ByteArray) a.contentEquals(b) else a == b

    /** Records the time of every synced preference the user changes. */
    private fun trackSettings() = scope.launch {
        var previous: Map<String, Any>? = null
        Settings.rawData.collect { preferences ->
            val current = preferences.asMap().mapKeys { it.key.name }
            val old = previous
            previous = current
            if (old == null) return@collect
            val changed = (old.keys + current.keys).filter { key ->
                OneDriveSyncMerger.isSyncableSetting(key) && !valueEquals(old[key], current[key])
            }.filterNot { key ->
                val expected = expectedSettings[key] ?: return@filterNot false
                val value = current[key] ?: removedSetting
                valueEquals(expected, value).also { if (it) expectedSettings.remove(key) }
            }
            if (changed.isNotEmpty()) {
                val time = now()
                settingsTimeMutex.withLock {
                    val times = OneDriveSyncState.readLongMap(OneDriveSyncState.settingsModifiedAt) + changed.associateWith { time }
                    OneDriveSyncState.withMutableSnapshot { it[OneDriveSyncState.settingsModifiedAt] = OneDriveSyncState.encodeLongMap(times) }
                }
                markDirty(SyncCategory.SETTINGS)
                scheduleChangeSync()
            }
        }
    }

    /** Writes synced settings to the app DataStore and keeps their time as the local modification time. */
    suspend fun applySettings(settings: Map<String, SyncSetting>) {
        val writes = settings.mapNotNull { (key, setting) -> setting.toPreference(key)?.let { Triple(key, it, setting) } }
        writes.forEach { (key, preference, _) -> expectedSettings[key] = preference.second ?: removedSetting }
        Settings.editRaw { prefs ->
            writes.forEach { (_, preference, _) ->
                @Suppress("UNCHECKED_CAST")
                val prefKey = preference.first as Preferences.Key<Any>
                val value = preference.second
                if (value == null) {
                    prefs.remove(prefKey)
                } else {
                    prefs[prefKey] = value
                }
            }
        }
        settingsTimeMutex.withLock {
            val times = OneDriveSyncState.readLongMap(OneDriveSyncState.settingsModifiedAt) + writes.associate { (key, _, setting) -> key to setting.modifiedAt }
            OneDriveSyncState.withMutableSnapshot { it[OneDriveSyncState.settingsModifiedAt] = OneDriveSyncState.encodeLongMap(times) }
        }
    }

    // endregion

    // region Scheduling

    private val debounceLock = Any()
    private var debounceJob: Job? = null
    private var batchStartedAt = 0L

    @Volatile
    private var pendingAfterSync = false

    @Volatile
    private var inForeground = false

    private var launchSyncDone = false

    /** Called once from the Application. */
    fun init() {
        if (!OneDriveClient.isSupported) return
        trackSettings()
        scope.launch {
            ensureDeviceId()
            updateOneDriveSyncWork(appCtx)
            merge(
                OneDriveSyncState.accountId.changesFlow(),
                OneDriveSyncState.autoSync.changesFlow(),
                OneDriveSyncState.autoSyncIntervalMinutes.changesFlow(),
                OneDriveSyncState.wifiOnly.changesFlow(),
            ).collect { updateOneDriveSyncWork(appCtx) }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        inForeground = true
                        if (!launchSyncDone) {
                            launchSyncDone = true
                            scope.launch { syncOnLaunch() }
                        }
                    }
                    Lifecycle.Event.ON_STOP -> {
                        inForeground = false
                        handOffPendingChanges()
                    }
                    else -> Unit
                }
            },
        )
    }

    private val autoSyncAllowed: Boolean
        get() = OneDriveClient.isSupported && OneDriveSyncState.isSignedIn && OneDriveSyncState.autoSync.value

    fun isNetworkAllowed(wifiOnly: Boolean = OneDriveSyncState.wifiOnly.value): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !wifiOnly || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private suspend fun syncOnLaunch() {
        if (!autoSyncAllowed) return
        if (isNetworkAllowed()) sync(SyncTrigger.LAUNCH) else enqueueOneDriveCatchUpWork(appCtx)
    }

    /**
     * Debounce of change triggered syncs: every change restarts a 60 s countdown, a batch waits at most 5 min
     * from its first change, and only one sync runs at a time (a change during a sync is synced after it).
     */
    private fun scheduleChangeSync() {
        if (!autoSyncAllowed || !OneDriveSyncState.syncOnChange.value) return
        synchronized(debounceLock) {
            if (mutex.isLocked) {
                pendingAfterSync = true
                return
            }
            if (!inForeground) {
                enqueueOneDriveCatchUpWork(appCtx)
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (batchStartedAt == 0L) batchStartedAt = now
            val wait = minOf(DEBOUNCE_MILLIS, batchStartedAt + MAX_WAIT_MILLIS - now).coerceAtLeast(0)
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(wait)
                synchronized(debounceLock) {
                    debounceJob = null
                    batchStartedAt = 0L
                }
                // Not a child of the countdown, so a later change cannot cancel the running sync
                scope.launch {
                    if (isNetworkAllowed()) sync(SyncTrigger.CHANGE) else enqueueOneDriveCatchUpWork(appCtx)
                }
            }
        }
    }

    /** The app went to the background: let WorkManager sync the changes the countdown has not synced yet. */
    private fun handOffPendingChanges() {
        val pending = synchronized(debounceLock) {
            val pending = debounceJob != null || pendingAfterSync
            debounceJob?.cancel()
            debounceJob = null
            batchStartedAt = 0L
            pendingAfterSync = false
            pending
        }
        if (pending && autoSyncAllowed) enqueueOneDriveCatchUpWork(appCtx)
    }

    // endregion

    // region Account and device

    suspend fun ensureDeviceId(): String {
        OneDriveSyncState.deviceId.value?.let { return it }
        val id = UUID.randomUUID().toString()
        OneDriveSyncState.withMutableSnapshot {
            it[OneDriveSyncState.deviceId] = id
            if (it[OneDriveSyncState.deviceName] == null) it[OneDriveSyncState.deviceName] = defaultDeviceName()
        }
        return id
    }

    fun defaultDeviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
        .joinToString(" ")
        .ifBlank { "Android" }

    val deviceName: String
        get() = OneDriveSyncState.deviceName.value ?: defaultDeviceName()

    suspend fun setDeviceName(name: String) {
        OneDriveSyncState.withMutableSnapshot { it[OneDriveSyncState.deviceName] = name.trim().ifBlank { defaultDeviceName() } }
        notifyDownloadsChanged()
    }

    suspend fun signIn(activity: Activity): OneDriveAccount {
        val account = OneDriveClient.signIn(activity)
        updateAccount(account)
        return account
    }

    suspend fun signOut() = mutex.withLock {
        OneDriveClient.signOut()
        OneDriveSyncState.withMutableSnapshot {
            it[OneDriveSyncState.accountId] = null
            it[OneDriveSyncState.accountName] = null
        }
    }

    /** A different account has different cloud data: forget the baseline, keep the device id. */
    private suspend fun updateAccount(account: OneDriveAccount) {
        val previous = OneDriveSyncState.accountId.value
        if (previous != null && previous != account.id) resetCloudState()
        OneDriveSyncState.withMutableSnapshot {
            it[OneDriveSyncState.accountId] = account.id
            it[OneDriveSyncState.accountName] = account.name
        }
    }

    private suspend fun resetCloudState() {
        withContext(Dispatchers.IO) {
            syncDir.deleteRecursively()
        }
        OneDriveSyncState.withMutableSnapshot {
            SyncCategory.entries.forEach { category -> it[OneDriveSyncState.eTag(category)] = null }
            it[OneDriveSyncState.deviceFileETag] = null
            it[OneDriveSyncState.remoteDeviceETags] = "{}"
            it[OneDriveSyncState.subscriptions] = "{}"
            it[OneDriveSyncState.downloadsDirty] = true
            it[OneDriveSyncState.favoritesIndexETag] = null
            // Another account may already hold an index to delete
            it[OneDriveSyncState.favoritesIndexRemoved] = false
        }
        FavoritesIndexSync.onCloudStateReset()
    }

    // endregion

    // region Sync

    suspend fun sync(trigger: SyncTrigger): Result<SyncReport> = withContext(Dispatchers.IO) {
        val result: Result<SyncReport> = mutex.withLock {
            mutableRunning.value = true
            try {
                val start = SystemClock.elapsedRealtime()
                val report = withContext(SyncWrite()) { doSync() }
                logcat(TAG) { "Sync ($trigger) finished in ${SystemClock.elapsedRealtime() - start} ms" }
                Result.success(report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(TAG, e)
                Result.failure<SyncReport>(e)
            } finally {
                mutableRunning.value = false
            }
        }
        result.onSuccess { report ->
            OneDriveSyncState.withMutableSnapshot {
                it[OneDriveSyncState.lastSyncTime] = now()
                it[OneDriveSyncState.lastSyncFailed] = report.devicesError != null || report.indexError != null
                it[OneDriveSyncState.lastSyncSummary] = summarize(report)
            }
        }.onFailure { e ->
            OneDriveSyncState.withMutableSnapshot {
                it[OneDriveSyncState.lastSyncTime] = now()
                it[OneDriveSyncState.lastSyncFailed] = true
                it[OneDriveSyncState.lastSyncSummary] = e.message ?: e.toString()
            }
        }
        val rerun = synchronized(debounceLock) {
            pendingAfterSync.also { pendingAfterSync = false }
        }
        if (rerun) scheduleChangeSync()
        result
    }

    private suspend fun doSync(): SyncReport {
        val account = OneDriveClient.currentAccount() ?: throw OneDriveAuthException(appCtx.getString(R.string.onedrive_sync_not_signed_in))
        updateAccount(account)
        val deviceId = ensureDeviceId()
        val listing = OneDriveClient.list(SHARED_FOLDER)
        val results = SyncSection.all.filter { OneDriveSyncState.isEnabled(it.category) }.map { section ->
            syncSection(section, listing[section.category.fileName], deviceId)
        }
        var imported = 0
        val devicesError = runCatching { imported = syncDevices(deviceId) }.exceptionOrNull()
        if (devicesError is CancellationException) throw devicesError
        devicesError?.let { logcat(TAG, it) }
        // Last: it reads the local favorites the favorites step just merged
        val indexError = runCatching { FavoritesIndexSync.sync(deviceId, deviceName) }.exceptionOrNull()
        if (indexError is CancellationException) throw indexError
        indexError?.let { logcat(TAG, it) }
        return SyncReport(results, imported, devicesError, indexError)
    }

    private suspend fun <T> syncSection(section: SyncSection<T>, remote: RemoteItem?, deviceId: String): CategoryResult {
        val category = section.category
        val start = SystemClock.elapsedRealtime()
        val fileName = category.fileName
        val cacheFile = File(cacheDir, fileName)
        val cachedETag = OneDriveSyncState.eTag(category).value
        val version = dirtyVersions.getValue(category).get()
        val dirty = OneDriveSyncState.dirty(category).value
        val hasCache = cachedETag != null && cacheFile.exists()

        if (remote != null && hasCache && remote.eTag == cachedETag && !dirty) {
            logcat(TAG) { "$category: skipped (${SystemClock.elapsedRealtime() - start} ms)" }
            return CategoryResult(category, SyncAction.SKIPPED, SyncCounts())
        }

        var item = remote
        var action = SyncAction.UPLOADED
        for (attempt in 0 until MAX_ATTEMPTS) {
            val current = item
            // A file missing in the cloud (first sync, or cloud data cleared) is merged without baseline,
            // so nothing is deleted locally because of it.
            val baseline = if (current != null && hasCache) readSectionOrNull(cacheFile, section.serializer)?.items else null
            val cloud: T = when {
                current == null -> section.empty
                current.eTag == cachedETag && baseline != null -> baseline
                else -> {
                    action = SyncAction.MERGED
                    val download = File(syncDir, "$fileName.download")
                    if (!OneDriveClient.download("$SHARED_FOLDER/$fileName", download)) {
                        // Deleted since the listing: handle it as a missing file, never as "everything deleted"
                        item = null
                        continue
                    }
                    readSection(download, section.serializer).items.also { download.delete() }
                }
            }
            val local = section.collect(baseline, localChangedAt(category))
            val merged = section.merge(local, cloud, baseline)
            val output = File(syncDir, "$fileName.upload")
            writeSection(output, section.serializer, SectionFile(updatedAt = now(), deviceId = deviceId, items = merged))
            val newETag = if (merged != cloud || current == null) {
                try {
                    OneDriveClient.upload("$SHARED_FOLDER/$fileName", output, current?.eTag)
                } catch (e: OneDrivePreconditionException) {
                    if (attempt == MAX_ATTEMPTS - 1) throw e
                    logcat(TAG) { "$category: changed on another device during the sync, retrying" }
                    item = OneDriveClient.list(SHARED_FOLDER)[fileName]
                    action = SyncAction.MERGED
                    continue
                }
            } else {
                current.eTag
            }
            // Only after the cloud has the merged copy: write the difference back
            val counts = section.apply(local, merged)
            cacheFile.parentFile?.mkdirs()
            if (!output.renameTo(cacheFile)) {
                output.copyTo(cacheFile, overwrite = true)
                output.delete()
            }
            val clean = dirtyVersions.getValue(category).get() == version
            OneDriveSyncState.withMutableSnapshot {
                it[OneDriveSyncState.eTag(category)] = newETag
                if (clean) it[OneDriveSyncState.dirty(category)] = false
            }
            if (clean) changedAt.remove(category)
            logcat(TAG) {
                "$category: ${if (action == SyncAction.MERGED) "downloaded and merged" else "uploaded only"}, " +
                    "+${counts.added} ~${counts.updated} -${counts.removed} (${SystemClock.elapsedRealtime() - start} ms)"
            }
            return CategoryResult(category, action, counts)
        }
        throw OneDrivePreconditionException()
    }

    private fun <T> readSection(file: File, serializer: KSerializer<T>): SectionFile<T> {
        val section = GZIPInputStream(file.inputStream().buffered()).use { json.decodeFromStream(SectionFile.serializer(serializer), it) }
        OneDriveSyncMerger.checkVersion(section.version)
        return section
    }

    private fun <T> readSectionOrNull(file: File, serializer: KSerializer<T>): SectionFile<T>? = runCatching { readSection(file, serializer) }.getOrNull()

    private fun <T> writeSection(file: File, serializer: KSerializer<T>, value: SectionFile<T>) {
        file.parentFile?.mkdirs()
        GZIPOutputStream(file.outputStream().buffered()).use { json.encodeToStream(SectionFile.serializer(serializer), value, it) }
    }

    private fun readDevice(file: File): DeviceDownloads {
        val device = GZIPInputStream(file.inputStream().buffered()).use { json.decodeFromStream(DeviceDownloads.serializer(), it) }
        OneDriveSyncMerger.checkVersion(device.version)
        return device
    }

    private fun writeDevice(file: File, device: DeviceDownloads) {
        file.parentFile?.mkdirs()
        GZIPOutputStream(file.outputStream().buffered()).use { json.encodeToStream(DeviceDownloads.serializer(), device, it) }
    }

    private fun summarize(report: SyncReport): String {
        val lines = report.categories.filterNot { it.counts.isEmpty }.map { result ->
            appCtx.getString(
                R.string.onedrive_sync_category_counts,
                appCtx.getString(categoryTitle(result.category)),
                result.counts.added,
                result.counts.updated,
                result.counts.removed,
            )
        }.toMutableList()
        if (report.imported > 0) lines += appCtx.getString(R.string.onedrive_sync_downloads_imported, report.imported)
        report.devicesError?.let { lines += appCtx.getString(R.string.onedrive_sync_devices_failed, it.message ?: it.toString()) }
        report.indexError?.let { lines += appCtx.getString(R.string.onedrive_sync_favorites_index_failed, it.message ?: it.toString()) }
        return lines.joinToString("\n").ifEmpty { appCtx.getString(R.string.onedrive_sync_no_changes) }
    }

    fun summaryOf(result: Result<SyncReport>): String = result.fold(::summarize) { it.message ?: it.toString() }

    fun categoryTitle(category: SyncCategory) = when (category) {
        SyncCategory.SETTINGS -> R.string.onedrive_sync_category_settings
        SyncCategory.FAVORITES -> R.string.onedrive_sync_category_favorites
        SyncCategory.HISTORY -> R.string.onedrive_sync_category_history
        SyncCategory.PROGRESS -> R.string.onedrive_sync_category_progress
        SyncCategory.QUICK_SEARCH -> R.string.onedrive_sync_category_quick_search
        SyncCategory.SUGGESTIONS -> R.string.onedrive_sync_category_suggestions
    }

    // endregion

    // region Devices

    /** Uploads the download list of this device and imports new downloads of subscribed devices. */
    private suspend fun syncDevices(deviceId: String): Int {
        val subscriptions = OneDriveSyncState.readSubscriptions()
        val upload = OneDriveSyncState.uploadDownloads.value &&
            (OneDriveSyncState.downloadsDirty.value || OneDriveSyncState.deviceFileETag.value == null)
        if (subscriptions.isEmpty() && !upload) return 0
        val listing = OneDriveClient.list(DEVICES_FOLDER)
        var imported = 0
        subscriptions.forEach { (id, known) ->
            val remote = loadDevice(id, listing["$id$DEVICE_FILE_SUFFIX"]) ?: return@forEach
            val (added, newKnown) = OneDriveSyncMerger.subscriptionImports(remote, known)
            if (added.isNotEmpty()) imported += importDownloads(remote, added).imported
            if (newKnown != known) updateSubscription(id, newKnown)
        }
        if (OneDriveSyncState.uploadDownloads.value && (upload || imported > 0)) {
            uploadOwnDevice(deviceId, listing["$deviceId$DEVICE_FILE_SUFFIX"])
        }
        return imported
    }

    private suspend fun buildOwnDevice(deviceId: String): DeviceDownloads {
        val downloads = EhDB.getAllDownloadInfo()
        return DeviceDownloads(
            deviceId = deviceId,
            deviceName = deviceName,
            updatedAt = now(),
            labels = EhDB.getAllDownloadLabelList().map { SyncLabel(it.label, it.position) },
            downloads = downloads.map { info ->
                SyncDownload(
                    gid = info.gid,
                    label = info.label,
                    time = info.time,
                    finished = info.state == DownloadInfo.STATE_FINISH,
                    artists = info.artistInfoList.map { it.artist },
                )
            },
            galleries = downloads.map { it.galleryInfo.toSyncGallery() },
        )
    }

    private suspend fun uploadOwnDevice(deviceId: String, remote: RemoteItem?) {
        val version = downloadsVersion.get()
        val device = buildOwnDevice(deviceId)
        val cached = runCatching { readDevice(ownDeviceCache) }.getOrNull()
        val storedETag = OneDriveSyncState.deviceFileETag.value
        val path = "$DEVICES_FOLDER/$deviceId$DEVICE_FILE_SUFFIX"
        val newETag = if (remote != null && remote.eTag == storedETag && cached?.copy(updatedAt = 0) == device.copy(updatedAt = 0)) {
            logcat(TAG) { "Device file: unchanged" }
            storedETag
        } else {
            val output = File(syncDir, "own_device.upload")
            writeDevice(output, device)
            // Only this device writes its file: on a conflict take the current ETag and overwrite
            val eTag = try {
                OneDriveClient.upload(path, output, remote?.eTag)
            } catch (_: OneDrivePreconditionException) {
                OneDriveClient.upload(path, output, OneDriveClient.list(DEVICES_FOLDER)["$deviceId$DEVICE_FILE_SUFFIX"]?.eTag)
            }
            if (!output.renameTo(ownDeviceCache)) {
                output.copyTo(ownDeviceCache, overwrite = true)
                output.delete()
            }
            logcat(TAG) { "Device file: uploaded ${device.downloads.size} downloads" }
            eTag
        }
        val clean = downloadsVersion.get() == version
        OneDriveSyncState.withMutableSnapshot {
            it[OneDriveSyncState.deviceFileETag] = newETag
            if (clean) it[OneDriveSyncState.downloadsDirty] = false
        }
    }

    /** Download list of another device, from the local copy when its ETag did not change. */
    private suspend fun loadDevice(id: String, remote: RemoteItem?): DeviceDownloads? {
        remote ?: return null
        val cacheFile = File(deviceCacheDir, "$id$DEVICE_FILE_SUFFIX")
        val eTags = OneDriveSyncState.readStringMap(OneDriveSyncState.remoteDeviceETags)
        if (cacheFile.exists() && remote.eTag != null && eTags[id] == remote.eTag) {
            runCatching { readDevice(cacheFile) }.getOrNull()?.let { return it }
        }
        if (!OneDriveClient.download("$DEVICES_FOLDER/${remote.name}", cacheFile)) return null
        val device = readDevice(cacheFile)
        val updated = OneDriveSyncState.readStringMap(OneDriveSyncState.remoteDeviceETags) + (id to remote.eTag.orEmpty())
        OneDriveSyncState.withMutableSnapshot { it[OneDriveSyncState.remoteDeviceETags] = OneDriveSyncState.encodeStringMap(updated) }
        return device
    }

    /** Other devices that uploaded their download list, this one excluded. */
    suspend fun listDevices(): List<RemoteDevice> = withContext(Dispatchers.IO) {
        val ownId = ensureDeviceId()
        val subscriptions = OneDriveSyncState.readSubscriptions()
        OneDriveClient.list(DEVICES_FOLDER).values
            .filter { it.name.endsWith(DEVICE_FILE_SUFFIX) && it.name != "$ownId$DEVICE_FILE_SUFFIX" }
            .mapNotNull { item ->
                val id = item.name.removeSuffix(DEVICE_FILE_SUFFIX)
                val device = runCatching { loadDevice(id, item) }.onFailure { logcat(TAG, it) }.getOrNull() ?: return@mapNotNull null
                RemoteDevice(id, device.deviceName.ifBlank { id }, device.updatedAt, device.downloads.size, id in subscriptions)
            }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun loadDevice(id: String): DeviceDownloads? = withContext(Dispatchers.IO) {
        loadDevice(id, OneDriveClient.list(DEVICES_FOLDER)["$id$DEVICE_FILE_SUFFIX"])
    }

    /** Imports the selected downloads of another device, paused; downloads this device has are skipped. */
    suspend fun importDownloads(remote: DeviceDownloads, gids: Collection<Long>): DownloadImportResult = withContext(Dispatchers.IO) {
        val localGids = gids.filterTo(HashSet()) { DownloadManager.containDownloadInfo(it) }
        val plan = OneDriveSyncMerger.planDownloadImport(remote, gids, localGids, DownloadManager.labelList.map { it.label })
        plan.newLabels.forEach { DownloadManager.addLabel(it) }
        val infos = plan.downloads.map { download ->
            val gallery = plan.galleries.getValue(download.gid).toEntity()
            DownloadInfo(
                galleryInfo = gallery,
                dirname = gallery.downloadDirname(),
                artistInfoList = DownloadArtist.from(download.gid, download.artists),
                downloadInfo = DownloadEntity(
                    gid = download.gid,
                    state = DownloadInfo.STATE_NONE,
                    time = download.time.takeIf { it > 0 } ?: now(),
                    label = download.label,
                ),
            )
        }
        if (infos.isNotEmpty()) DownloadManager.addDownload(infos)
        logcat(TAG) { "Imported ${infos.size} downloads from ${remote.deviceName}, skipped ${plan.skipped}" }
        DownloadImportResult(infos.size, plan.skipped)
    }

    private suspend fun updateSubscription(id: String, known: Set<Long>?) {
        val updated = OneDriveSyncState.readSubscriptions().toMutableMap()
        if (known == null) updated.remove(id) else updated[id] = known
        OneDriveSyncState.withMutableSnapshot { it[OneDriveSyncState.subscriptions] = OneDriveSyncState.encodeSubscriptions(updated) }
    }

    /** Subscribing only imports what the device adds from now on; its current downloads count as seen. */
    suspend fun setSubscribed(remote: DeviceDownloads, subscribed: Boolean) {
        updateSubscription(remote.deviceId, if (subscribed) remote.downloads.mapTo(HashSet()) { it.gid } else null)
    }

    suspend fun removeDevice(id: String) = withContext(Dispatchers.IO) {
        OneDriveClient.delete("$DEVICES_FOLDER/$id$DEVICE_FILE_SUFFIX")
        File(deviceCacheDir, "$id$DEVICE_FILE_SUFFIX").delete()
        updateSubscription(id, null)
        val eTags = OneDriveSyncState.readStringMap(OneDriveSyncState.remoteDeviceETags) - id
        OneDriveSyncState.withMutableSnapshot { it[OneDriveSyncState.remoteDeviceETags] = OneDriveSyncState.encodeStringMap(eTags) }
    }

    /** Deletes everything this app stored on OneDrive. The next sync uploads the data of this device again. */
    suspend fun clearCloudData() = withContext(Dispatchers.IO) {
        mutex.withLock {
            OneDriveClient.delete(ROOT_FOLDER)
            resetCloudState()
        }
    }

    // endregion
}
