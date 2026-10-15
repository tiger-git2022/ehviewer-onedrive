package com.hippo.ehviewer.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.download.GallerySizes
import com.hippo.ehviewer.util.FileUtils

/**
 * Fork: size of a gallery in the download list: of its files once downloaded, else (or when its folder cannot be read)
 * the one E-Hentai gives for it ("about", as resampled images take less). Nothing while it is not known.
 */
@Composable
fun DownloadSizeText(info: DownloadInfo, state: Int, modifier: Modifier = Modifier) {
    val finished = state == DownloadInfo.STATE_FINISH
    val sizes by GallerySizes.sizes.collectAsState()
    val local by produceState<Long?>(null, info.gid, finished) {
        value = if (finished) GallerySizes.localSize(info) else null
    }
    LaunchedEffect(info.gid) { GallerySizes.request(info) }
    // A downloaded gallery whose folder cannot be read shows the estimate too
    val text = local?.takeIf { finished && it > 0 }?.let { FileUtils.humanReadableByteCount(it) }
        ?: sizes[info.gid]?.let { stringResource(R.string.download_size_estimated, FileUtils.humanReadableByteCount(it)) }
        ?: return
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
