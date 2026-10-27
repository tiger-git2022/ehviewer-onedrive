package com.hippo.ehviewer.sync

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.spider.downloadDirname
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splitties.init.appCtx
import splitties.systemservices.connectivityManager

/**
 * Fork: adds the galleries that newly appear in the cloud favorites slots chosen on this device (see
 * [OneDriveSyncState.autoDownloadSlots]) to the download list, once each. Those in a slot when it is chosen are left
 * alone, and so is a gallery moved between chosen slots. A gallery updated on E-Hentai moves to its new version there,
 * which is added like any new one. Each slot can have its own download label (see
 * [OneDriveSyncState.autoDownloadLabels]).
 *
 * A gallery whose details could not be read (no network) is tried again when the network is back, when the app comes
 * to the front and with every sync, not only with the next change of the list. One listed while Android did not let the
 * app start a download from the background is started once it can, and a download that failed is started again when
 * the network is back, a few times (see [AutoDownloadRetry]).
 */
object AutoDownload {
    private const val TAG = "AutoDownload"
    private val file get() = File(appCtx.filesDir, "auto_download.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    @Serializable
    private data class State(
        /** [CloudFavorites.userKey] of the account the galleries belong to. */
        val userKey: String,
        val slots: Set<Int>,
        /** Galleries of [slots] not to add: there when their slot was chosen, or added once already. */
        val handled: Set<Long>,
        /** Added to the downloads but not started, see [AutoDownloadRetry]. */
        val unstarted: Set<Long> = emptySet(),
        /** Added and started, not finished yet, with the times a failed download was started again. */
        val running: Map<Long, Int> = emptyMap(),
    )

    /**
     * The galleries of the list may not all be handled: a check failed (no network), or none ran yet in this process
     * (one of a process killed before may have failed). The next [retry] checks the whole list then.
     */
    @Volatile
    private var checkDue = true

    /**
     * Tracked downloads started, or seen waiting or downloading, in this process: one of them found not started was
     * stopped by the user. Not started without being here, it was cut off when the process ended, see [AutoDownloadRetry].
     */
    private val activeHere: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /** The network worked at the last change, see [init]. */
    @Volatile
    private var online = false

    /**
     * The slots whose new galleries are added, empty when off. The option shows only while signed in to OneDrive with
     * the favorites index on, so it is off otherwise too: nothing is added that the user cannot stop from there.
     */
    private val slots: Set<Int>
        get() = if (OneDriveSyncState.isSignedIn && OneDriveSyncState.exportFavoritesIndex.value) {
            OneDriveSyncState.parseSlots(OneDriveSyncState.autoDownloadSlots.value)
        } else {
            emptySet()
        }

    /** Fork: new galleries of some slots are added, see [FavoritesIndexSync.checkForAutoDownload]. */
    val isOn: Boolean get() = slots.isNotEmpty()

    /** After the cloud favorites list changed, see [FavoritesIndexSync]. */
    fun onCloudFavoritesSaved(cloud: CloudFavorites) {
        if (slots.isEmpty()) {
            // Off: on again, it starts from then
            if (file.exists()) scope.launch { mutex.withLock { file.delete() } }
            return
        }
        scope.launch { runCatching { check(cloud) }.onFailure { logcat(TAG, it) } }
    }

    /** Called once from [OneDriveSync.init]: when the network is back, what failed is tried again. */
    fun init() {
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        val works = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        val back = works && !online
                        online = works
                        if (back) retry()
                    }

                    override fun onLost(network: Network) {
                        online = false
                    }
                },
            )
        }.onFailure { logcat(TAG, it) }
    }

    /**
     * Tries again what did not work: the galleries of a failed check, the downloads not started or failed. Called when
     * the network is back, when the app comes to the front and with every sync, see [FavoritesIndexSync].
     */
    fun retry() {
        if (slots.isEmpty()) return
        scope.launch {
            runCatching {
                if (checkDue) {
                    check(FavoritesIndexSync.cloudFavorites())
                } else {
                    mutex.withLock { load()?.let { stored -> resume(stored).let { if (it != stored) save(it) } } }
                }
            }.onFailure { logcat(TAG, it) }
        }
    }

    /** After the option changed: turning it on remembers what the slot holds now. */
    fun onOptionChanged() {
        scope.launch { runCatching { check(FavoritesIndexSync.cloudFavorites()) }.onFailure { logcat(TAG, it) } }
    }

    /** A download label renamed to [to], or deleted ([to] null): the slots that used it follow, see [OneDriveSyncState.autoDownloadLabels]. */
    fun onLabelChanged(from: String, to: String?) {
        val labels = OneDriveSyncState.parseSlotLabels(OneDriveSyncState.autoDownloadLabels.value)
        if (from !in labels.values) return
        val updated = if (to == null) labels.filterValues { it != from } else labels.mapValues { (_, label) -> if (label == from) to else label }
        OneDriveSyncState.autoDownloadLabels.value = OneDriveSyncState.encodeStringMap(updated)
    }

    private suspend fun check(cloud: CloudFavorites) = mutex.withLock {
        try {
            checkLocked(cloud)
            checkDue = false
        } catch (e: Throwable) {
            // Most often no network: tried again by retry
            checkDue = true
            throw e
        }
    }

    private suspend fun checkLocked(cloud: CloudFavorites) {
        val slots = slots
        if (slots.isEmpty()) {
            // Turned off: turning it on again starts from then
            file.delete()
            return
        }
        val user = cloud.userKey ?: return
        // Until the list was read completely once, galleries favorited long ago would look new
        if (cloud.refreshedAt == 0L) return
        val inSlots = cloud.galleries.filter { it.slot in slots }
        val stored = load()?.takeIf { it.userKey == user }
        // A slot chosen just now: what it holds already is left alone
        val chosenNow = slots - stored?.slots.orEmpty()
        val state = State(
            userKey = user,
            slots = slots,
            handled = stored?.handled.orEmpty() + inSlots.filter { it.slot in chosenNow }.map { it.gid },
            unstarted = stored?.unstarted.orEmpty(),
            running = stored?.running.orEmpty(),
        )
        val new = inSlots.filter { it.gid !in state.handled }
        // Galleries that left the chosen slots are forgotten: put back in later, they count as new
        val kept = state.handled.intersect(inSlots.mapTo(HashSet()) { it.gid })
        if (new.isEmpty()) {
            val resumed = resume(state.copy(handled = kept))
            if (resumed != stored) save(resumed)
            return
        }
        val infos = new.map {
            BaseGalleryInfo(gid = it.gid, token = it.token, title = it.title, titleJpn = it.titleJpn, uploader = it.uploader, category = it.category)
        }
        // Pages, thumbnail and size; a failed request is tried again with the next change of the list
        withContext(Dispatchers.IO) { EhEngine.fillGalleryListByApi(infos) }
        val slotOf = new.associate { it.gid to it.slot }
        val started = HashSet<Long>()
        val listed = HashSet<Long>()
        infos.forEach { info ->
            // A removed gallery the API has nothing for cannot be downloaded
            if (info.pages > 0 && !DownloadManager.containDownloadInfo(info.gid)) {
                if (add(info, slotOf[info.gid])) started += info.gid else listed += info.gid
            }
        }
        activeHere += started
        val added = state.copy(
            handled = kept + infos.map { it.gid },
            unstarted = state.unstarted + listed,
            running = state.running + started.associateWith { 0 },
        )
        // The ones just added are not in the downloads yet when started through the service
        save(resume(added, skip = started + listed))
        logcat(TAG) { "Added ${infos.size} galleries of slots $slots to the downloads" }
    }

    /**
     * Starts the downloads of [state] not started or failed, see [AutoDownloadRetry]; those that finished or the user
     * stopped or removed are forgotten.
     */
    private fun resume(state: State, skip: Set<Long> = emptySet()): State {
        if (state.unstarted.isEmpty() && state.running.isEmpty()) return state
        (state.running.keys + state.unstarted).forEach { gid ->
            val now = DownloadManager.getDownloadState(gid)
            if (now == DownloadInfo.STATE_WAIT || now == DownloadInfo.STATE_DOWNLOAD) activeHere += gid
        }
        val plan = AutoDownloadRetry.plan(
            state.unstarted,
            state.running,
            DownloadManager::getDownloadState,
            online || isOnlineNow(),
            skip,
            activeHere::contains,
        )
        if (plan.start.isEmpty()) return state.copy(unstarted = plan.unstarted, running = plan.running)
        return try {
            with(appCtx) { DownloadService.startRangeDownload(plan.start.toLongArray()) }
            activeHere += plan.start
            logcat(TAG) { "Started ${plan.start.size} downloads again" }
            state.copy(unstarted = plan.unstarted, running = plan.running)
        } catch (e: IllegalStateException) {
            // Still in the background: when the app comes to the front
            logcat(TAG, e)
            state.copy(unstarted = plan.unstartedIfRefused, running = plan.runningIfRefused)
        }
    }

    private fun isOnlineNow(): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** True when the download started, false when it is only listed: Android did not let the app start it. */
    private suspend fun add(info: BaseGalleryInfo, slot: Int?): Boolean {
        // The label chosen for its slot, else the default one; a label deleted since falls back too
        val slotLabel = slot?.let { OneDriveSyncState.parseSlotLabels(OneDriveSyncState.autoDownloadLabels.value)[it.toString()] }
        val label = slotLabel?.takeIf { DownloadManager.containLabel(it) }
            ?: Settings.defaultDownloadLabel.takeIf { Settings.hasDefaultDownloadLabel && DownloadManager.containLabel(it) }
        try {
            with(appCtx) { DownloadService.startDownload(info, label) }
            return true
        } catch (e: IllegalStateException) {
            // Android does not let a background app start the download service: listed, started by resume later
            logcat(TAG, e)
            val download = DownloadInfo(info.asEntity(), info.downloadDirname())
            download.label = label
            download.state = DownloadInfo.STATE_NONE
            DownloadManager.addDownload(listOf(download))
            return false
        }
    }

    private fun load(): State? = runCatching { json.decodeFromString(State.serializer(), file.readText()) }
        .onFailure { if (file.exists()) logcat(TAG, it) }
        .getOrNull()

    private fun save(state: State) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(State.serializer(), state))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
