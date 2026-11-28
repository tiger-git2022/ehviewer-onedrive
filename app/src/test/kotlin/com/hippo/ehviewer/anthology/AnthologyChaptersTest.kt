package com.hippo.ehviewer.anthology

import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.chapters.ChapterTitles
import com.hippo.ehviewer.client.keyToUrl
import com.hippo.ehviewer.sync.GidShards
import com.hippo.ehviewer.wnacg.isWnacg
import com.hippo.ehviewer.wnacg.wnacgGid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthologyChaptersTest {
    @Test
    fun encodeAndDecodeRoundTripsAndSeparatesFromWnacg() {
        val cases = listOf(
            Triple(1L, 1, 1),
            Triple(3045678L, 49, 68),
            Triple(3999999L, 1, 612),
            Triple(MAX_CHAPTER_PARENT_GID, MAX_CHAPTER_PAGE, MAX_CHAPTER_PAGE),
        )
        for ((parentGid, first, last) in cases) {
            val encoded = assertNotNull(AnthologyChapters.encodeGid(parentGid, first, last))
            assertTrue(encoded < -1L)
            assertTrue(isAnthologyChapter(encoded))
            assertFalse(isWnacg(encoded))
            val slice = assertNotNull(AnthologyChapters.decode(encoded))
            assertEquals(parentGid, slice.parentGid)
            assertEquals(first, slice.first)
            assertEquals(last, slice.last)
            assertEquals(last - first + 1, slice.pageCount)
            assertEquals(first - 1, slice.parentFirstIndex)
            assertEquals(last - 1, slice.parentLastIndex)
        }

        // Regular E-H gids, sentinels, and wnacg gids never decode as anthology chapters
        for (nonChapter in listOf(0L, 1L, 3045678L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, wnacgGid(1), wnacgGid(350_000))) {
            assertNull(AnthologyChapters.decode(nonChapter))
            assertFalse(isAnthologyChapter(nonChapter))
        }
        assertTrue(isWnacg(wnacgGid(350_000)))

        // Invalid page ranges or out-of-range gids return null
        assertNull(AnthologyChapters.encodeGid(0L, 1, 10))
        assertNull(AnthologyChapters.encodeGid(MAX_CHAPTER_PARENT_GID + 1, 1, 10))
        assertNull(AnthologyChapters.encodeGid(100L, 0, 10))
        assertNull(AnthologyChapters.encodeGid(100L, 20, 19))
        assertNull(AnthologyChapters.encodeGid(100L, 1, MAX_CHAPTER_PAGE + 1))
    }

    @Test
    fun thumbKeyRoundTripsAndStaysIntactInKeyToUrl() {
        val key = AnthologyChapters.thumbKeyOf(3045678L, "0123abcd45", 49)
        assertEquals(key, keyToUrl(key))
        val parsed = assertNotNull(AnthologyChapters.parseThumbKey(key))
        assertEquals(3045678L, parsed.parentGid)
        assertEquals("0123abcd45", parsed.parentToken)
        assertEquals(49, parsed.page)

        assertNull(AnthologyChapters.parseThumbKey("https://ehgt.org/ab/cd.webp"))
        assertNull(AnthologyChapters.parseThumbKey("ehv-chapter-thumb://bad"))
    }

    @Test
    fun titlesFilterAndCompareCompatibility() {
        val entry = TocEntry(
            label = "049",
            first = 49,
            last = 68,
            names = listOf(TocName("Matsuzawa Muni", 0, 14), TocName("松沢夢丹", 17, 21)),
            title = "乳辱エージェント",
            lineStart = 0,
            lineEnd = 40,
        )
        val (defTitle, jpnTitle) = AnthologyChapters.formatTitles(entry)
        assertEquals("[Matsuzawa Muni] 乳辱エージェント", defTitle)
        assertEquals("[松沢夢丹] 乳辱エージェント", jpnTitle)
        assertEquals("#3 · 松沢夢丹 (P.49–68)", AnthologyChapters.uploaderLabel(2, entry))

        // Compatible with ChapterTitles.core (ChapterCompare) and AnthologyTitles.matches (AnthologyMatches)
        assertEquals("乳辱エージェント", ChapterTitles.core(jpnTitle))
        assertTrue(AnthologyTitles.matches("乳辱エージェント", jpnTitle))
        assertTrue(AnthologyTitles.matches("乳辱エージェント", defTitle))

        val gid = assertNotNull(AnthologyChapters.encodeGid(3045678L, 49, 68))
        val slice = assertNotNull(AnthologyChapters.decode(gid))
        val item = AnthologyChapterItem(
            index = 2,
            entry = entry,
            slice = slice,
            info = BaseGalleryInfo(gid = gid, token = "0123abcd45", title = defTitle, titleJpn = jpnTitle, pages = slice.pageCount),
        )
        assertTrue(AnthologyChapters.matchesFilter(item, "松沢"))
        assertTrue(AnthologyChapters.matchesFilter(item, "muni"))
        assertTrue(AnthologyChapters.matchesFilter(item, "エージェント"))
        assertTrue(AnthologyChapters.matchesFilter(item, "55"))
        assertTrue(AnthologyChapters.matchesFilter(item, "#3"))
        assertFalse(AnthologyChapters.matchesFilter(item, "69"))
        assertFalse(AnthologyChapters.matchesFilter(item, "池竜静留"))

        // Negative virtual chapter gids shard cleanly in GidShards alongside positive E-H gids
        val gids = longArrayOf(gid, 1000L, 2000L).apply { sort() }
        val shards = GidShards.split(gids, emptyList())
        assertEquals(0, GidShards.rangeOf(shards.map { it.minGid }.toLongArray(), gid))
    }
}
