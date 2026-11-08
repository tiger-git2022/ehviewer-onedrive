package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.client.parser.GalleryListResult
import com.hippo.ehviewer.client.parser.ParserUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class FavoritedGapTest {
    // A search of one gallery every 5 minutes (a page of 25 is about 2 hours), gid 100000 newest, the gids 10 apart
    private class Search(val favorited: (gid: Long) -> Boolean) {
        val gids = (100_000L downTo 10L step 10).toList()
        var asked = 0

        fun page(below: Long): GalleryListResult {
            asked++
            val list = gids.filter { it < below }.take(25).map { info(it) }
            val next = list.lastOrNull()?.gid?.takeIf { it > gids.last() }?.toString()
            return GalleryListResult(list.firstOrNull()?.gid?.toString(), next, list.toMutableList())
        }

        fun info(gid: Long) = BaseGalleryInfo(
            gid = gid,
            token = "0123456789",
            // 5 minutes for each 10 gids, from 2026-01-01
            posted = ParserUtils.formatDate(1_767_225_600_000L + gid / 10 * 5 * 60_000),
            favoriteSlot = if (favorited(gid)) 0 else -2,
        )

        val fetch: suspend (String?) -> GalleryListResult = { cursor -> page(cursor?.toLong() ?: Long.MAX_VALUE) }
    }

    private val day = 24 * 12 * 10L // gids in a day

    @Test
    fun shortRunGoesOn() = runBlocking {
        // 2 days of favorites below the first page
        val search = Search { it in (100_000L - 250 - 2 * day)..(100_000L - 250) }
        val gap = FavoritedGap(wait = {})
        val first = gap.load(null, search.fetch)
        val second = gap.load(first.next, search.fetch)
        assertTrue(second.galleryInfoList.first().gid < 100_000L - 250 - 2 * day)
    }

    @Test
    fun longRunPausesThenSkips() = runBlocking {
        // A month of favorites below the first page
        val top = 100_000L - 250
        val end = top - 30 * day
        val search = Search { it in end..top }
        val gap = FavoritedGap(wait = {})
        val first = gap.load(null, search.fetch)
        assertEquals(25, first.galleryInfoList.size)
        val pause = assertFailsWith<FavoritedGapPause> { gap.load(first.next, search.fetch) }
        assertTrue(pause.days >= FAVORITED_GAP_DAYS)
        // A retry without the button pauses again, asking nothing
        val asked = search.asked
        assertFailsWith<FavoritedGapPause> { gap.load(first.next, search.fetch) }
        assertEquals(asked, search.asked)
        // The button: few pages to the first not favorited, nothing skipped
        pause.resume()
        val found = gap.load(first.next, search.fetch)
        assertEquals(end - 10, found.galleryInfoList.first().gid)
        val skipping = search.asked - asked
        assertTrue(skipping < 30, "asked $skipping pages to skip a month")
    }

    @Test
    fun runToTheEnd() = runBlocking {
        // Favorites only from below the first page to the end of the search
        val search = Search { it <= 100_000L - 250 }
        val gap = FavoritedGap(wait = {})
        val first = gap.load(null, search.fetch)
        val pause = assertFailsWith<FavoritedGapPause> { gap.load(first.next, search.fetch) }
        pause.resume()
        val last = gap.load(first.next, search.fetch)
        assertTrue(last.galleryInfoList.isEmpty())
    }
}
