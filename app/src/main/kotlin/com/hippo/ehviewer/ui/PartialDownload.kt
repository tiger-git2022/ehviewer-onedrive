package com.hippo.ehviewer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.delete
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryDetail
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.chapters.ChapterCompare
import com.hippo.ehviewer.chapters.ChapterSource
import com.hippo.ehviewer.collectAsState
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
suspend fun startPartialDownload(
    info: BaseGalleryInfo,
    detail: GalleryDetail? = null,
    initial: String? = null,
    onOpen: suspend (ChapterSource) -> Unit = {},
) {
    val pages = info.pages
    if (pages <= 0) return
    val gid = info.gid
    val ranges = when (val choice = awaitPageRanges(pages, initial ?: PartialDownloads.text(gid).orEmpty(), detail)) {
        is PageChoice.Ranges -> choice.ranges
        // A favorite listed in the compare, opened instead: the sheet opens again, as it was, back on this gallery
        is PageChoice.Open -> {
            PartialSheetReturn.leave(gid, choice.picked)
            return onOpen(choice.source)
        }
    }
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
private sealed interface PageChoice {
    data class Ranges(val ranges: List<PageRange>) : PageChoice

    /** [picked] the pages picked so far, as "1-30,60-80". */
    data class Open(val source: ChapterSource, val picked: String) : PageChoice
}

/** Fork: the sheet left to open a favorite it lists, to open again when its gallery shows again. */
object PartialSheetReturn {
    private var left: Pair<Long, String>? = null

    fun leave(gid: Long, picked: String) {
        left = gid to picked
    }

    /** The pages picked in the sheet of [gid] left, once; null when it was not left. */
    fun take(gid: Long): String? = left?.takeIf { it.first == gid }?.second?.also { left = null }
}

context(_: DialogState)
private suspend fun awaitPageRanges(pages: Int, initial: String, detail: GalleryDetail?) = dialog<PageChoice> { cont ->
    // As the glass menu of the top bars, its text white in dark themes and black in light ones: the gray text of the
    // sheets was hard to read over the busy page behind. Its blur and the color inside its glassy edge are set in Settings → EH
    val colors = MaterialTheme.colorScheme
    val blur by Settings.partialSheetBlur.collectAsState()
    val opacity by Settings.partialSheetOpacity.collectAsState()
    val ink = if (colors.surface.luminance() < 0.5f) Color.White else Color.Black
    // The borders and the lines as the edge of the floating buttons: a light one in dark themes, a dark one in light ones
    MaterialTheme(
        colorScheme = colors.copy(
            onSurface = ink,
            onSurfaceVariant = ink.copy(alpha = 0.8f),
            outline = ink.copy(alpha = 0.6f),
            outlineVariant = ink.copy(alpha = 0.3f),
        ),
    ) {
        EhModalBottomSheet(onDismissRequest = { cont.cancel() }, glassColor = colors.surfaceContainer, glassBlur = blur, glassOpacity = opacity) {
            PagePicker(
                pages = pages,
                initial = initial,
                detail = detail?.takeIf(ChapterCompare::isTankoubon),
                onCancel = { cont.cancel() },
                onDownload = { cont.resume(PageChoice.Ranges(it)) },
                onOpen = { source, picked -> cont.resume(PageChoice.Open(source, picked)) },
            )
        }
    }
}
