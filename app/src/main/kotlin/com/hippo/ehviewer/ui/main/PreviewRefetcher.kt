package com.hippo.ehviewer.ui.main

import android.os.SystemClock
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
 * A page is fetched again only when it was not fetched in the last few minutes, so a preview that
 * still fails with its new link does not loop, while a link that expired later gets a new one.
 */
class PreviewRefetcher(
    private val gid: Long,
    private val token: String,
    private val pageSize: Int,
    private val scope: CoroutineScope,
) {
    private class Fetch(val previews: Deferred<List<GalleryPreview>>) {
        val at = SystemClock.elapsedRealtime()
    }

    private val pages = ConcurrentHashMap<Int, Fetch>()

    /** The preview with a new link, or null when there is none to try. */
    suspend fun refetch(stale: GalleryPreview): GalleryPreview? {
        if (pageSize <= 0) return null
        val page = stale.position / pageSize
        while (true) {
            val fetch = pages.computeIfAbsent(page) {
                Fetch(
                    scope.async(Dispatchers.IO) {
                        EhEngine.getPreviewList(EhUrl.getGalleryDetailUrl(gid, token, page)).previews
                    },
                )
            }
            val previews = runSuspendCatching { fetch.previews.await() }.getOrElse {
                pages.remove(page, fetch)
                return null
            }
            val fresh = previews.find { it.position == stale.position } ?: return null
            if (fresh.url != stale.url) return fresh
            // The link from that fetch failed too: try again only once it may have expired
            if (SystemClock.elapsedRealtime() - fetch.at < REFETCH_AFTER_MS) return null
            pages.remove(page, fetch)
        }
    }
}

private const val REFETCH_AFTER_MS = 5 * 60 * 1000L
