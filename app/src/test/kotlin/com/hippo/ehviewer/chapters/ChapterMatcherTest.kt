package com.hippo.ehviewer.chapters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The hashes in resources/chapters are those of the real thumbnails of E-H galleries of one artist, hashed as
 * [ThumbHash] does: 4287185 a decensored tankoubon of 297 pages, 1494017 a censored edition of it (206), 1111650 and
 * 1131412 two chapters of it as doujinshi from another scan (48 and 46 pages), 274950 a doujinshi of 54 pages whose
 * first half only is in the decensored edition, and 1519107 another tankoubon of the artist. 4297650 is a translated
 * tankoubon of 229 pages, 3196984, 3217515, 3374377 and 3669955 its chapters 3 to 6 as doujinshi (35, 31, 36, 59).
 */
class ChapterMatcherTest {
    private fun hashes(gid: Long) = javaClass.getResource("/chapters/$gid.txt")!!.readText().lines().filter { it.isNotBlank() }.map { ThumbHash.fromHex(it) }

    @Test
    fun findsChaptersFromAnotherScan() {
        val book = hashes(4287185)
        assertEquals(listOf(4..42), ChapterMatcher.locate(book, hashes(1111650)))
        assertEquals(listOf(43..80), ChapterMatcher.locate(book, hashes(1131412)))
        val censored = hashes(1494017)
        assertEquals(listOf(4..43), ChapterMatcher.locate(censored, hashes(1111650)))
        assertEquals(listOf(45..81), ChapterMatcher.locate(censored, hashes(1131412)))
    }

    @Test
    fun findsTheChaptersOfATranslatedBook() {
        val book = hashes(4297650)
        assertEquals(listOf(66..99), ChapterMatcher.locate(book, hashes(3196984)))
        assertEquals(listOf(100..130), ChapterMatcher.locate(book, hashes(3217515)))
        assertEquals(listOf(132..167), ChapterMatcher.locate(book, hashes(3374377)))
        assertEquals(listOf(168..224), ChapterMatcher.locate(book, hashes(3669955)))
    }

    @Test
    fun takesOnlyThePagesTheBookHas() {
        // 54 pages, the book has the first 26: the rest is not counted
        assertEquals(listOf(197..221), ChapterMatcher.locate(hashes(4287185), hashes(274950)))
        assertEquals(emptyList(), ChapterMatcher.locate(hashes(1494017), hashes(274950)))
    }

    @Test
    fun findsNothingInAnotherBook() {
        val other = hashes(1519107)
        listOf(1111650L, 1131412L, 274950L).forEach { assertEquals(emptyList(), ChapterMatcher.locate(other, hashes(it))) }
    }

    @Test
    fun findsWhereACoverStarts() {
        // Page 3 of the chapter (its first pages are the cover and a credit page) is page 5 of the book
        val page = assertNotNull(hashes(1111650)[2])
        assertEquals(4, ChapterMatcher.locateCover(hashes(4287185), page))
        // The credit page is somewhat close to a page of the book: enough to vote, not to place a chapter alone
        assertEquals(null, ChapterMatcher.locateCover(hashes(4287185), hashes(1111650)[0]!!))
    }

    @Test
    fun hashesKeepTheirBits() {
        val hash = assertNotNull(hashes(4287185).first())
        assertEquals(hash.toList(), ThumbHash.fromHex(ThumbHash.toHex(hash))!!.toList())
        assertTrue(ThumbHash.fromHex("xyz") == null)
    }
}
