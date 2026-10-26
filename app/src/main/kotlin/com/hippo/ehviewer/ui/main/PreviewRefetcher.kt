package com.hippo.ehviewer.ui.main

import com.ehviewer.core.model.GalleryPreview
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import moe.tarsin.coroutines.runSuspendCatching

/**
 * Fork: gets a fresh link for a preview that failed to load.
 *
 * Preview links point at an H@H client and carry a token that expires. The first page of previews
 * comes with the gallery detail, which stays in `detailCache` while the app is alive, so opening the
 * gallery again later shows links that no longer work (404), while the later pages, fetched as you
 * scroll, load fine. Fetching the preview page again gives working links.
 *
 * Each page is fetched again at most once (a failed fetch can be tried again), so a preview that
 * still fails with its new link does not loop.
 */
class PreviewRefetcher(
    private val gid: Long,
    private val token: String,
    private val pageSize: Int,
    private val scope: CoroutineScope,
) {
    private val pages = ConcurrentHashMap<Int, Deferred<List<GalleryPreview>>>()

    /** The preview with a new link, or null when there is none to try. */
    suspend fun refetch(stale: GalleryPreview): GalleryPreview? {
        if (pageSize <= 0) return null
        val page = stale.position / pageSize
        val fetch = pages.computeIfAbsent(page) {
            scope.async(Dispatchers.IO) {
                EhEngine.getPreviewList(EhUrl.getGalleryDetailUrl(gid, token, page)).previews
            }
        }
        val previews = runSuspendCatching { fetch.await() }.getOrElse {
            pages.remove(page, fetch)
            return null
        }
        return previews.find { it.position == stale.position }?.takeIf { it.url != stale.url }
    }
}
