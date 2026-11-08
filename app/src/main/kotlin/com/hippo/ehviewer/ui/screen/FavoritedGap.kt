package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.hippo.ehviewer.client.parser.GalleryListResult
import com.hippo.ehviewer.client.parser.ParserUtils
import kotlinx.coroutines.delay

// Fork: with Settings.hideFavoritedInLists, a search going on for days with favorites only pauses: the end of the list
// says so and has a button to go on. Going on skips the favorites by gid (E-Hentai lists by gid, newest first, and
// next=<gid> lists those below any gid): jumps twice as far each time until a page has one not favorited, then halves
// the range back to it. A run of favorites is mostly one stretch seen before (a gallery opened goes to the local
// favorites), so few pages are asked; one not favorited alone in the run may be skipped.

/** A run of favorites only pauses past this many days of posted time, see FavoritedGap. */
const val FAVORITED_GAP_DAYS = 3
private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

/** Pages of favorites only in a row that pause too, for a search of many galleries a day. */
private const val GAP_MAX_PAGES = 50

/** Never paused by one page of favorites only (a page of a search of few galleries may take days). */
private const val GAP_MIN_PAGES = 2

/** Pages asked at most to skip a run, and the time between them. */
private const val JUMP_MAX_PAGES = 40
private const val JUMP_INTERVAL_MILLIS = 300L

/** The list paused after [days] days in [pages] pages of favorites only, down to [reached] (the posted date). */
class FavoritedGapPause(
    val days: Int,
    val pages: Int,
    val reached: String,
    private val onResume: () -> Unit,
) : Exception("Only favorited galleries for $days days ($pages pages) down to $reached") {
    /** Lets the next retry of the load skip the run. */
    fun resume() = onResume()
}

/** Skipping a run of favorites: [reached] is the posted date of the page asked last, [pages] the pages asked. */
data class FavoritedGapProgress(val reached: String, val pages: Int)

/**
 * The runs of favorites only of one PagingSource, forward (refresh and append): a new search (refresh) has a new one.
 * [fetch] lists the page below a gid ([cursor] of next=), or the page of the load as asked when null; a page beyond the
 * end lists none. [onProgress] tells the skipping as it goes, null when done.
 */
class FavoritedGap(
    private val onProgress: (FavoritedGapProgress?) -> Unit = {},
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    // Posted time (ms) of the oldest gallery shown, or of the newest fetched while none was
    private var since: Long? = null
    private var emptyPages = 0
    private var oldest: Long? = null
    private var oldestDate = ""

    @Volatile
    private var resumed = false
    private var paused: Paused? = null

    private class Paused(val key: String?, val cursor: String, val error: FavoritedGapPause)

    /** The page loaded at [key] without the favorited, going on to the pages after one of favorites only. */
    suspend fun load(key: String?, fetch: suspend (cursor: String?) -> GalleryListResult): GalleryListResult {
        var result: GalleryListResult
        val pause = paused?.takeIf { it.key == key }
        paused = null
        if (pause != null) {
            // A retry of the paused load: once the button said to go on, it skips from where it paused
            if (!resumed) throw pause.error.also { paused = pause }
            resumed = false
            val cursor = try {
                skip(pause.cursor, fetch)
            } finally {
                onProgress(null)
            }
            result = fetch(cursor)
        } else {
            result = fetch(null)
        }
        var shown = result.withoutFavorited()
        track(result, shown)
        while (shown.galleryInfoList.isEmpty()) {
            val next = result.next ?: break
            if (gapReached()) pause(key, next)
            result = fetch(next)
            shown = result.withoutFavorited()
            track(result, shown)
        }
        return shown
    }

    private fun track(page: GalleryListResult, shown: GalleryListResult) {
        if (shown.galleryInfoList.isNotEmpty()) {
            shown.galleryInfoList.mapNotNull { it.postedMillis() }.minOrNull()?.let { since = it }
            emptyPages = 0
            oldest = null
            oldestDate = ""
            return
        }
        if (page.galleryInfoList.isEmpty()) return
        val times = page.galleryInfoList.mapNotNull { info -> info.postedMillis()?.let { it to info } }
        if (since == null) since = times.maxOfOrNull { it.first }
        emptyPages++
        times.minByOrNull { it.first }?.let { (time, info) ->
            if (oldest == null || time < oldest!!) {
                oldest = time
                oldestDate = info.posted.orEmpty().take(10)
            }
        }
    }

    private fun gapReached(): Boolean {
        if (emptyPages < GAP_MIN_PAGES) return false
        if (emptyPages >= GAP_MAX_PAGES) return true
        val since = since ?: return false
        val oldest = oldest ?: return false
        return since - oldest > FAVORITED_GAP_DAYS * DAY_MILLIS
    }

    private fun pause(key: String?, cursor: String): Nothing {
        val days = ((since ?: 0) - (oldest ?: 0)).coerceAtLeast(0) / DAY_MILLIS
        val error = FavoritedGapPause(days.toInt(), emptyPages, oldestDate) { resumed = true }
        paused = Paused(key, cursor, error)
        throw error
    }

    /**
     * Where to go on after a run of favorites from [start] (a gid): jumps down twice as far each time until a page has
     * one not favorited (or none, past the end), then halves the range between it and the farthest page of favorites
     * only, until less than a page apart. The favorites between pages asked are taken as a run: a gid where the pages
     * below are favorites only up to the first not favorited.
     */
    private suspend fun skip(start: String, fetch: suspend (cursor: String?) -> GalleryListResult): String {
        var top = start.toLongOrNull() ?: return start
        // Gids a page spans, from the last asked
        var span = 0L
        var asked = 0
        suspend fun ask(at: Long): List<BaseGalleryInfo>? {
            if (asked >= JUMP_MAX_PAGES) return null
            if (asked > 0) wait(JUMP_INTERVAL_MILLIS)
            asked++
            val page = fetch(at.toString()).galleryInfoList
            page.lastOrNull()?.posted?.let { onProgress(FavoritedGapProgress(it.take(10), asked)) }
            if (page.size > 1) span = (page.maxOf { it.gid } - page.minOf { it.gid }).coerceAtLeast(1)
            return page
        }
        fun favoritesOnly(page: List<BaseGalleryInfo>) = page.isNotEmpty() && page.none { it.favoriteSlot == NOT_FAVORITED }

        // The first page from the start tells how far a page goes
        var step = 0L
        var bottom: Long? = null
        while (bottom == null) {
            val at = if (step == 0L) top else top - step
            if (at <= 1) {
                bottom = 1
                break
            }
            val page = ask(at) ?: return top.toString()
            if (favoritesOnly(page)) {
                top = page.minOf { it.gid }
                step = if (step == 0L) maxOf(span, 1) else step * 2
            } else {
                if (at == top) return top.toString()
                bottom = at
            }
        }
        var low = bottom ?: return top.toString()
        while (top - low > span) {
            val mid = low + (top - low) / 2
            val page = ask(mid) ?: break
            if (favoritesOnly(page)) {
                top = page.minOf { it.gid }
                if (top <= low) break
            } else {
                low = mid
            }
        }
        // Counted again from where it goes on
        since = null
        emptyPages = 0
        oldest = null
        oldestDate = ""
        return top.toString()
    }
}

private fun BaseGalleryInfo.postedMillis() = posted?.let { runCatching { ParserUtils.parseDate(it) }.getOrNull() }
