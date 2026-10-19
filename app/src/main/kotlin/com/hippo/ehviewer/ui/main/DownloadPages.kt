package com.hippo.ehviewer.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ehviewer.core.database.model.DownloadInfo
import com.hippo.ehviewer.download.ArchivePages
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.shownPages
import kotlinx.coroutines.launch

/**
 * Fork: pages of a download in the download list: once downloaded, those in its CBZ, counted each time it shows or the
 * app comes back, see [ArchivePages]; else those of the gallery.
 */
@Composable
fun rememberDownloadPages(info: DownloadInfo): Int {
    val state by DownloadManager.collectDownloadState(info.gid)
    val finished = state == DownloadInfo.STATE_FINISH
    val counts by ArchivePages.counts.collectAsState()
    // Also when the app comes back: the CBZ may have been written back meanwhile
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(info.gid, finished) {
        val job = if (finished) scope.launch { ArchivePages.refresh(info) } else null
        onPauseOrDispose { job?.cancel() }
    }
    return info.shownPages(counts, finished)
}
