package com.hippo.ehviewer.restore

import com.ehviewer.core.util.logcat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import splitties.init.appCtx

/**
 * Fork: the galleries restored from PandaChika, see [PandaChaikaApi]. Kept in a file of their own, not in the database,
 * whose schema stays the one of the official app.
 *
 * - Restored: E-Hentai said the gallery is unavailable and PandaChika has it. Its details and its download come from
 *   PandaChika. Kept across launches.
 * - Found: listed by PandaChika in a search next to those of E-Hentai, not opened yet. Only in memory; opening one
 *   tells which it is.
 *
 * Both show a badge on their cover.
 */
object RestoredGalleries {
    private const val TAG = "RestoredGalleries"
    private const val FILE_NAME = "pandachaika_restored.json"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val serializer = MapSerializer(Long.serializer(), PandaChaikaApi.Gallery.serializer())
    private val file get() = File(appCtx.filesDir, FILE_NAME)
    private val fileMutex = Mutex()

    private val restored = MutableStateFlow(load())
    private val found = MutableStateFlow<Map<Long, PandaChaikaApi.Gallery>>(emptyMap())

    /** The galleries with a badge: restored or found. */
    val badged: StateFlow<Set<Long>> = combine(restored, found) { r, f -> r.keys + f.keys }
        .stateIn(scope, SharingStarted.Eagerly, restored.value.keys)

    fun isRestored(gid: Long) = gid in restored.value

    /** The backup of [gid], restored or found. */
    operator fun get(gid: Long): PandaChaikaApi.Gallery? = restored.value[gid] ?: found.value[gid]

    /** [galleries] were listed by PandaChika in a search. */
    fun found(galleries: Collection<PandaChaikaApi.Gallery>) {
        val new = galleries.mapNotNull { g -> g.gid?.let { it to g } }.filter { (gid, _) -> gid !in restored.value }
        if (new.isNotEmpty()) found.update { it + new }
    }

    /** E-Hentai listed [gids]: those found by a search before are not removed. Restored ones stay until opened. */
    fun listed(gids: Collection<Long>) {
        if (found.value.keys.none { it in gids }) return
        val listed = gids.toSet()
        found.update { it - listed }
    }

    /** E-Hentai said [gid] is unavailable and PandaChika has [gallery]. */
    fun restore(gid: Long, gallery: PandaChaikaApi.Gallery) {
        found.update { it - gid }
        restored.update { it + (gid to gallery) }
        save()
    }

    /**
     * [gid] goes to the downloads: when found by a search, it is kept as restored, its download coming from PandaChika
     * also after a restart. The download asks E-Hentai first, see DownloadManager.startPandaChaikaDownload.
     */
    fun promote(gid: Long) {
        val gallery = found.value[gid] ?: return
        restore(gid, gallery)
    }

    /** E-Hentai showed [gid]: it is not removed, or no longer. */
    fun dismiss(gid: Long) {
        found.update { it - gid }
        if (gid in restored.value) {
            restored.update { it - gid }
            save()
        }
    }

    private fun load(): Map<Long, PandaChaikaApi.Gallery> = runCatching {
        file.takeIf { it.exists() }?.readText()?.let { json.decodeFromString(serializer, it) }
    }.onFailure { logcat(TAG, it) }.getOrNull().orEmpty()

    private fun save() {
        scope.launch {
            fileMutex.withLock {
                // The latest, whichever save runs first
                val snapshot = restored.value
                runCatching {
                    val temp = File(appCtx.filesDir, "$FILE_NAME.tmp")
                    temp.writeText(json.encodeToString(serializer, snapshot))
                    if (!temp.renameTo(file)) {
                        temp.copyTo(file, overwrite = true)
                        temp.delete()
                    }
                }.onFailure { logcat(TAG, it) }
            }
        }
    }
}
