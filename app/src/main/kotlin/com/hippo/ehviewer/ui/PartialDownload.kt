package com.hippo.ehviewer.ui

import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.delete
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.download.ArchivePages
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.PageRange
import com.hippo.ehviewer.download.PageRanges
import com.hippo.ehviewer.download.PartialDownloads
import com.hippo.ehviewer.download.archiveFile
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.EhAlertDialog
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.ui.tools.dialog
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException

/**
 * Fork: downloads only the pages typed, as "1-30, 60-80" (see [PartialDownloads]). A gallery already in the downloads
 * takes the new pages: one kept as a folder gets those it lacks; one kept as a CBZ, after asking, is downloaded again.
 */
context(_: DialogState, _: MainActivity)
suspend fun startPartialDownload(info: BaseGalleryInfo) {
    val pages = info.pages
    if (pages <= 0) return
    val gid = info.gid
    val ranges = awaitPageRanges(pages, PartialDownloads.text(gid).orEmpty())
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

/** Asks for the pages to download of a gallery of [pages] pages, [initial] typed first. */
context(_: DialogState)
private suspend fun awaitPageRanges(pages: Int, initial: String) = dialog<List<PageRange>> { cont ->
    val state = rememberTextFieldState(initial.replace(",", ", "))
    val ranges by remember { derivedStateOf { PageRanges.parse(state.text.toString()) } }
    val picked by remember { derivedStateOf { ranges?.let { PageRanges.indices(it, pages).size } } }
    val valid = picked != null && picked != 0
    EhAlertDialog(
        onDismissRequest = { cont.cancel() },
        confirmButton = {
            TextButton(
                onClick = { ranges?.let { cont.resume(it) } },
                enabled = valid,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(text = stringResource(R.string.download))
            }
        },
        dismissButton = {
            TextButton(onClick = { cont.cancel() }, shapes = ButtonDefaults.shapes()) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
        title = { Text(text = stringResource(R.string.download_partial)) },
        text = {
            val empty = state.text.isBlank()
            OutlinedTextField(
                state = state,
                label = { Text(text = stringResource(R.string.download_partial_hint)) },
                supportingText = {
                    val text = when {
                        empty -> stringResource(R.string.download_partial_pages, pages)
                        picked == null -> stringResource(R.string.download_partial_invalid)
                        picked == 0 -> stringResource(R.string.download_partial_none, pages)
                        picked == pages -> stringResource(R.string.download_partial_all, pages)
                        else -> stringResource(R.string.download_partial_count, picked!!, pages)
                    }
                    Text(text = text)
                },
                isError = !empty && !valid,
                lineLimits = TextFieldLineLimits.SingleLine,
            )
        },
    )
}
