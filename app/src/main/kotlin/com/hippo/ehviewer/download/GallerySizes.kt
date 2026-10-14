package com.hippo.ehviewer.download

import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.list
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.client.EhEngine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import splitties.init.appCtx

/**
 * Fork: sizes shown in the download list. Before a gallery is downloaded, the size E-Hentai gives for it (the
 * original archive, from the API); once downloaded, the size of its files. Kept in `files/gallery_sizes.json`, not in
 * the database, whose schema stays the one of the official app.
 */
object GallerySizes {
    private const val TAG = "GallerySizes"
    private val file get() = File(appCtx.filesDir, "gallery_sizes.json")
    private val serializer = MapSerializer(Long.serializer(), Long.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fileMutex = Mutex()
    private val fetchMutex = Mutex()

    private val mutableSizes = MutableStateFlow<Map<Long, Long>>(emptyMap())

    /** gid -> size in bytes E-Hentai gives for the gallery. */
    val sizes: StateFlow<Map<Long, Long>> = mutableSizes.asStateFlow()

    // Galleries asked once this run: one the API has no size for (removed) is not asked again and again
    private val asked = ConcurrentHashMap.newKeySet<Long>()

    // Downloaded galleries: gid -> (files counted for, size)
    private val localSizes = ConcurrentHashMap<Long, Pair<Int, Long>>()

    private val loadJob = scope.launch {
        val stored = fileMutex.withLock {
            runCatching { json.decodeFromString(serializer, file.readText()) }
                .onFailure { if (file.exists()) logcat(TAG, it) }
                .getOrNull()
        }
        if (!stored.isNullOrEmpty()) mutableSizes.update { stored + it }
    }

    /** Remembers the sizes the gallery API gave, gid -> bytes; 0 means unknown and is skipped. */
    fun remember(found: Map<Long, Long>) {
        val known = found.filterValues { it > 0 }
        if (known.isEmpty()) return
        var changed = false
        mutableSizes.update { current ->
            if (known.all { (gid, size) -> current[gid] == size }) {
                current
            } else {
                changed = true
                current + known
            }
        }
        if (changed) scope.launch { save() }
    }

    private suspend fun save() {
        // Not before the stored sizes are read, which the write would drop
        loadJob.join()
        fileMutex.withLock { write() }
    }

    private fun write() {
        runCatching {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(serializer, mutableSizes.value))
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        }.onFailure { logcat(TAG, it) }
    }

    /** Asks the API for the sizes of [infos] not known yet, 25 galleries a request. */
    suspend fun fetchMissing(infos: List<DownloadInfo>) = fetchMutex.withLock {
        val missing = infos.filter { it.gid !in sizes.value && asked.add(it.gid) }
        if (missing.isEmpty()) return@withLock
        // Copies: the API fills in the title and more, the downloads keep theirs
        val copies = missing.map { BaseGalleryInfo(gid = it.gid, token = it.token) }
        runCatching { withContext(Dispatchers.IO) { EhEngine.fillGalleryListByApi(copies) } }
            .onFailure {
                logcat(TAG, it)
                // Asked again the next time the list shows
                missing.forEach { info -> asked.remove(info.gid) }
            }
    }

    /** Size of the files of a downloaded gallery, null if it has no folder. */
    suspend fun localSize(info: DownloadInfo): Long? = withContext(Dispatchers.IO) {
        val files = info.downloadDir?.list() ?: return@withContext null
        localSizes[info.gid]?.takeIf { it.first == files.size }?.let { return@withContext it.second }
        val size = files.sumOf { it.metadataOrNull()?.size ?: 0L }
        localSizes[info.gid] = files.size to size
        size
    }
}
