package com.hippo.ehviewer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.delete
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryDetail
import com.hippo.ehviewer.chapters.ChapterCompare
import com.hippo.ehviewer.download.ArchivePages
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.PageRange
import com.hippo.ehviewer.download.PartialDownloads
import com.hippo.ehviewer.download.archiveFile
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.EhModalBottomSheet
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.ui.tools.dialog
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException

/**
 * Fork: downloads only the pages picked, kept as "1-30, 60-80" (see [PartialDownloads]). A gallery already in the downloads
 * takes the new pages: one kept as a folder gets those it lacks; one kept as a CBZ, after asking, is downloaded again.
 */
context(_: DialogState, _: MainActivity)
suspend fun startPartialDownload(info: BaseGalleryInfo, detail: GalleryDetail? = null) {
    val pages = info.pages
    if (pages <= 0) return
    val gid = info.gid
    val ranges = awaitPageRanges(pages, PartialDownloads.text(gid).orEmpty(), detail)
    val existing = DownloadManager.getDownloadInfo(gid)
    val archive = existing?.archiveFile
    if (existing != null && archive != null) {
        awaitConfirmationOrCancel(title = R.string.download_partial) {
            Text(text = stringResource(R.string.download_partial_replace_archive))
        }
    }
    PartialDownloads.set(gid, ranges)
    try {
        if (existing != null) {
            // Started again, to fetch the pages picked now
            val state = existing.state
            if (state == DownloadInfo.STATE_WAIT || state == DownloadInfo.STATE_DOWNLOAD) DownloadManager.stopDownload(gid)
            if (archive != null) {
                archive.delete()
                ArchivePages.refresh(existing)
            }
        }
        startDownload(false, info)
    } catch (e: CancellationException) {
        // The label was not picked: not downloaded, the pages are not kept
        if (existing == null && !DownloadManager.containDownloadInfo(gid)) PartialDownloads.remove(gid)
        throw e
    }
}

/**
 * Asks for the pages to download in a sheet (liquid glass when the app is): a range at a time, "from" and "to", each a
 * chip. A tankoubon is also compared with the favorites of its artist, see [ChapterCompare]: its blocks, favorited in
 * blue and not in grey, are picked with a tick.
 */
context(_: DialogState)
private suspend fun awaitPageRanges(pages: Int, initial: String, detail: GalleryDetail?) = dialog<List<PageRange>> { cont ->
    // As the glass menu of the top bars, its text white in dark themes: the gray text of the sheets was hard to read
    // over the busy page behind
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val text = if (dark) colors.copy(onSurface = Color.White, onSurfaceVariant = Color.White.copy(alpha = 0.85f)) else colors
    MaterialTheme(colorScheme = text) {
        EhModalBottomSheet(onDismissRequest = { cont.cancel() }, glassColor = colors.surfaceContainer) {
            PagePicker(
                pages = pages,
                initial = initial,
                detail = detail?.takeIf(ChapterCompare::isTankoubon),
                onCancel = { cont.cancel() },
                onDownload = { cont.resume(it) },
            )
        }
    }
}
