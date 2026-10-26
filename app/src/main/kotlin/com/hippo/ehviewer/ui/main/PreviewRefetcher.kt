package com.hippo.ehviewer.ui.main

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImagePainter.State
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import com.ehviewer.core.model.GalleryPreview
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.coil.justDownload
import com.hippo.ehviewer.ktbuilder.execute
import com.hippo.ehviewer.ktbuilder.imageRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.tarsin.coroutines.runSuspendCatching

/*
 * Fork: previews that fail to load try again by themselves, and a floating bar at the bottom of the gallery tells how
 * they load (see PreviewProgressBar).
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
 * A preview card that reports how its image loads to [refetcher] and, when it fails, waits a little, gets the page's
 * links again and retries. The links of a page fetched again (here or by "Refresh previews") replace the old ones.
 */
@Composable
fun RetryingPreviewCard(
    galleryPreview: GalleryPreview,
    refetcher: PreviewRefetcher?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position = galleryPreview.position
    val generation = refetcher?.generationOf(position) ?: 0
    val preview = refetcher?.fresh?.get(position) ?: galleryPreview
    var attempt by remember(galleryPreview, generation) { mutableIntStateOf(0) }
    var retrying by remember(galleryPreview, generation) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Scrolled away while loading: neither loading nor failed any more, so the bar does not wait for it
    DisposableEffect(refetcher, position) {
        refetcher?.entered(position)
        onDispose { refetcher?.left(position) }
    }
    // A new key makes a new request even when the link stays the same
    key(generation, attempt) {
        EhPreviewCard(
            model = preview,
            onClick = onClick,
            modifier = modifier,
            onState = { state ->
                when (state) {
                    is State.Loading -> refetcher?.loading(position)
                    is State.Error -> {
                        val failed = preview
                        val willRetry = attempt < MAX_RETRIES
                        val host = Uri.parse(failed.url).host
                        refetcher?.failed(position, previewFailureReason(context, state.result.throwable), host, willRetry)
                        if (retrying) {
                            // A retry is on its way
                        } else if (!willRetry) {
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
                    is State.Success -> refetcher?.loaded(position)
                    else -> Unit
                }
            },
        )
    }
}

/** How the image of a preview loads, see [PreviewRefetcher.loads]. */
sealed interface PreviewLoad {
    data object Loading : PreviewLoad
    data object Loaded : PreviewLoad

    /** [retrying]: it still tries again by itself. [host]: the H@H client it came from. */
    data class Failed(val reason: String, val host: String?, val retrying: Boolean) : PreviewLoad
}

/** "Refresh previews" under way: on the [page]th of [pages] pages, getting its links or downloading its images. */
data class PreviewRefresh(val page: Int, val pages: Int, val downloading: Boolean)

/**
 * Fetches a gallery's preview pages again for fresh links, see [RetryingPreviewCard], and keeps how the previews
 * shown so far load, for [PreviewProgressBar]. The previews of a page that fail together share one fetch, and a page
 * is not fetched again within [REFETCH_AFTER_MS] unless asked to ("Refresh previews").
 */
@Stable
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

    /** The newest links by position, from the pages fetched again. */
    val fresh = mutableStateMapOf<Int, GalleryPreview>()

    // By page, goes up when "Refresh previews" downloaded the page again, so its previews load again
    private val generations = mutableStateMapOf<Int, Int>()

    fun generationOf(position: Int) = generations[pageOf(position)] ?: 0

    /** How the previews shown so far load, by position. */
    val loads = mutableStateMapOf<Int, PreviewLoad>()

    /** "Refresh previews" under way, else null. */
    var refresh by mutableStateOf<PreviewRefresh?>(null)
        private set

    /** Goes up when "Refresh previews" finds no preview that failed, for the bar to say so. */
    var nothingToRefresh by mutableIntStateOf(0)
        private set

    private var refreshJob: Job? = null

    /** The progress of the bar swiped away: it stays away until previews fail or "Refresh previews". */
    var progressDismissed by mutableStateOf(false)

    /** The failures the bar showed when swiped away: not told again until they load or "Refresh previews". */
    var dismissedFailures by mutableStateOf(emptySet<Int>())


    private fun pageOf(position: Int) = if (pageSize > 0) position / pageSize else 0

    fun loading(position: Int) {
        loads[position] = PreviewLoad.Loading
        // Retried by a long press: told again if it fails again
        if (position in dismissedFailures) dismissedFailures -= position
    }

    fun loaded(position: Int) {
        loads[position] = PreviewLoad.Loaded
        if (position in dismissedFailures) dismissedFailures -= position
    }

    fun failed(position: Int, reason: String, host: String?, retrying: Boolean) {
        loads[position] = PreviewLoad.Failed(reason, host, retrying)
    }

    // The positions of the cards in the grid now
    private val shown = mutableSetOf<Int>()

    fun entered(position: Int) {
        shown += position
    }

    fun left(position: Int) {
        shown -= position
        val load = loads[position]
        if (load == PreviewLoad.Loading || load is PreviewLoad.Failed && load.retrying) loads.remove(position)
    }

    /**
     * "Refresh previews": for each page with previews that failed for good, fetches its links again and downloads its
     * images, skipping the cache, then loads the previews of the pages that came through again.
     */
    fun refreshFailed(context: Context) {
        if (refreshJob?.isActive == true) return
        progressDismissed = false
        dismissedFailures = emptySet()
        val failedPages = loads.filterValues { it is PreviewLoad.Failed && !it.retrying }.keys.map(::pageOf).distinct().sorted()
        if (failedPages.isEmpty()) {
            nothingToRefresh++
            return
        }
        val appContext = context.applicationContext
        refreshJob = scope.launch {
            try {
                failedPages.forEachIndexed { index, page ->
                    refresh = PreviewRefresh(index, failedPages.size, downloading = false)
                    val previews = runSuspendCatching { fetchPage(page, force = true) }.getOrElse {
                        failPage(page, previewFailureReason(appContext, it), host = null)
                        return@forEachIndexed
                    }
                    refresh = PreviewRefresh(index, failedPages.size, downloading = true)
                    val failure = download(appContext, previews)
                    if (failure != null) {
                        failPage(page, failure.reason, failure.host)
                    } else {
                        // Loads them again, from the images just downloaded: those in the grid now at once, the
                        // others once scrolled to
                        loads.keys.filter { pageOf(it) == page }.forEach {
                            if (it in shown) loads[it] = PreviewLoad.Loading else loads.remove(it)
                        }
                        generations[page] = (generations[page] ?: 0) + 1
                    }
                }
            } finally {
                refresh = null
            }
        }
    }

    private fun failPage(page: Int, reason: String, host: String?) {
        loads.keys.filter { pageOf(it) == page && loads[it] is PreviewLoad.Failed }.forEach {
            loads[it] = PreviewLoad.Failed(reason, host, retrying = false)
        }
    }

    // Downloads the images of a page, skipping the cache; why one failed, or null
    private suspend fun download(context: Context, previews: List<GalleryPreview>): PreviewLoad.Failed? {
        // Previews of a page share one image (a sprite) or have one each
        for (preview in previews.distinctBy(GalleryPreview::url)) {
            val request = with(context) {
                imageRequest(preview) {
                    justDownload()
                    // From the network, not the cache: the cached image is what failed
                    diskCachePolicy(CachePolicy.WRITE_ONLY)
                }
            }
            val result = request.execute()
            if (result is ErrorResult) {
                return PreviewLoad.Failed(previewFailureReason(context, result.throwable), Uri.parse(preview.url).host, retrying = false)
            }
        }
        return null
    }

    /** Fetches [page] again, unless a fetch of it started recently and [force] is false. */
    private suspend fun fetchPage(page: Int, force: Boolean): List<GalleryPreview> {
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
        runSuspendCatching { fetchPage(pageOf(stale.position), force = false) }
    }
}

private const val REFETCH_AFTER_MS = 30 * 1000L
