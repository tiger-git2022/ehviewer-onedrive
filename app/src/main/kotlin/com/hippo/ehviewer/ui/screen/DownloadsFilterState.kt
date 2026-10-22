package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.database.model.DownloadInfo
import com.hippo.ehviewer.download.DownloadsFilterMode
import kotlinx.serialization.Serializable

@Serializable
data class DownloadsFilterState(
    val mode: DownloadsFilterMode,
    val label: String?,
    val state: Int = -1,
    val keyword: String = "",
)

// Fork: the keyword is matched by DownloadsSearch (several words, `f:` namespaces, quotes and `$`)
fun DownloadsFilterState.take(info: DownloadInfo, search: DownloadsSearch) = mode.take(info, label) &&
    (state == -1 || info.state == state) &&
    with(info) { search.matches(title, titleJpn, simpleTags) }
