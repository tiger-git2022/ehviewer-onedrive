package com.hippo.ehviewer.restore

import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.client.parser.ParserUtils
import com.hippo.ehviewer.restore.PandaChaikaMerger.Load
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

class PandaChaikaSearchTest {
    @Test
    fun wordsGoToTheTitle() {
        assertEquals(PandaChaikaQuery("MC Gakuen", emptyList(), null), PandaChaikaQuery.fromKeyword("MC Gakuen"))
    }

    @Test
    fun tagsKeepTheirNamespace() {
        assertEquals(
            PandaChaikaQuery("gakuen", listOf("female:big breasts", "artist:mizuryu_kei", "-language:english"), null),
            PandaChaikaQuery.fromKeyword("f:\"big breasts$\" gakuen a:mizuryu_kei$ -l:english$"),
        )
        // A tag without a namespace is "quoted$"
        assertEquals(PandaChaikaQuery(null, listOf("big breasts"), null), PandaChaikaQuery.fromKeyword("\"big breasts$\""))
    }

    @Test
    fun uploaderAndOr() {
        assertEquals(PandaChaikaQuery(null, emptyList(), "Awing01"), PandaChaikaQuery.fromKeyword("uploader:Awing01 ~f:nun$"))
    }

    @Test
    fun searchesPandaChaikaCannotDo() {
        assertNull(PandaChaikaQuery.fromKeyword("gid:338049"))
        assertNull(PandaChaikaQuery.fromKeyword("-l:english$"))
        assertNull(PandaChaikaQuery.fromKeyword("  "))
    }

    @Test
    fun galleriesGoBetweenPagesByPostedTime() = runBlocking {
        val found = mutableListOf<Long>()
        val merger = merger(
            listOf(
                panda(200, 2000, "Newest"),
                panda(201, 950, "Between 100 and 101"),
                // E-Hentai lists it
                panda(101, 900, "Gallery 101"),
                panda(202, 850, "Between 101 and 102"),
                // E-Hentai lists one of the same title on the next page
                panda(203, 750, "Same Title"),
                panda(204, 700, "With 103"),
                panda(205, 100, "Oldest"),
            ),
            found,
        )
        val page1 = merger.merge(Load.Refresh, null, listOf(eh(100, 1000), eh(101, 900), eh(102, 800)), prev = null, next = "102")
        assertEquals(listOf(200L, 100, 201, 101, 202, 102), page1.map { it.gid })
        val page2 = merger.merge(Load.Append, "102", listOf(eh(103, 700, "same  title"), eh(104, 600)), prev = "103", next = null)
        // Same posted time: after the one of E-Hentai. The last page gets all those left
        assertEquals(listOf(103L, 204, 104, 205), page2.map { it.gid })
        assertEquals(listOf(200L, 201, 202, 204, 205), found)
    }

    @Test
    fun aGalleryShowsOnce() = runBlocking {
        val merger = merger(listOf(panda(1, 950, "A")))
        assertEquals(listOf(10L, 1, 11), merger.merge(Load.Refresh, null, listOf(eh(10, 1000), eh(11, 900)), null, "11").map { it.gid })
        // E-Hentai lists it on the next page
        assertEquals(listOf(12L), merger.merge(Load.Append, "11", listOf(eh(1, 850), eh(12, 800)), "1", null).map { it.gid })
    }

    @Test
    fun withoutHitsOnEHentai() = runBlocking {
        val merger = merger(listOf(panda(1, 300, "A"), panda(2, 200, "B")))
        assertEquals(listOf(1L, 2), merger.merge(Load.Refresh, null, emptyList(), null, null).map { it.gid })
    }

    @Test
    fun fetchesOnlyThePagesNeeded() = runBlocking {
        val fetched = mutableListOf<Int>()
        val pages = listOf(listOf(panda(1, 3000, "A"), panda(2, 2500, "B")), listOf(panda(3, 1500, "C")), listOf(panda(4, 500, "D")))
        val merger = PandaChaikaMerger(
            PandaChaikaQuery("x", emptyList(), null),
            accept = { true },
            fetch = { page ->
                fetched += page
                PandaChaikaApi.GalleryPage(pages[page - 1], hasNext = page < pages.size)
            },
            onFound = {},
        )
        // Down to 2000: the first page ends at 2500, the second at 1500
        merger.merge(Load.Refresh, null, listOf(eh(10, 2000)), null, "10")
        assertEquals(listOf(1, 2), fetched)
    }

    private fun merger(galleries: List<PandaChaikaApi.Gallery>, found: MutableList<Long> = mutableListOf()) = PandaChaikaMerger(
        PandaChaikaQuery("x", emptyList(), null),
        accept = { true },
        fetch = { PandaChaikaApi.GalleryPage(galleries, hasNext = false) },
        onFound = { list -> list.forEach { found += it.gid!! } },
    )

    private fun eh(gid: Long, minutes: Long, title: String = "Gallery $gid") = BaseGalleryInfo(
        gid = gid,
        token = "0123456789",
        title = title,
        posted = ParserUtils.formatDate(minutes * 60_000),
    )

    private fun panda(gid: Long, minutes: Long, title: String) = PandaChaikaApi.Gallery(
        gidValue = JsonPrimitive(gid.toString()),
        token = "abcdef0123",
        title = title,
        category = "Doujinshi",
        posted = minutes * 60,
    )
}

class PandaChaikaApiTest {
    @Test
    fun galleryOfTheApi() {
        val gallery = PandaChaikaApi.decodeGallery(API_GALLERY)!!
        assertEquals(338049L, gallery.gid)
        assertEquals("a909656b14", gallery.ehToken)
        assertEquals(4.75f, gallery.rating)
        assertEquals("https://panda.chaika.moe/archive/34809/download/", gallery.archiveUrl)
        assertEquals(listOf("other:multi-work series", "language:english"), gallery.ehTags)
        assertNull(PandaChaikaApi.decodeGallery("""{"result": "Gallery does not exist."}"""))
    }

    @Test
    fun galleryOfTheSearch() {
        val page = PandaChaikaApi.decodePage(SEARCH_PAGE)
        val gallery = page.galleries.single()
        assertEquals(338049L, gallery.gid)
        assertEquals("a909656b14", gallery.ehToken)
        assertEquals(4.75f, gallery.rating)
        assertEquals("https://panda.chaika.moe/archive/34809/download/", gallery.archiveUrl)
        // The cover of E-Hentai, as a key
        assertEquals("7b/6e/7b6ed4a5fa48d5ed39093c569283830e70a1767a-379295-1119-1600-jpg_l.jpg", gallery.thumbKey)
        assertEquals(false, page.hasNext)
    }

    private companion object {
        const val API_GALLERY = """{"archives": [{"download": "/archive/34809/download/", "id": 34809}], "category": "Doujinshi", "disowned": false, "expunged": false, "filecount": 30, "filesize": 16719729, "fjord": false, "gid": "338049", "link": "https://e-hentai.org/g/338049/a909656b14/", "posted": 1298000913, "rating": 4.75, "tags": ["other:multi-work_series", "language:english"], "title": "(C79) [Alice no Takarabako (Mizuryu Kei)] MC Gakuen San Jigenme | MC High Third Period [English] =LWB=", "title_jpn": "(C79) [ありすの宝箱 (水龍敬)] MC学園 三時限目 [英訳]", "uploader": "Awing01"}"""
        const val SEARCH_PAGE = """{"count": 1, "galleries": [{"archives": [{"link": "https://panda.chaika.moe/archive/34809/download/", "reason": "irodori", "source": "panda"}], "category": "Doujinshi", "comment": "", "disowned": false, "expunged": false, "filecount": 30, "filesize": 16719729, "fjord": false, "gid": "338049", "id": 33454, "link": "https://e-hentai.org/g/338049/a909656b14/", "posted": 1298000913, "provider": "panda", "rating": "4.75", "tags": ["other:multi-work_series", "mixed:group", "parody:original"], "thumbnail": "https://panda.chaika.moe/gallery/33454/thumb/", "thumbnail_url": "https://ehgt.org/7b/6e/7b6ed4a5fa48d5ed39093c569283830e70a1767a-379295-1119-1600-jpg_l.jpg", "title": "(C79) [Alice no Takarabako (Mizuryu Kei)] MC Gakuen San Jigenme | MC High Third Period [English] =LWB=", "title_jpn": "(C79) [ありすの宝箱 (水龍敬)] MC学園 三時限目 [英訳]", "token": "a909656b14", "uploader": "Awing01", "url": "https://panda.chaika.moe/gallery/33454/"}], "has_next": false, "has_previous": false, "num_pages": 1, "number": 1}"""
    }
}
