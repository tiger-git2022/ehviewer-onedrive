package com.hippo.ehviewer.myuploads

import com.ehviewer.core.model.BaseGalleryInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MyUploadsRulesTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = MyUploadsRules.postedMillis("2026-11-11 08:00")

    private fun upload(posted: String, fetchedAgo: Long, lastCommentAgo: Long? = null) = MyUpload(
        info = BaseGalleryInfo(gid = 1, token = "abcdef0123", posted = posted),
        comments = listOfNotNull(lastCommentAgo?.let { UploadComment(id = 5, user = "a", time = now - it, score = 0, text = "x") }),
        fetched = now - fetchedAgo,
    )

    @Test
    fun parsesTheGalleryManager() {
        val html = """
            <table><tr><th>Gallery</th></tr>
            <tr><td><a href="https://upload.e-hentai.org/managegallery?ulgid=1">A</a></td>
                <td><a href="https://e-hentai.org/g/3621157/0123456789/">view</a></td></tr>
            <tr><td><a href="https://upload.e-hentai.org/managegallery?ulgid=2">unpublished</a></td></tr>
            <tr><td><a href="https://exhentai.org/g/3600001/abcdefabcd/">B</a></td>
                <td><a href="https://e-hentai.org/g/3600001/abcdefabcd/">again</a></td></tr>
            <tr><td><a href="https://e-hentai.org/g/3621157/0123456789/">dup</a></td></tr>
            </table>
        """.trimIndent()
        assertEquals(listOf(3621157L to "0123456789", 3600001L to "abcdefabcd"), MyUploadsRules.parseManagePage(html))
    }

    @Test
    fun notTheManagerIsNull() {
        assertNull(MyUploadsRules.parseManagePage("<html>Please log in</html>"))
        assertEquals(emptyList(), MyUploadsRules.parseManagePage("""<a href="managegallery?act=new">New</a>"""))
    }

    @Test
    fun liveGalleriesAreReadHourly() {
        // Posted 2 days ago: hourly
        assertTrue(MyUploadsRules.isDue(upload("2026-11-09 08:00", fetchedAgo = hour), now))
        assertFalse(MyUploadsRules.isDue(upload("2026-11-09 08:00", fetchedAgo = 30 * 60_000L), now))
        // Old, but commented yesterday: hourly too
        assertTrue(MyUploadsRules.isDue(upload("2026-01-01 00:00", fetchedAgo = hour, lastCommentAgo = day), now))
    }

    @Test
    fun quietGalleriesLessOften() {
        // Commented 10 days ago: every 6 hours
        assertFalse(MyUploadsRules.isDue(upload("2026-01-01 00:00", fetchedAgo = 2 * hour, lastCommentAgo = 10 * day), now))
        assertTrue(MyUploadsRules.isDue(upload("2026-01-01 00:00", fetchedAgo = 6 * hour, lastCommentAgo = 10 * day), now))
        // No comment for long: daily
        assertFalse(MyUploadsRules.isDue(upload("2026-01-01 00:00", fetchedAgo = 12 * hour, lastCommentAgo = 60 * day), now))
        assertTrue(MyUploadsRules.isDue(upload("2026-01-01 00:00", fetchedAgo = day, lastCommentAgo = 60 * day), now))
    }

    @Test
    fun neverReadIsDue() {
        assertTrue(MyUploadsRules.isDue(MyUpload(BaseGalleryInfo(gid = 1, token = "x")), now))
    }

    @Test
    fun mergeKeepsTheNewestRead() {
        val merged = MyUploadsRules.mergeSeen(mapOf(1L to 10L, 2L to 30L), mapOf(1L to 20L, 3L to 5L))
        assertEquals(mapOf(1L to 20L, 2L to 30L, 3L to 5L), merged)
    }

    @Test
    fun postedTimeIsUtc() {
        assertEquals(0L, MyUploadsRules.postedMillis("1970-01-01 00:00"))
        assertEquals(0L, MyUploadsRules.postedMillis(null))
        assertEquals(0L, MyUploadsRules.postedMillis("bad"))
    }
}
