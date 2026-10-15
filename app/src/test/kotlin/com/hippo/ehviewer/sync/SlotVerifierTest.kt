package com.hippo.ehviewer.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SlotVerifierTest {
    /** A slot of the website: (gid, favorited time), pages of [pageSize] sorted by favorited time, newest first. */
    private class Site(var items: List<Pair<Long, Long>>, val pageSize: Int = 3) {
        var requests = 0

        private fun sorted() = items.sortedWith(compareByDescending<Pair<Long, Long>> { it.second }.thenByDescending { it.first })

        private fun cursor(item: Pair<Long, Long>) = "${item.first}-${item.second}"

        fun fetch(after: String?): SlotVerifier.Page {
            requests++
            val all = sorted()
            val start = if (after == null) {
                0
            } else {
                val (gid, time) = after.split('-').map { it.toLong() }
                all.indexOfFirst { it.second < time || it.second == time && it.first < gid }.let { if (it < 0) all.size else it }
            }
            val page = all.subList(start, minOf(start + pageSize, all.size))
            val next = if (start + pageSize < all.size) cursor(page.last()) else null
            return SlotVerifier.Page(page.map { IndexedGallery(gid = it.first, token = "t", slot = 0) }, next)
        }

        /** A full read, as the refresh records it. */
        fun read(): List<Segment> {
            val segments = ArrayList<Segment>()
            var after: String? = null
            while (true) {
                val page = fetch(after)
                segments += Segment(after, page.galleries.map { it.gid })
                after = page.next ?: break
            }
            return segments
        }
    }

    // 30 favorites, gid i favorited at time i: 10 pages of 3
    private fun site() = Site((1L..30L).map { it to it })

    private fun verify(site: Site, segments: List<Segment>, known: Set<Long>) = runBlocking {
        site.requests = 0
        SlotVerifier.verify(segments, known, site.items.size - known.size) { site.fetch(it) }
    }

    @Test
    fun orderAlternatesFromBothEnds() {
        assertEquals(listOf(0, 4, 1, 3, 2), SlotVerifier.order(5))
        assertEquals(listOf(0, 3, 1, 2), SlotVerifier.order(4))
        assertEquals(listOf(0), SlotVerifier.order(1))
    }

    @Test
    fun recentRemovalIsFoundOnTheFirstPage() {
        val site = site()
        val segments = site.read()
        val known = site.items.map { it.first }.toSet()
        site.items = site.items.filterNot { it.first == 29L }
        val result = assertNotNull(verify(site, segments, known))
        assertEquals(setOf(29L), result.removed)
        assertEquals(1, result.checked)
        assertEquals(1, site.requests)
    }

    @Test
    fun oldRemovalIsFoundFromTheOtherEnd() {
        val site = site()
        val segments = site.read()
        val known = site.items.map { it.first }.toSet()
        site.items = site.items.filterNot { it.first == 2L }
        val result = assertNotNull(verify(site, segments, known))
        assertEquals(setOf(2L), result.removed)
        // The first segment, then the last one
        assertEquals(2, result.checked)
        assertEquals(2, site.requests)
    }

    @Test
    fun removalInTheMiddleIsFoundAfterReadingTowardsIt() {
        val site = site()
        val segments = site.read()
        val known = site.items.map { it.first }.toSet()
        // In the third segment, the fifth one checked: 0, 9, 1, 8, 2
        site.items = site.items.filterNot { it.first == 23L }
        val result = assertNotNull(verify(site, segments, known))
        assertEquals(setOf(23L), result.removed)
        assertEquals(5, result.checked)
        // One page each, as nothing else changed
        assertEquals(5, site.requests)
    }

    @Test
    fun newFavoritesAndRemovalsTogether() {
        val site = site()
        val segments = site.read()
        val known = site.items.map { it.first }.toSet()
        // Two removed, one new favorite: the count dropped by one
        site.items = site.items.filterNot { it.first == 5L || it.first == 20L } + (100L to 100L)
        val result = assertNotNull(verify(site, segments, known))
        assertEquals(setOf(5L, 20L), result.removed)
        assertEquals(listOf(100L), result.added.map { it.gid })
    }

    @Test
    fun manyNewFavoritesBecomeSegmentsOfTheirPages() {
        val site = site()
        val segments = site.read()
        val known = site.items.map { it.first }.toSet()
        site.items = site.items + (100L..106L).map { it to it }
        val result = assertNotNull(verify(site, segments, known))
        assertEquals((106L downTo 100L).toList(), result.added.map { it.gid })
        // The first segment, read over 4 pages, is kept as those pages: the next check reads one page of it
        assertEquals(
            listOf(Segment(null, listOf(106L, 105L, 104L)), Segment("104-104", listOf(103L, 102L, 101L)), Segment("101-101", listOf(100L, 30L, 29L)), Segment("29-29", listOf(28L))),
            result.segments.take(4),
        )
        assertEquals(segments.size + 3, result.segments.size)
        val again = assertNotNull(verify(site.apply { items = items.filterNot { it.first == 105L } }, result.segments, known + (100L..106L)))
        assertEquals(setOf(105L), again.removed)
        assertEquals(1, site.requests)
    }

    @Test
    fun aFavoriteAddedAgainIsNotRemoved() {
        val site = site()
        val segments = site.read()
        val known = site.items.map { it.first }.toSet()
        // 3 favorited again (now first) and 25 removed
        site.items = site.items.filterNot { it.first == 25L }.map { if (it.first == 3L) 3L to 200L else it }
        val result = assertNotNull(verify(site, segments, known))
        assertEquals(setOf(25L), result.removed)
        assertTrue(result.added.isEmpty())
        // The checked segments list it where it is now
        assertTrue(3L in result.segments[0].gids)
        assertEquals(1, result.segments.count { 3L in it.gids })
    }

    @Test
    fun aFavoriteMovedHereKeepsItsPlace() {
        val site = site()
        val segments = site.read()
        val known = site.items.map { it.first }.toSet()
        // Moved from another slot with its favorited time: between 10 and 11
        site.items = site.items + (500L to 10L)
        val result = assertNotNull(verify(site, segments, known))
        assertEquals(listOf(500L), result.added.map { it.gid })
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun knownGalleryNotInTheSegmentsIsFoundGoneAfterReadingAll() {
        val site = site()
        val segments = site.read()
        // Known from a change in the app, never on the website
        val known = site.items.map { it.first }.toSet() + 999L
        val result = assertNotNull(verify(site, segments, known))
        assertEquals(setOf(999L), result.removed)
        assertEquals(segments.size, result.checked)
    }

    @Test
    fun unexplainedDifferenceAsksForAFullRead() = runBlocking {
        val site = site()
        val segments = site.read()
        val known = site.items.map { it.first }.toSet()
        // A count E-Hentai shows that nothing on the pages explains
        assertNull(SlotVerifier.verify(segments, known, difference = -3) { site.fetch(it) })
    }

    @Test
    fun segmentsOfARefreshFollowItsMarks() {
        val galleries = (1L..5L).map { IndexedGallery(gid = it, token = "t", slot = if (it <= 3) 0 else 2) }
        val marks = listOf(PageMark(0, null, 2), PageMark(0, "2-2", 1), PageMark(2, null, 2))
        val segments = SlotVerifier.segmentsOf(marks, galleries)
        assertEquals(listOf(Segment(null, listOf(1L, 2L)), Segment("2-2", listOf(3L))), segments[0])
        assertEquals(listOf(Segment(null, listOf(4L, 5L))), segments[2])
    }
}
