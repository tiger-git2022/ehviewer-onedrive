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
 * Fork: adds the galleries that newly appear in one cloud favorites slot (chosen per device, see
 * [OneDriveSyncState.autoDownloadSlot]) to the download list, once each. Those in the slot when the option is turned on
 * are left alone. A gallery updated on E-Hentai moves to its new version there, which is added like any new one.
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
        val slot: Int,
        /** Galleries of [slot] not to add: there when the option was turned on, or added once already. */
        val handled: Set<Long>,
    )

    /**
     * The slot whose new galleries are added, null when off. Its option shows only while signed in to OneDrive with
     * the favorites index on, so it is off otherwise too: nothing is added that the user cannot stop from there.
     */
    private val slot: Int?
        get() = OneDriveSyncState.autoDownloadSlot.value.takeIf {
            it in 0 until CLOUD_SLOT_COUNT && OneDriveSyncState.isSignedIn && OneDriveSyncState.exportFavoritesIndex.value
        }

    /** After the cloud favorites list changed, see [FavoritesIndexSync]. */
    fun onCloudFavoritesSaved(cloud: CloudFavorites) {
        if (slot == null) {
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
        val slot = slot
        if (slot == null) {
            // Turned off: turning it on again starts from then
            file.delete()
            return@withLock
        }
        val user = cloud.userKey ?: return@withLock
        // Until the list was read completely once, galleries favorited long ago would look new
        if (cloud.refreshedAt == 0L) return@withLock
        val inSlot = cloud.galleries.filter { it.slot == slot }
        val state = load()?.takeIf { it.userKey == user && it.slot == slot }
        if (state == null) {
            save(State(user, slot, inSlot.mapTo(HashSet()) { it.gid }))
            return@withLock
        }
        val new = inSlot.filter { it.gid !in state.handled }
        // Galleries that left the slot are forgotten: put back in later, they count as new
        val kept = state.handled.intersect(inSlot.mapTo(HashSet()) { it.gid })
        if (new.isEmpty()) {
            if (kept.size != state.handled.size) save(state.copy(handled = kept))
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
        logcat(TAG) { "Added ${infos.size} galleries of slot $slot to the downloads" }
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
