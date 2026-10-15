package com.hippo.ehviewer.sync

import android.app.Activity
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.annotation.StringRes
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * A manual sync takes everything; an automatic one only the [SyncCategory.automatic] categories and the favorites
 * index, so settings, quick searches and download lists wait for the next manual sync.
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

    // Fork: the settings of every device, see DeviceSettings
    private const val SETTINGS_FOLDER = "$ROOT_FOLDER/settings"
    private const val DEVICE_FILE_SUFFIX = ".json.gz"
    private const val MAX_ATTEMPTS = 4 // First try and 3 retries after a precondition failure
    private const val DEBOUNCE_MILLIS = 60_000L
    private const val MAX_WAIT_MILLIS = 5 * 60_000L
    private const val RESULT_TAKEN_TIMEOUT_MILLIS = 1_000L
    private const val CHANGED_AT_PERSIST_INTERVAL = 60_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    private val mutableRunning = MutableStateFlow(false)
    val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

    private val mutableChangesPending = MutableStateFlow(false)

    /** True while changes wait for a sync scheduled for them: the change countdown, a rerun or background work. */
    val changesPending: StateFlow<Boolean> = mutableChangesPending.asStateFlow()

    private val mutableProgress = MutableStateFlow<SyncRunProgress?>(null)

    /** Step of the running sync, null when no sync runs. The step of the favorites index shows its own progress. */
    val progress: StateFlow<SyncRunProgress?> = combine(mutableProgress, FavoritesIndexSync.progress) { progress, detail ->
        if (progress != null && progress.favoritesIndex && detail != null) progress.withDetail(detail) else progress
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val syncDir get() = File(appCtx.filesDir, "onedrive_sync")
    private val cacheDir get() = File(syncDir, "cache")
    private val deviceCacheDir get() = File(syncDir, "devices")
    private val ownDeviceCache get() = File(syncDir, "own_device.json.gz")
    private val ownSettingsCache get() = File(syncDir, "own_settings.json.gz")

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

    /** Called by EhDB writes of synced data. Only data an automatic sync takes schedules one. */
    suspend fun notifyChanged(category: SyncCategory) {
        if (currentCoroutineContext()[SyncWrite] != null) return
        markDirty(category)
        // The local favorites are in the favorites index too, also when their own category is not synced
        if (category == SyncCategory.FAVORITES) markFavoritesIndexChanged()
        // Opening a gallery writes the history: no sync for it while the history is not synced. Fork: the local
        // favorites also go out with the favorites index an automatic sync uploads, synced as a category or not
        if (category.automatic && OneDriveSyncState.isEnabled(category) || category == SyncCategory.FAVORITES && OneDriveSyncState.exportFavoritesIndex.value) {
            scheduleChangeSync()
        }
    }

    private val favoritesIndexVersion = AtomicLong()

    // A change and the sync that uploads it update the version and the flag one after the other
    private val favoritesIndexDirtyMutex = Mutex()

    /**
     * Called after [FavoritesIndexSync] saved a change of the cloud favorites, which marked it already
     * ([markFavoritesIndexChanged]): schedules a sync. Marking again would let a sync that built the index in between
     * leave the flag set.
     */
    fun scheduleFavoritesIndexSync() = scheduleChangeSync()

    /** The local or cloud favorites changed: the index waits for a sync (also with sync on change off, and across restarts). */
    suspend fun markFavoritesIndexChanged() {
        favoritesIndexDirtyMutex.withLock {
            favoritesIndexVersion.incrementAndGet()
            if (OneDriveSyncState.isSignedIn && OneDriveSyncState.exportFavoritesIndex.value && !OneDriveSyncState.favoritesIndexDirty.value) {
                OneDriveSyncState.withMutableSnapshot { it[OneDriveSyncState.favoritesIndexDirty] = true }
            }
        }
    }

    /** Version of the favorites changes, taken before building the index a sync uploads. */
    fun favoritesIndexVersion(): Long = favoritesIndexVersion.get()

    /** The index of the changes up to [version] is on OneDrive (or sharing is off): no change waits any more. */
    suspend fun favoritesIndexSynced(version: Long) {
        favoritesIndexDirtyMutex.withLock {
            if (favoritesIndexVersion.get() == version && OneDriveSyncState.favoritesIndexDirty.value) {
                OneDriveSyncState.withMutableSnapshot { it[OneDriveSyncState.favoritesIndexDirty] = false }
            }
        }
    }

    /** Called by EhDB writes of the download list, which the next manual sync uploads. */
    suspend fun notifyDownloadsChanged() {
        if (currentCoroutineContext()[SyncWrite] != null) return
        downloadsVersion.incrementAndGet()
        if (!OneDriveSyncState.downloadsDirty.value) {
            OneDriveSyncState.withMutableSnapshot { it[OneDriveSyncState.downloadsDirty] = true }
        }
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
                // Synced by the next manual sync
                markDirty(SyncCategory.SETTINGS)
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
                OneDriveSyncState.exportFavoritesIndex.changesFlow(),
            ).collect {
                updateOneDriveSyncWork(appCtx)
                // Signed out or the index turned off ends auto download; back on, it starts from then
                AutoDownload.onOptionChanged()
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        inForeground = true
                        // Started now, the cloud favorites work runs in the foreground with its notification
                        scope.launch { runCatching { resumeWaitingCloudFavoritesWork(appCtx) }.onFailure { logcat(TAG, it) } }
                        if (!launchSyncDone) {
                            launchSyncDone = true
                            scope.launch { syncOnLaunch() }
                        } else {
                            resumePendingChanges()
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
            mutableChangesPending.value = true
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

    /**
     * Fork: back in the foreground, changes handed to WorkManager ([handOffPendingChanges]) count down here again:
     * Android may hold that work back for long, while the sync icon shows them as waiting.
     */
    private fun resumePendingChanges() {
        val dirty = SyncCategory.entries.any { it.automatic && OneDriveSyncState.isEnabled(it) && OneDriveSyncState.dirty(it).value }
        val indexDirty = OneDriveSyncState.exportFavoritesIndex.value && OneDriveSyncState.favoritesIndexDirty.value
        if (changesPending.value || dirty || indexDirty) scheduleChangeSync()
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
        mutableChangesPending.value = false
        OneDriveSyncState.withMutableSnapshot {
            it[OneDriveSyncState.accountId] = null
            it[OneDriveSyncState.accountName] = null
            // The next account is unknown: its index may need deleting if sharing stays off
            it[OneDriveSyncState.favoritesIndexETag] = null
            it[OneDriveSyncState.favoritesIndexRemoved] = false
        }
        // Fork: the next account is unknown, its export of format 1 too
        FavoritesIndexSync.onCloudStateReset()
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

    private val mutableManualSyncQueued = MutableStateFlow(false)

    /**
     * Fork: a manual sync is queued or runs as [OneDriveManualSyncWork]. Also read from WorkManager, which keeps the
     * work when the process ends: the screens then still know it is queued.
     */
    val manualSyncQueued: StateFlow<Boolean> by lazy {
        combine(mutableManualSyncQueued, manualSyncWorkQueued(appCtx)) { started, queued -> started || queued }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
    }

    /** A manual sync result, and the screen waiting for it saying that it got it. */
    private class ManualSyncResult(val result: Result<SyncReport>, val taken: CompletableDeferred<Unit> = CompletableDeferred())

    private val manualSyncResults = MutableSharedFlow<ManualSyncResult>(extraBufferCapacity = 1)

    /**
     * Fork: a sync asked from a screen, run as [OneDriveManualSyncWork] in a foreground service, so it goes on when the
     * app is in the background, where the process may be frozen or killed. Leaving the screen cancels only the wait
     * for the result; with no screen waiting, the work shows the result itself.
     */
    suspend fun syncManually(): Result<SyncReport> = coroutineScope {
        // Waiting before the work is queued, so the result cannot pass unseen
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            manualSyncResults.first().also { it.taken.complete(Unit) }.result
        }
        mutableManualSyncQueued.value = true
        enqueueOneDriveManualSyncWork(appCtx)
        result.await()
    }

    /** Fork: the sync of [OneDriveManualSyncWork], and whether a screen waiting for it took the result. */
    internal suspend fun runManualSync(): Pair<Result<SyncReport>, Boolean> {
        mutableManualSyncQueued.value = true
        try {
            val result = sync(SyncTrigger.MANUAL)
            val delivery = ManualSyncResult(result)
            manualSyncResults.emit(delivery)
            // Taken only once a screen confirms it: one leaving meanwhile drops the result
            val taken = withTimeoutOrNull(RESULT_TAKEN_TIMEOUT_MILLIS) { delivery.taken.await() } != null
            return result to taken
        } finally {
            mutableManualSyncQueued.value = false
        }
    }

    suspend fun sync(trigger: SyncTrigger): Result<SyncReport> = withContext(Dispatchers.IO) {
        val result: Result<SyncReport> = mutex.withLock {
            mutableRunning.value = true
            // This sync takes every change made so far; a change from now on schedules the next one
            mutableChangesPending.value = false
            try {
                val start = SystemClock.elapsedRealtime()
                val report = withContext(SyncWrite()) { doSync(manual = trigger == SyncTrigger.MANUAL) }
                logcat(TAG) { "Sync ($trigger) finished in ${SystemClock.elapsedRealtime() - start} ms" }
                Result.success(report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(TAG, e)
                Result.failure<SyncReport>(e)
            } finally {
                mutableRunning.value = false
                mutableProgress.value = null
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

    private suspend fun doSync(manual: Boolean): SyncReport {
        val sections = SyncSection.all.filter { OneDriveSyncState.isEnabled(it.category) && (manual || it.category.automatic) }
        // Connecting, each category, the download lists (manual syncs only) and the favorites index
        val steps = sections.size + if (manual) 3 else 2
        mutableProgress.value = SyncRunProgress(R.string.onedrive_sync_step_connect, 1, steps)
        val account = OneDriveClient.currentAccount() ?: throw OneDriveAuthException(appCtx.getString(R.string.onedrive_sync_not_signed_in))
        updateAccount(account)
        val deviceId = ensureDeviceId()
        val listing = OneDriveClient.list(SHARED_FOLDER)
        val results = sections.mapIndexed { index, section ->
            mutableProgress.value = SyncRunProgress(categoryTitle(section.category), index + 2, steps)
            // Fork: settings are per device, uploaded to its own file and never merged
            if (section === SettingsSection) uploadOwnSettings(deviceId) else syncSection(section, listing[section.category.fileName], deviceId)
        }
        var imported = 0
        val devicesError = if (manual) {
            mutableProgress.value = SyncRunProgress(R.string.onedrive_sync_step_devices, steps - 1, steps)
            runCatching { imported = syncDevices(deviceId) }.exceptionOrNull()
        } else {
            null
        }
        if (devicesError is CancellationException) throw devicesError
        devicesError?.let { logcat(TAG, it) }
        mutableProgress.value = SyncRunProgress(R.string.onedrive_sync_step_favorites_index, steps, steps, favoritesIndex = true)
        // Last: it reads the local favorites the favorites step just merged
        val indexError = runCatching { FavoritesIndexSync.sync(deviceId, deviceName) }.exceptionOrNull()
        if (indexError is CancellationException) throw indexError
        indexError?.let { logcat(TAG, it) }
        return SyncReport(results, imported, devicesError, indexError)
    }

    /**
     * Uploads the favorites index alone, for [CloudFavoritesWork] while it reads the cloud favorites. A failure is
     * left to the next sync, which uploads the index too.
     */
    suspend fun uploadFavoritesIndex() {
        if (!OneDriveClient.isSupported || !OneDriveSyncState.isSignedIn || !OneDriveSyncState.exportFavoritesIndex.value) return
        try {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val account = OneDriveClient.currentAccount() ?: throw OneDriveAuthException(appCtx.getString(R.string.onedrive_sync_not_signed_in))
                    updateAccount(account)
                    FavoritesIndexSync.sync(ensureDeviceId(), deviceName, fromWork = true)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(TAG, e)
        }
    }

    /** Shows a part of the category step running now, see [SyncRunProgress.phase]. */
    private fun phase(@StringRes phase: Int, part: ClosedFloatingPointRange<Float>, done: Long = 0, total: Long = 0, bytes: Boolean = false) {
        val current = mutableProgress.value ?: return
        mutableProgress.value = current.copy(
            phase = phase,
            partStart = part.start,
            partEnd = part.endInclusive,
            done = done.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            total = total.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            bytes = bytes,
        )
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
                    phase(R.string.onedrive_sync_phase_download, StepProgress.DOWNLOAD)
                    val downloaded = OneDriveClient.download("$SHARED_FOLDER/$fileName", download) { done, total ->
                        phase(R.string.onedrive_sync_phase_download, StepProgress.DOWNLOAD, done, total, bytes = true)
                    }
                    if (!downloaded) {
                        // Deleted since the listing: handle it as a missing file, never as "everything deleted"
                        item = null
                        continue
                    }
                    readSection(download, section.serializer).items.also { download.delete() }
                }
            }
            phase(R.string.onedrive_sync_phase_read_local, StepProgress.READ_LOCAL)
            val local = section.collect(baseline, localChangedAt(category))
            phase(R.string.onedrive_sync_phase_merge, StepProgress.MERGE)
            val merged = section.merge(local, cloud, baseline)
            val output = File(syncDir, "$fileName.upload")
            writeSection(output, section.serializer, SectionFile(updatedAt = now(), deviceId = deviceId, items = merged))
            val newETag = if (merged != cloud || current == null) {
                try {
                    phase(R.string.onedrive_sync_phase_upload, StepProgress.UPLOAD_SECTION)
                    OneDriveClient.upload("$SHARED_FOLDER/$fileName", output, current?.eTag) { done, total ->
                        phase(R.string.onedrive_sync_phase_upload, StepProgress.UPLOAD_SECTION, done, total, bytes = true)
                    }
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
            phase(R.string.onedrive_sync_phase_apply, StepProgress.APPLY)
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

    /**
     * Fork: uploads the settings of this device to its own file when they changed since the last upload. Nothing of
     * other devices is applied: they are restored by the user, see [restoreSettings].
     */
    private suspend fun uploadOwnSettings(deviceId: String): CategoryResult {
        val category = SyncCategory.SETTINGS
        val version = dirtyVersions.getValue(category).get()
        phase(R.string.onedrive_sync_phase_read_local, StepProgress.READ_LOCAL)
        val own = DeviceSettings(deviceId = deviceId, deviceName = deviceName, updatedAt = now(), settings = SettingsSection.collect(null, 0))
        val cached = runCatching { readDeviceSettings(ownSettingsCache) }.getOrNull()
        val uploaded = OneDriveSyncState.eTag(category).value != null
        val action = if (uploaded && cached?.copy(updatedAt = 0) == own.copy(updatedAt = 0)) {
            SyncAction.SKIPPED
        } else {
            phase(R.string.onedrive_sync_phase_upload, StepProgress.UPLOAD_SECTION)
            val name = "$deviceId$DEVICE_FILE_SUFFIX"
            val path = "$SETTINGS_FOLDER/$name"
            val output = File(syncDir, "own_settings.upload")
            writeDeviceSettings(output, own)
            // Only this device writes its file: on a conflict take the current ETag and overwrite
            val eTag = try {
                OneDriveClient.upload(path, output, OneDriveClient.list(SETTINGS_FOLDER)[name]?.eTag)
            } catch (_: OneDrivePreconditionException) {
                OneDriveClient.upload(path, output, OneDriveClient.list(SETTINGS_FOLDER)[name]?.eTag)
            }
            if (!output.renameTo(ownSettingsCache)) {
                output.copyTo(ownSettingsCache, overwrite = true)
                output.delete()
            }
            OneDriveSyncState.withMutableSnapshot { it[OneDriveSyncState.eTag(category)] = eTag ?: "" }
            SyncAction.UPLOADED
        }
        if (dirtyVersions.getValue(category).get() == version) {
            OneDriveSyncState.withMutableSnapshot { it[OneDriveSyncState.dirty(category)] = false }
            changedAt.remove(category)
        }
        logcat(TAG) { "$category: ${if (action == SyncAction.UPLOADED) "uploaded" else "unchanged"} (${own.settings.size} settings)" }
        return CategoryResult(category, action, SyncCounts())
    }

    /** Fork: the settings other devices uploaded, newest first. */
    suspend fun listSettingsBackups(): List<DeviceSettings> = withContext(Dispatchers.IO) {
        val ownId = ensureDeviceId()
        OneDriveClient.list(SETTINGS_FOLDER).values
            .filter { it.name.endsWith(DEVICE_FILE_SUFFIX) && it.name != "$ownId$DEVICE_FILE_SUFFIX" }
            .mapNotNull { item ->
                val download = File(syncDir, "settings_${item.name}")
                runCatching {
                    if (!OneDriveClient.download("$SETTINGS_FOLDER/${item.name}", download)) return@runCatching null
                    readDeviceSettings(download)
                }.onFailure { logcat(TAG, it) }.getOrNull().also { download.delete() }
            }
            .sortedByDescending { it.updatedAt }
    }

    /**
     * Fork: applies the settings of another device to this one, except those never synced (download location, account,
     * secrets). Returns how many changed. The next manual sync uploads them as this device's own.
     */
    suspend fun restoreSettings(backup: DeviceSettings): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val local = SettingsSection.collect(null, 0)
            val restored = backup.settings.filterKeys { OneDriveSyncMerger.isSyncableSetting(it) }
            val counts = SettingsSection.apply(local, restored)
            markDirty(SyncCategory.SETTINGS)
            counts.added + counts.updated + counts.removed
        }
    }

    private fun readDeviceSettings(file: File): DeviceSettings {
        val settings = GZIPInputStream(file.inputStream().buffered()).use { json.decodeFromStream(DeviceSettings.serializer(), it) }
        OneDriveSyncMerger.checkVersion(settings.version)
        return settings
    }

    private fun writeDeviceSettings(file: File, settings: DeviceSettings) {
        file.parentFile?.mkdirs()
        GZIPOutputStream(file.outputStream().buffered()).use { json.encodeToStream(DeviceSettings.serializer(), settings, it) }
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
            OneDriveSyncState.withMutableSnapshot {
                // Nothing is on OneDrive any more: shown as not synced until the next sync uploads everything again
                it[OneDriveSyncState.lastSyncTime] = 0L
                it[OneDriveSyncState.lastSyncFailed] = false
                // The index is gone too, nothing to delete when sharing is off
                it[OneDriveSyncState.favoritesIndexRemoved] = true
            }
        }
    }

    // endregion
}
