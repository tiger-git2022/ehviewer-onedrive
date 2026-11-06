package com.hippo.ehviewer.todownload

import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.sync.AutoDownload
import com.hippo.ehviewer.sync.OneDriveSync
import com.hippo.ehviewer.sync.SyncCategory
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import splitties.init.appCtx

/**
 * Fork: 「待下载」, the local favorites put aside to download, on any device (see [AutoDownload]). Kept in a file of its
 * own, not in the database, whose schema stays the one of EhViewer; synced with the local favorites, each record
 * carrying its mark ([com.hippo.ehviewer.sync.SyncGalleryRecord.queued]).
 *
 * A mark is the time (ms) it was put in, or minus the time it was taken out: the later of two changes made on two
 * devices wins. A gallery leaving the local favorites leaves this list too.
 */
object ToDownload {
    private const val TAG = "ToDownload"
    private val file get() = File(appCtx.filesDir, "to_download.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(Long.serializer(), Long.serializer())
    private val mutex = Mutex()

    private var marks: Map<Long, Long> = load()
    private val _gids = MutableStateFlow(queuedOf(marks))

    /** The galleries in the list. */
    val gids: StateFlow<Set<Long>> = _gids.asStateFlow()

    fun contains(gid: Long) = gid in _gids.value

    /** The marks, for the sync. */
    fun marks(): Map<Long, Long> = marks

    /** Puts [infos] in the list, and in the local favorites when not there yet. */
    suspend fun add(infos: Collection<GalleryInfo>) {
        infos.filterNot { EhDB.containLocalFavorites(it.gid) }.forEach { EhDB.putLocalFavorites(it) }
        val now = System.currentTimeMillis()
        update { old -> old + infos.filter { (old[it.gid] ?: 0) <= 0 }.associate { it.gid to now } }
    }

    /** Takes [gids] out of the list; they stay in the local favorites. */
    suspend fun remove(gids: Collection<Long>) {
        val now = System.currentTimeMillis()
        update { old -> old + gids.filter { (old[it] ?: 0) > 0 }.associateWith { -now } }
    }

    /** [gids] left the local favorites: no mark kept, the sync drops their records. */
    suspend fun onFavoritesRemoved(gids: Collection<Long>) = mutex.withLock {
        if (gids.none { it in marks }) return
        set(marks - gids.toSet())
    }

    /**
     * The marks the sync merged ([synced]) from the ones it read ([collected]), of the local favorites it kept. A mark
     * changed here since it was read is kept, for the next sync to send. Returns how many changed.
     */
    suspend fun applySynced(synced: Map<Long, Long>, collected: Map<Long, Long>): Int = mutex.withLock {
        val old = marks
        val new = HashMap<Long, Long>()
        for (gid in old.keys + synced.keys) {
            val mark = if (old[gid] != collected[gid]) old[gid]?.takeIf { EhDB.containLocalFavorites(gid) } else synced[gid]
            if (mark != null) new[gid] = mark
        }
        if (new == old) return 0
        val changed = (old.keys + new.keys).count { old[it] != new[it] }
        set(new)
        if (queuedOf(new).any { it !in queuedOf(old) }) AutoDownload.onToDownloadChanged()
        changed
    }

    private suspend fun update(change: (Map<Long, Long>) -> Map<Long, Long>) {
        val added = mutex.withLock {
            val old = marks
            val new = change(old)
            if (new == old) return
            set(new)
            queuedOf(new).any { it !in queuedOf(old) }
        }
        OneDriveSync.notifyChanged(SyncCategory.FAVORITES)
        if (added) AutoDownload.onToDownloadChanged()
    }

    private fun set(new: Map<Long, Long>) {
        marks = new
        _gids.value = queuedOf(new)
        runCatching {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(serializer, new))
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }.onFailure { logcat(TAG, it) }
    }

    private fun load(): Map<Long, Long> = runCatching { json.decodeFromString(serializer, file.readText()) }
        .onFailure { if (file.exists()) logcat(TAG, it) }
        .getOrDefault(emptyMap())

    private fun queuedOf(marks: Map<Long, Long>): Set<Long> = marks.filterValues { it > 0 }.keys
}
