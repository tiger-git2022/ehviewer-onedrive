package com.hippo.ehviewer.ui.main

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter.State
import com.ehviewer.core.i18n.R
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
 * Fork: previews that fail to load try again by themselves, and say why they failed.
 *
 * Every 20 previews are one image served by an H@H client (a volunteer's computer). A request to it
 * can fail (the client is slow or busy, the request takes over the 10 s timeout, the link carries a
 * token that expires, E-Hentai moved the gallery to another client), and upstream never tries again:
 * the preview stays broken until the gallery is loaded from scratch. The first page of previews comes
 * with the gallery detail, which stays in `detailCache` until the app is closed, so reopening the
 * gallery keeps the same link; the later pages are fetched as you scroll, so they load.
 */

private const val MAX_RETRIES = 3

/**
 * A preview card that, when its image fails, shows why, waits a little, gets the page's links again
 * and retries. The links of a page fetched again (here or by "Refresh previews") replace the old ones.
 */
@Composable
fun RetryingPreviewCard(
    galleryPreview: GalleryPreview,
    refetcher: PreviewRefetcher?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position = galleryPreview.position
    val generation = refetcher?.generation ?: 0
    val preview = refetcher?.fresh?.get(position) ?: galleryPreview
    var attempt by remember(galleryPreview, generation) { mutableIntStateOf(0) }
    var retrying by remember(galleryPreview, generation) { mutableStateOf(false) }
    var failure by remember(galleryPreview, generation) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    if (refetcher != null) {
        LaunchedEffect(refetcher, position) { refetcher.shown(position) }
    }
    Box(modifier) {
        // A new key makes a new request even when the link stays the same
        key(generation, attempt) {
            EhPreviewCard(
                model = preview,
                onClick = onClick,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    when (state) {
                        is State.Error -> {
                            failure = previewFailureReason(context, state.result.throwable)
                            val failed = preview
                            if (retrying) {
                                // A retry is on its way
                            } else if (attempt >= MAX_RETRIES) {
                                // Retries used up: a manual retry (long press) still gets a new link when there is one
                                scope.launch { refetcher?.refetch(failed) }
                            } else {
                                retrying = true
                                scope.launch {
                                    try {
                                        delay(1000L * (attempt + 1))
                                        refetcher?.refetch(failed)
                                        attempt++
                                    } finally {
                                        retrying = false
                                    }
                                }
                            }
                        }
                        is State.Success -> failure = null
                        else -> Unit
                    }
                },
            )
        }
        failure?.let { reason ->
            val text = if (attempt < MAX_RETRIES) {
                stringResource(R.string.preview_retrying, reason, attempt + 1, MAX_RETRIES)
            } else {
                stringResource(R.string.preview_failed_long_press, reason)
            }
            Text(
                text = text,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 3,
            )
        }
    }
}

/**
 * Fetches a gallery's preview pages again for fresh links, see [RetryingPreviewCard]. The previews of a
 * page that fail together share one fetch, and a page is not fetched again within [REFETCH_AFTER_MS]
 * unless asked to ("Refresh previews").
 */
@Stable
class PreviewRefetcher(
    private val gid: Long,
    private val token: String,
    val pageSize: Int,
    private val scope: CoroutineScope,
) {
    private class Fetch(val previews: Deferred<List<GalleryPreview>>) {
        val at = SystemClock.elapsedRealtime()
    }

    private val pages = ConcurrentHashMap<Int, Fetch>()
    private val shownPages = ConcurrentHashMap.newKeySet<Int>()

    /** The newest links by position, from the pages fetched again. */
    val fresh = mutableStateMapOf<Int, GalleryPreview>()

    /** Goes up after "Refresh previews", so every preview loads again. */
    var generation by mutableIntStateOf(0)
        private set

    fun shown(position: Int) {
        if (pageSize > 0) shownPages += position / pageSize
    }

    /** The pages whose previews were shown, the first one at least. */
    fun shownPages() = (shownPages + 0).sorted()

    fun reloadAll() {
        generation++
    }

    /** Fetches [page] again, unless a fetch of it started recently and [force] is false. */
    suspend fun fetchPage(page: Int, force: Boolean): List<GalleryPreview> {
        val now = SystemClock.elapsedRealtime()
        val fetch = pages.compute(page) { _, old ->
            old?.takeIf { !force && now - it.at < REFETCH_AFTER_MS } ?: Fetch(
                scope.async(Dispatchers.IO) {
                    EhEngine.getPreviewList(EhUrl.getGalleryDetailUrl(gid, token, page)).previews.onEach {
                        fresh[it.position] = it
                    }
                },
            )
        }!!
        return runSuspendCatching { fetch.previews.await() }.onFailure { pages.remove(page, fetch) }.getOrThrow()
    }

    /** Fetches the page of [stale] again (see [fetchPage]); its new link lands in [fresh]. */
    suspend fun refetch(stale: GalleryPreview) {
        if (pageSize <= 0) return
        runSuspendCatching { fetchPage(stale.position / pageSize, force = false) }
    }
}

private const val REFETCH_AFTER_MS = 30 * 1000L
