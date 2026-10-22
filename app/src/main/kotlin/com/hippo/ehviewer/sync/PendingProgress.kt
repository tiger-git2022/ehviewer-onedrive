package com.hippo.ehviewer.sync

import com.ehviewer.core.database.model.ProgressInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import splitties.init.appCtx

/**
 * Fork: reading progress synced from other devices for galleries this device does not have yet. The PROGRESS table
 * needs the gallery row (foreign key), so such progress waits here, by gid, and is written as soon as the gallery is
 * added: opened, favorited, downloaded ([onGalleryAdded]) or brought by another sync step ([applyAdded]).
 *
 * Before, it waited only in the baseline: it was never written once the gallery was there, and the next progress sync
 * then took the missing row for a deletion and removed the progress on every device.
 */
object PendingProgress {
    private val file get() = File(appCtx.filesDir, "onedrive_sync/pending_progress.json")
    private val serializer = MapSerializer(Long.serializer(), Int.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    // Read once; null in memory before that
    private var loaded = false
    private var pending: Map<Long, Int>? = null

    /** Progress by gid, null before the first progress sync of this version computed it. */
    suspend fun get(): Map<Long, Int>? = mutex.withLock { loadLocked() }

    /** The progress of [merged] whose gallery this device does not have. */
    suspend fun replace(merged: Map<Long, Int>) = mutex.withLock {
        loadLocked()
        saveLocked(merged)
    }

    /** Writes the pending progress of [gid], whose gallery row was just written. */
    suspend fun onGalleryAdded(gid: Long) {
        val page = mutex.withLock { loadLocked()?.get(gid) } ?: return
        runCatching {
            // Never over a page this device read meanwhile
            EhDB.putSyncedProgressIfUnread(listOf(ProgressInfo(gid, page)))
            mutex.withLock { pending?.let { saveLocked(it - gid) } }
        }.onFailure { logcat("PendingProgress", it) }
    }

    /** Writes the pending progress of the galleries added since, by any way. */
    suspend fun applyAdded() {
        val current = get() ?: return
        if (current.isEmpty()) return
        val added = EhDB.existingGalleryGids(current.keys)
        if (added.isEmpty()) return
        EhDB.putSyncedProgressIfUnread(added.map { ProgressInfo(it, current.getValue(it)) })
        mutex.withLock { pending?.let { saveLocked(it - added) } }
    }

    private suspend fun loadLocked(): Map<Long, Int>? {
        if (!loaded) {
            pending = withContext(Dispatchers.IO) {
                runCatching { file.takeIf(File::exists)?.readText()?.let { json.decodeFromString(serializer, it) } }.getOrNull()
            }
            loaded = true
        }
        return pending
    }

    private suspend fun saveLocked(map: Map<Long, Int>) {
        pending = map
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(serializer, map))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    /** The sync data was deleted ([OneDriveSync] reset its folder): read again. */
    suspend fun forget() = mutex.withLock {
        loaded = false
        pending = null
    }
}
