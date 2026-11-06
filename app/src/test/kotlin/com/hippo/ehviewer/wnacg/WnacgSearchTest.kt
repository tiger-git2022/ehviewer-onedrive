package com.hippo.ehviewer.wnacg

import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.parser.ParserUtils
import com.hippo.ehviewer.restore.BackupItem
import com.hippo.ehviewer.restore.BackupMerger
import com.hippo.ehviewer.restore.BackupMerger.Load
import com.hippo.ehviewer.restore.BackupPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class WnacgSearchTest {
    @Test
    fun searchesWordsAndNames() {
        assertEquals(WnacgQuery("ほとりの小屋", null), WnacgQuery.fromKeyword("ほとりの小屋"))
        assertEquals(WnacgQuery("mind control", null), WnacgQuery.fromKeyword("mind control"))
        assertEquals(WnacgQuery("Some Artist Circle", null), WnacgQuery.fromKeyword("""a:"Some Artist$" g:"Circle$""""))
        assertEquals(WnacgQuery("blue archive", "ZH"), WnacgQuery.fromKeyword("""parody:"blue archive$" l:chinese$"""))
        // Left out: the site cannot leave out
        assertEquals(WnacgQuery("word", null), WnacgQuery.fromKeyword("""word -f:"big breasts$""""))
    }

    @Test
    fun namesAsTheTitlesWriteThem() {
        val names = mapOf("yamashita kurowo" to "山下クロヲ")
        val nameOf = { _: String, value: String -> names[value] ?: value }
        assertEquals(WnacgQuery("山下クロヲ", null), WnacgQuery.fromKeyword("""a:"yamashita kurowo$"""", nameOf))
        assertEquals(WnacgQuery("山下クロヲ other", null, WnacgName("a", "other")), WnacgQuery.fromKeyword("""artist:"yamashita kurowo$" a:other$""", nameOf))
        // Without the tag database: as the tag, to be guessed from the titles
        val unwritten = WnacgName("a", "yamashita kurowo")
        assertEquals(WnacgQuery("yamashita kurowo", null, unwritten), WnacgQuery.fromKeyword("""a:"yamashita kurowo$""""))
        assertEquals(WnacgQuery("山下クロヲ word", null), WnacgQuery("yamashita kurowo word", null, unwritten).withName("山下クロヲ"))
    }

    @Test
    fun theNameAsTheTitlesWriteIt() {
        val titles = listOf(
            "[くろほんやさん (山下クロヲ)] メスガキ×無知×鞭 [DL版]",
            "(C104) [くろほんやさん (山下クロヲ、ねこ電力)] トモダチのママ [中国翻訳]",
            "[くろほんやさん (ねこ電力)] J○姪っ子の弱味を握ってから…",
            "[Kurohonyasan (Yamashita Kurowo)] Mesugaki [English]",
        )
        assertEquals("山下クロヲ", guessWrittenName(WnacgName("a", "yamashita kurowo"), titles))
        assertEquals("くろほんやさん", guessWrittenName(WnacgName("g", "kurohonyasan"), titles))
        assertEquals("作者", guessWrittenName(WnacgName("artist", "sakusha"), listOf("[作者] 本", "[作者] 本2", "[Sakusha] Book")))
        // Latin letters only, or no one name for most: none
        assertNull(guessWrittenName(WnacgName("a", "x"), listOf("[Kurohonyasan (Yamashita Kurowo)] Mesugaki")))
        assertNull(guessWrittenName(WnacgName("a", "x"), listOf("[A] 本", "[B] 本", "[C] 本")))
    }

    @Test
    fun cannotSearchOtherTags() {
        assertNull(WnacgQuery.fromKeyword("""f:"big breasts$""""))
        assertNull(WnacgQuery.fromKeyword("""word f:"big breasts$""""))
        assertNull(WnacgQuery.fromKeyword("""word l:korean$"""))
        assertNull(WnacgQuery.fromKeyword("""l:chinese$"""))
        assertNull(WnacgQuery.fromKeyword("""-word"""))
    }

    @Test
    fun languagesOfAlbums() {
        assertEquals(true, WnacgQuery.languageMatches(null, "JA"))
        assertEquals(true, WnacgQuery.languageMatches("ZH", null))
        assertEquals(false, WnacgQuery.languageMatches("ZH", "JA"))
        assertEquals(true, WnacgQuery.languageMatches("EN", "EN"))
        assertEquals(false, WnacgQuery.languageMatches("EN", null))
    }

    @Test
    fun albumsAsGalleries() {
        val info = com.hippo.ehviewer.chapters.Wnacg.Album(402119, "[A] Title [中国翻訳]", 91, "https://t/a.webp", 0, 1).toGalleryInfo()
        assertEquals(-402119L, info.gid)
        assertEquals(WNACG_TOKEN, info.token)
        assertEquals(EhUtils.DOUJINSHI, info.category)
        assertEquals("ZH", info.simpleLanguage)
        assertEquals(true, info.isWnacg)
        assertEquals(402119L, info.wnacgAid)
    }

    @Test
    fun aTranslationShowsNextToTheOriginal() = runBlocking {
        val merger = merger(listOf(album(1, 950, "[A] Title [中国翻訳]"), album(2, 850, "[A] Other [中国翻訳]")))
        // E-Hentai lists the Japanese original of the first, and the Chinese translation of the second
        val page = listOf(eh(10, 1000, "[A] Title"), eh(11, 900, "[A] Other [中国翻訳] [Group]", "ZH"), eh(12, 800, "x"))
        assertEquals(listOf(10L, -1, 11, 12), merger.merge(Load.Refresh, null, page, null, null).map { it.gid })
    }

    @Test
    fun theJapaneseTitlesAreAskedOnlyWhenNeeded() = runBlocking {
        var asked = 0
        val merger = BackupMerger(
            accept = { true },
            sources = listOf { BackupPage(listOf(item(album(1, 950, "[A] 日本語 [中国翻訳]"))), listOf(minutes(950)), hasNext = false) },
            onFound = {},
            maxPages = 5,
            titleKeys = ::wnacgTitleKeys,
            moreTitles = { page ->
                asked++
                page.associate { it.gid to listOf("[A] 日本語 [中国翻訳]") }
            },
        )
        // The list told the English title only; the API tells the Japanese one: the same, left out
        val page = listOf(eh(10, 1000, "[A] English [Chinese]", "ZH"), eh(11, 900, "y"))
        assertEquals(listOf(10L, 11), merger.merge(Load.Refresh, null, page, null, null).map { it.gid })
        assertEquals(1, asked)
    }

    @Test
    fun aCheckAskedForIsTold() = runBlocking {
        var told: Throwable? = null
        val merger = BackupMerger<com.hippo.ehviewer.chapters.Wnacg.Album>(
            accept = { true },
            sources = listOf { throw com.hippo.ehviewer.chapters.Wnacg.BlockedException() },
            onFound = {},
            maxPages = 5,
            onError = { told = it },
        )
        // The page of E-Hentai shows alone, and the check is told
        val page = listOf(eh(10, 1000, "a"), eh(11, 900, "b"))
        assertEquals(listOf(10L, 11), merger.merge(Load.Refresh, null, page, null, null).map { it.gid })
        assertEquals(true, told is com.hippo.ehviewer.chapters.Wnacg.BlockedException)
    }

    private fun merger(albums: List<com.hippo.ehviewer.chapters.Wnacg.Album>) = BackupMerger(
        accept = { true },
        sources = listOf { BackupPage(albums.map(::item), albums.map { it.created }, hasNext = false) },
        onFound = {},
        maxPages = 5,
        titleKeys = ::wnacgTitleKeys,
    )

    private fun item(album: com.hippo.ehviewer.chapters.Wnacg.Album) = BackupItem(album, album.toGalleryInfo(), album.created)

    private fun album(aid: Long, minutes: Long, title: String) = com.hippo.ehviewer.chapters.Wnacg.Album(aid, title, 20, null, minutes(minutes), 1)

    private fun eh(gid: Long, minutes: Long, title: String, language: String? = null) = BaseGalleryInfo(
        gid = gid,
        token = "t$gid",
        title = title,
        posted = ParserUtils.formatDate(minutes(minutes)),
        simpleLanguage = language,
    )

    private fun minutes(minutes: Long) = 1_700_000_000_000L + minutes * 60_000
}
