package com.hippo.ehviewer.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ehviewer.core.database.model.DownloadInfo
import com.hippo.ehviewer.download.ArchivePages
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.shownPages

/**
 * Fork: pages of a download in the download list: once downloaded, those in its CBZ, counted each time it shows, see
 * [ArchivePages]; else those of the gallery.
 */
@Composable
fun rememberDownloadPages(info: DownloadInfo): Int {
    val state by DownloadManager.collectDownloadState(info.gid)
    val finished = state == DownloadInfo.STATE_FINISH
    val counts by ArchivePages.counts.collectAsState()
    LaunchedEffect(info.gid, finished) { if (finished) ArchivePages.refresh(info) }
    return if (finished) info.shownPages(counts) else info.pages
}
