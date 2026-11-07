package com.hippo.ehviewer.chapters

import com.hippo.ehviewer.download.PageRange
import com.hippo.ehviewer.download.PageRanges
import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterBlocksTest {
    private val one = ChapterSource(1, "a", "Chapter 1")
    private val two = ChapterSource(2, "b", "Chapter 2")

    @Test
    fun cutsTheBookInBlocks() {
        val blocks = ChapterBlocks.build(20, listOf(4..9 to one, 10..14 to two))
        assertEquals(
            listOf(ChapterBlock(1, 4), ChapterBlock(5, 10, one), ChapterBlock(11, 15, two), ChapterBlock(16, 20)),
            blocks,
        )
    }

    @Test
    fun keepsChaptersOverTheSamePages() {
        // One of pages 1-40, another of 1-180 found after it: both kept, the longer first
        val blocks = ChapterBlocks.build(296, listOf(0..39 to one, 0..179 to two))
        assertEquals(listOf(ChapterBlock(1, 180, two), ChapterBlock(1, 40, one), ChapterBlock(181, 296)), blocks)
        assertEquals(
            listOf(ChapterBlock(1, 40, listOf(two, one)), ChapterBlock(41, 180, two), ChapterBlock(181, 296)),
            ChapterBlocks.segments(296, blocks),
        )
        assertEquals(180, ChapterBlocks.favoritedPages(296, blocks))
    }

    @Test
    fun groupsTheFavoritesOfTheSameChapter() {
        val blocks = ChapterBlocks.build(20, listOf(8..12 to two, 2..9 to one, 8..12 to one))
        assertEquals(
            listOf(ChapterBlock(1, 2), ChapterBlock(3, 10, one), ChapterBlock(9, 13, listOf(two, one)), ChapterBlock(14, 20)),
            blocks,
        )
        assertEquals(
            listOf(ChapterBlock(1, 2), ChapterBlock(3, 8, one), ChapterBlock(9, 10, listOf(one, two)), ChapterBlock(11, 13, listOf(two, one)), ChapterBlock(14, 20)),
            ChapterBlocks.segments(20, blocks),
        )
    }

    @Test
    fun keepsTheBookInItsPages() {
        assertEquals(listOf(ChapterBlock(1, 3), ChapterBlock(4, 5, one)), ChapterBlocks.build(5, listOf(3..30 to one)))
        assertEquals(listOf(ChapterBlock(1, 5)), ChapterBlocks.build(5, emptyList()))
    }

    @Test
    fun picksRunsOfPages() {
        val picked = BooleanArray(10).apply { (0..2).forEach { this[it] = true }; this[5] = true; (8..9).forEach { this[it] = true } }
        assertEquals(listOf(PageRange(1, 3), PageRange(6, 6), PageRange(9, 10)), PageRanges.runs(picked))
        assertEquals(emptyList(), PageRanges.runs(BooleanArray(4)))
    }
}
