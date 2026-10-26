package com.hippo.ehviewer.ui.main

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ehviewer.core.model.GalleryPreview
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.tarsin.coroutines.runSuspendCatching

/*
 * Fork: previews that fail to load try again by themselves.
 *
 * Every 20 previews are one image served by an H@H client (a volunteer's computer). A request to it
 * can fail (the client is slow or busy, the request takes over the 10 s timeout, the link carries a
 * token that expires, E-Hentai moved the gallery to another client), and upstream never tries again:
 * the preview stays broken until the gallery is loaded from scratch. The first page of previews comes
 * with the gallery detail, which stays in `detailCache` until the app is closed, so reopening the
 * gallery keeps the same link; the later pages are fetched as you scroll, so they load.
 */

private const val MAX_RETRIES = 3

/** A preview card that, when its image fails, waits a little, gets the page's links again and retries. */
@Composable
fun RetryingPreviewCard(
    galleryPreview: GalleryPreview,
    refetcher: PreviewRefetcher?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var preview by remember(galleryPreview) { mutableStateOf(galleryPreview) }
    var attempt by remember(galleryPreview) { mutableIntStateOf(0) }
    var retrying by remember(galleryPreview) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // A new key makes a new request even when the link stays the same
    key(attempt) {
        EhPreviewCard(
            model = preview,
            onClick = onClick,
            modifier = modifier,
            onError = {
                if (!retrying && attempt < MAX_RETRIES) {
                    retrying = true
                    val failed = preview
                    scope.launch {
                        try {
                            delay(1000L * (attempt + 1))
                            refetcher?.refetch(failed)?.let { preview = it }
                            attempt++
                        } finally {
                            retrying = false
                        }
                    }
                }
            },
        )
    }
}

/**
 * Fetches a gallery's preview pages again for fresh links. The previews of a page that fail together
 * share one fetch, and a page is not fetched again within [REFETCH_AFTER_MS].
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

    /** The preview at the same position as [stale] from a recent fetch of its page, or null when the fetch failed. */
    suspend fun refetch(stale: GalleryPreview): GalleryPreview? {
        if (pageSize <= 0) return null
        val page = stale.position / pageSize
        val now = SystemClock.elapsedRealtime()
        val fetch = pages.compute(page) { _, old ->
            old?.takeIf { now - it.at < REFETCH_AFTER_MS } ?: Fetch(
                scope.async(Dispatchers.IO) {
                    EhEngine.getPreviewList(EhUrl.getGalleryDetailUrl(gid, token, page)).previews
                },
            )
        }!!
        val previews = runSuspendCatching { fetch.previews.await() }.getOrElse {
            pages.remove(page, fetch)
            return null
        }
        return previews.find { it.position == stale.position }
    }
}

private const val REFETCH_AFTER_MS = 30 * 1000L
