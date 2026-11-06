package com.hippo.ehviewer.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageRangesTest {
    private fun pick(text: String, pages: Int) = PageRanges.parse(text)?.let { PageRanges.indices(it, pages) }

    @Test
    fun parsesRangesAndPages() {
        assertEquals(listOf(PageRange(1, 30), PageRange(60, 80)), PageRanges.parse("1-30, 60-80"))
        assertEquals(listOf(PageRange(5, 5), PageRange(7, 9)), PageRanges.parse("5，7~9"))
        assertEquals(listOf(PageRange(1, 3), PageRange(10, 12)), PageRanges.parse(" 1 - 3 、 10 — 12 "))
        assertEquals(listOf(PageRange(60, null), PageRange(1, 10)), PageRanges.parse("60-; -10"))
        assertEquals(emptyList(), PageRanges.parse("  "))
    }

    @Test
    fun turnsBackwardRangesRound() {
        assertEquals(listOf(PageRange(10, 30)), PageRanges.parse("30-10"))
    }

    @Test
    fun rejectsWhatIsNotPages() {
        assertNull(PageRanges.parse("abc"))
        assertNull(PageRanges.parse("0"))
        assertNull(PageRanges.parse("1-2-3"))
        assertNull(PageRanges.parse("-"))
        assertNull(PageRanges.parse("0-5"))
        assertNull(PageRanges.parse("99999999999"))
    }

    @Test
    fun picksPagesFromZeroInOrderWithinTheGallery() {
        assertEquals((0..29) + (59..79), pick("1-30,60-80", 412))
        assertEquals(51, pick("60-80, 1-30, 20-25", 412)!!.size)
        assertEquals(listOf(8, 9), pick("9-", 10))
        assertEquals(listOf(9), pick("10-200", 10))
        assertEquals(emptyList(), pick("11-20", 10))
    }

    @Test
    fun formatsToReadAgain() {
        val ranges = PageRanges.parse("1-30, 45, 60-")!!
        assertEquals("1-30,45,60-", PageRanges.format(ranges))
        assertEquals(ranges, PageRanges.parse(PageRanges.format(ranges)))
    }
}
