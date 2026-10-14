package com.hippo.ehviewer.sync

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

/**
 * Fork: adds the galleries that newly appear in the cloud favorites slots chosen on this device (see
 * [OneDriveSyncState.autoDownloadSlots]) to the download list, once each. Those in a slot when it is chosen are left
 * alone, and so is a gallery moved between chosen slots. A gallery updated on E-Hentai moves to its new version there,
 * which is added like any new one.
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
    )

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

    /** After the cloud favorites list changed, see [FavoritesIndexSync]. */
    fun onCloudFavoritesSaved(cloud: CloudFavorites) {
        if (slots.isEmpty()) {
            // Off: on again, it starts from then
            if (file.exists()) scope.launch { mutex.withLock { file.delete() } }
            return
        }
        scope.launch { runCatching { check(cloud) }.onFailure { logcat(TAG, it) } }
    }

    /** After the option changed: turning it on remembers what the slot holds now. */
    fun onOptionChanged() {
        scope.launch { runCatching { check(FavoritesIndexSync.cloudFavorites()) }.onFailure { logcat(TAG, it) } }
    }

    private suspend fun check(cloud: CloudFavorites) = mutex.withLock {
        val slots = slots
        if (slots.isEmpty()) {
            // Turned off: turning it on again starts from then
            file.delete()
            return@withLock
        }
        val user = cloud.userKey ?: return@withLock
        // Until the list was read completely once, galleries favorited long ago would look new
        if (cloud.refreshedAt == 0L) return@withLock
        val inSlots = cloud.galleries.filter { it.slot in slots }
        val stored = load()?.takeIf { it.userKey == user }
        // A slot chosen just now: what it holds already is left alone
        val chosenNow = slots - stored?.slots.orEmpty()
        val state = State(
            userKey = user,
            slots = slots,
            handled = stored?.handled.orEmpty() + inSlots.filter { it.slot in chosenNow }.map { it.gid },
        )
        val new = inSlots.filter { it.gid !in state.handled }
        // Galleries that left the chosen slots are forgotten: put back in later, they count as new
        val kept = state.handled.intersect(inSlots.mapTo(HashSet()) { it.gid })
        if (new.isEmpty()) {
            if (state.copy(handled = kept) != stored) save(state.copy(handled = kept))
            return@withLock
        }
        val infos = new.map {
            BaseGalleryInfo(gid = it.gid, token = it.token, title = it.title, titleJpn = it.titleJpn, uploader = it.uploader, category = it.category)
        }
        // Pages, thumbnail and size; a failed request is tried again with the next change of the list
        withContext(Dispatchers.IO) { EhEngine.fillGalleryListByApi(infos) }
        infos.forEach { info ->
            // A removed gallery the API has nothing for cannot be downloaded
            if (info.pages > 0 && !DownloadManager.containDownloadInfo(info.gid)) add(info)
        }
        save(state.copy(handled = kept + infos.map { it.gid }))
        logcat(TAG) { "Added ${infos.size} galleries of slots $slots to the downloads" }
    }

    private suspend fun add(info: BaseGalleryInfo) {
        val label = Settings.defaultDownloadLabel.takeIf { Settings.hasDefaultDownloadLabel && DownloadManager.containLabel(it) }
        try {
            with(appCtx) { DownloadService.startDownload(info, label) }
        } catch (e: IllegalStateException) {
            // Android does not let a background app start the download service: listed, started by the user
            logcat(TAG, e)
            val download = DownloadInfo(info.asEntity(), info.downloadDirname())
            download.label = label
            download.state = DownloadInfo.STATE_NONE
            DownloadManager.addDownload(listOf(download))
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
