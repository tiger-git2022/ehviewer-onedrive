package com.hippo.ehviewer.download

import com.ehviewer.core.util.logcat
import java.io.File
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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import splitties.init.appCtx

/**
 * Fork: the downloads of only some pages of a gallery, as "1-30,60-80" (see [PageRanges]). The download fetches only
 * those (SpiderQueen), counts its progress on them, and its archive holds only them (SpiderDen.archiveTo), under the
 * names of their pages in the gallery. Kept in a file of their own, not in the database, whose schema stays the one of
 * the official app. A download of every page has no entry.
 */
object PartialDownloads {
    private const val TAG = "PartialDownloads"
    private const val FILE_NAME = "partial_downloads.json"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val serializer = MapSerializer(Long.serializer(), String.serializer())
    private val file get() = File(appCtx.filesDir, FILE_NAME)
    private val fileMutex = Mutex()

    private val mutableRanges = MutableStateFlow(load())

    /** gid -> the pages picked, as typed in [PageRanges] form. */
    val ranges: StateFlow<Map<Long, String>> = mutableRanges.asStateFlow()

    /** The pages picked for [gid] as text, null for all of them. */
    fun text(gid: Long) = mutableRanges.value[gid]

    /**
     * The pages to download of [gid], a gallery of [pages] pages, from 0; null for all of them, also when the picked
     * ones are all of them.
     */
    fun indices(gid: Long, pages: Int): List<Int>? {
        if (pages <= 0) return null
        val ranges = mutableRanges.value[gid]?.let(PageRanges::parse) ?: return null
        return PageRanges.indices(ranges, pages).takeIf { it.isNotEmpty() && it.size < pages }
    }

    /** The number of pages to download of [gid], a gallery of [pages] pages; null for all of them. */
    fun count(gid: Long, pages: Int) = indices(gid, pages)?.size

    /** Only the pages of [ranges] of [gid] are to be downloaded; all of them with null. */
    fun set(gid: Long, ranges: List<PageRange>?) {
        val text = ranges?.takeIf { it.isNotEmpty() }?.let(PageRanges::format)
        if (mutableRanges.value[gid] == text) return
        mutableRanges.update { if (text == null) it - gid else it + (gid to text) }
        save()
    }

    fun remove(gid: Long) = set(gid, null)

    fun remove(gids: Collection<Long>) {
        if (gids.none { it in mutableRanges.value }) return
        mutableRanges.update { it - gids.toSet() }
        save()
    }

    private fun load(): Map<Long, String> = runCatching {
        file.takeIf { it.exists() }?.readText()?.let { Json.decodeFromString(serializer, it) }
    }.onFailure { logcat(TAG, it) }.getOrNull().orEmpty()

    private fun save() {
        scope.launch {
            fileMutex.withLock {
                // The latest, whichever save runs first
                val snapshot = mutableRanges.value
                runCatching {
                    val temp = File(appCtx.filesDir, "$FILE_NAME.tmp")
                    temp.writeText(Json.encodeToString(serializer, snapshot))
                    if (!temp.renameTo(file)) {
                        temp.copyTo(file, overwrite = true)
                        temp.delete()
                    }
                }.onFailure { logcat(TAG, it) }
            }
        }
    }
}
