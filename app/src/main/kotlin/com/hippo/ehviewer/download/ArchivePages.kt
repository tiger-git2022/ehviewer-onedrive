package com.hippo.ehviewer.download

import android.os.ParcelFileDescriptor
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.util.logcat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Fork: the pages of the downloads kept as a CBZ (or ZIP), counted from the archive. It may have been changed since
 * the download, as when some pages are translated and written back and others left out: the reader shows its pages, so
 * the download list shows them too, not those of the gallery online. Not in the database, whose schema stays the one
 * of the official app; counted again each time a download shows.
 */
object ArchivePages {
    private const val TAG = "ArchivePages"

    private val mutableCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())

    /** gid -> pages in the archive of the download. */
    val counts: StateFlow<Map<Long, Int>> = mutableCounts.asStateFlow()

    /**
     * Counts the pages in the archive of [info] again. Without an archive that can be read (gone, or downloaded again
     * as a folder), the count is dropped and the pages of the gallery show.
     */
    suspend fun refresh(info: DownloadInfo) {
        val pages = withContext(Dispatchers.IO) { count(info) }
        mutableCounts.update {
            when {
                it[info.gid] == pages -> it
                pages == null -> it - info.gid
                else -> it + (info.gid to pages)
            }
        }
    }

    private fun count(info: DownloadInfo): Int? {
        val file = info.archiveFile ?: return null
        return runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(file.openFileDescriptor("r")).use { countZipPages(it.channel) }
        }.onFailure { logcat(TAG, it) }.getOrNull()?.takeIf { it > 0 }
    }
}

/** Pages shown for [this] download: those in its archive when known, else those of the gallery. */
fun DownloadInfo.shownPages(counts: Map<Long, Int>) = counts[gid] ?: pages
