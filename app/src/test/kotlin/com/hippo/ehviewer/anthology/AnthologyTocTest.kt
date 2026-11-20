package com.hippo.ehviewer.anthology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthologyTocTest {
    private val gid = 3045678L

    // The way of the first screenshot: page - romanized / as written ..... title, the pages linked
    private val namesFirst = """
        Cover - Matsuzawa Muni / 松沢夢丹

        005 - Matsuzawa Muni / 松沢夢丹 ......................................... 乳辱エージェント
        029 - Hoshino Ryuichi / 星野竜一 ........................................ 性服者～堕ちた人妻たち～
        049 - Iketatsu Shizuru / 池竜静留 ....................................... 覚性
        073 - Toba Yuga / 跳馬遊鹿 .............................................. 妻低
        093 - Akikusa Peperon / 秋草ぺぺろん ..................................... 千夜子の秘密
    """.trimIndent()

    private fun linksOf(text: String, shift: Int = 0) = Regex("""(?m)^(\d{3})""").findAll(text).map {
        TextLink(it.range.first, it.range.last + 1, "https://e-hentai.org/s/0123abcd45/$gid-${it.value.toInt() + shift}")
    }.toList()

    @Test
    fun namesThenTitle() {
        val entries = assertNotNull(AnthologyToc.parse(namesFirst, linksOf(namesFirst), gid, 120))
        assertEquals(listOf("Cover", "005", "029", "049", "073", "093"), entries.map { it.label })
        val first = entries[1]
        assertEquals(listOf("Matsuzawa Muni", "松沢夢丹"), first.names.map { it.text })
        assertEquals("乳辱エージェント", first.title)
        assertEquals(5..28, first.pages)
        assertEquals(1..4, entries[0].pages)
        assertNull(entries[0].title)
        // The last one up to the end of the gallery
        assertEquals(93..120, entries.last().pages)
        // The names where they are in the text, to make them links
        val name = first.names[1]
        assertEquals("松沢夢丹", namesFirst.substring(name.start, name.end))
        assertTrue(first.names[0].latin)
        assertFalse(first.names[1].latin)
        assertEquals("妻低", entries[4].title)
    }

    @Test
    fun linkedPagesMoveTheWrittenOnes() {
        // The links two pages further than written (two pages of ads first): the lines without links move as well
        val links = linksOf(namesFirst, shift = 2).take(2)
        val entries = assertNotNull(AnthologyToc.parse(namesFirst, links, gid, 130))
        assertEquals(listOf(7, 31, 51, 75, 95), entries.drop(1).map { it.first })
        // Links to another gallery are not taken
        val other = AnthologyToc.parse(namesFirst, linksOf(namesFirst, 2).map { it.copy(url = it.url.replace("$gid", "999")) }, gid, 130)
        assertEquals(5, assertNotNull(other)[1].first)
    }

    // The way of the second screenshot: page ■ title ..... as written / romanized, under a heading
    private val titleFirst = """
        COMIC 真激 2025年1月号

        Cover Illustration 茶野みな / Chano Mina

        Contents:
        008 ■ 自称不感症理系女子 小松田みちる (27)【後編】 .......................................... doumou / Doumou
        054 ■ 汗だく岬姉ちゃんたちと秘密の狂宴 .......................................... ........................ 増多部翔 / Masutabe Sho
        082 ■ 幼馴染と射精100回できるかな!?【後編】 ............................ 茶野みな / Chano Mina
        126 ■ 新 友達の母親【第14話】 ....................................... gonza / Gonza
        156 ■ 二人だけの秘密【第5話】 .................................................. たくわん / Takuwan
        182 ■ No Mark ........................................................ むーにゃん / Muunyan
    """.trimIndent()

    @Test
    fun titleThenNames() {
        val entries = assertNotNull(AnthologyToc.parse(titleFirst, emptyList(), gid, 220))
        // The cover artist above the heading is no entry
        assertEquals(listOf("008", "054", "082", "126", "156", "182"), entries.map { it.label })
        assertEquals("自称不感症理系女子 小松田みちる (27)【後編】", entries[0].title)
        assertEquals(listOf("doumou", "Doumou"), entries[0].names.map { it.text })
        assertEquals("汗だく岬姉ちゃんたちと秘密の狂宴", entries[1].title)
        assertEquals(listOf("増多部翔", "Masutabe Sho"), entries[1].names.map { it.text })
        // "gonza / Gonza" tells no side by its scripts, the others do
        assertEquals("新 友達の母親【第14話】", entries[3].title)
        assertEquals(listOf("gonza", "Gonza"), entries[3].names.map { it.text })
        assertEquals("No Mark", entries[5].title)
        assertEquals(8..53, entries[0].pages)
        assertEquals(182..220, entries[5].pages)
    }

    @Test
    fun noContents() {
        assertNull(AnthologyToc.parse("mc in p47。", emptyList(), gid, 100))
        assertNull(AnthologyToc.parse("Thoughts:\n1. The art is great\n2. The story drags\n3. Too short\n4. Still worth it", emptyList(), gid, 100))
    }

    // Series link list with a trailing date ("2026.11.14") must NOT be misparsed as an anthology TOC,
    // and all gallery links in it are extracted for per-link favorite folder badges
    @Test
    fun seriesLinksWithDateNotTocAndLinksExtracted() {
        val comment = """
            图源：https://exhentai.org/g/3378935/d9db8fdb27/

            1：https://exhentai.org/g/3113227/4e2faa61f0/
            2：https://exhentai.org/g/3115032/765c30fc9c/
            3：https://exhentai.org/g/3210792/5ead577971/
            4：https://exhentai.org/g/4326020/cadc2a4086/
            5：本书
            6：https://exhentai.org/g/4332940/818bc0fdfd/

            既然能闻到味道，那你这道具不和棍木没区别吗

            B_Meow
            2026.11.14
        """.trimIndent()
        assertNull(AnthologyToc.parse(comment, emptyList(), gid, 30))
        val refs = AnthologyToc.findGalleryLinks(comment, emptyList())
        assertEquals(listOf(3378935L, 3113227L, 3115032L, 3210792L, 4326020L, 4332940L), refs.map { it.gid })
        refs.forEach { ref ->
            assertEquals(ref.url, comment.substring(ref.start, ref.end))
        }
    }

    // Two-line magazine TOC with Color Comic / Pinup pages from the back listed before Comic, and 『title』 on the next line
    @Test
    fun twoLineSectionsOutOfOrder() {
        val comment = """
            Cover - Shiokonbu / しおこんぶ

            Color Comic
            628 - Tamazatou / 玉砂糖 ..........................................
            『ふしぎカノジョラブ契約中 第3話』
            641 - Chiri / ちり ................................................
            『僕と彼女の搾精性活8』

            Pinup -DO NOT TAG-
            645 - Minamoto / 源 ...............................................
            『バベルPin-Up Girls 296』
            646 - Mebura / めぶら .............................................
            『バベルPin-Up Girls 297』
            647 - Nurumayu / ぬるまゆ .........................................
            『バベルPin-Up Girls 298』

            Comic
            003 - Tohyama Eight / 東山エイト ..................................
            『キミは俺の本指名(推し)！ 1話』
            040 - Takota Konu / 蛸田こぬ ......................................
            『年上彼女の落とし方。2』
            063 - Cucchiore / くっきおーれ ....................................
            『エール』
        """.trimIndent()
        val entries = assertNotNull(AnthologyToc.parse(comment, emptyList(), gid, 650))
        assertEquals(listOf("Cover", "628", "641", "645", "646", "647", "003", "040", "063"), entries.map { it.label })
        val colorFirst = entries[1]
        assertEquals(listOf("Tamazatou", "玉砂糖"), colorFirst.names.map { it.text })
        assertEquals("ふしぎカノジョラブ契約中 第3話", colorFirst.title)
        assertEquals(628..640, colorFirst.pages)
        assertEquals("『ふしぎカノジョラブ契約中 第3話』", comment.substring(colorFirst.lineEnd - "『ふしぎカノジョラブ契約中 第3話』".length, colorFirst.lineEnd))
        val comicFirst = entries[6]
        assertEquals(listOf("Tohyama Eight", "東山エイト"), comicFirst.names.map { it.text })
        assertEquals("キミは俺の本指名(推し)！ 1話", comicFirst.title)
        assertEquals(3..39, comicFirst.pages)
        assertEquals(1..2, entries[0].pages)
        assertEquals(63..627, entries.last().pages)
        assertEquals(647..650, entries[5].pages)
        // Artist name offsets match exact substrings in original comment text
        entries.forEach { entry ->
            entry.names.forEach { name ->
                assertEquals(name.text, comment.substring(name.start, name.end))
            }
        }
    }

    @Test
    fun titles() {
        assertTrue(AnthologyTitles.matches("乳辱エージェント", "[松沢夢丹] 乳辱エージェント (COMIC 真激 2025年1月号) [中国翻訳] [DL版]"))
        assertTrue(AnthologyTitles.matches("妻低", "[跳馬遊鹿] 妻低 (COMIC 真激 2024年10月号) [中国翻訳]"))
        assertFalse(AnthologyTitles.matches("妻低", "[跳馬遊鹿] 妻低い女 (COMIC 真激 2024年10月号)"))
        assertTrue(AnthologyTitles.matches("自称不感症理系女子 小松田みちる (27)【後編】", "[doumou] 自称不感症理系女子 小松田みちる(27) 後編 (COMIC 真激 2025年1月号) [中国翻訳]"))
        assertFalse(AnthologyTitles.matches("自称不感症理系女子 小松田みちる (27)【後編】", "[doumou] 自称不感症理系女子 小松田みちる(27) 前編 (COMIC 真激 2024年12月号)"))
        assertTrue(AnthologyTitles.matches("新 友達の母親【第14話】", "[gonza] 新 友達の母親 第14話 (COMIC 真激 2025年1月号) [中国翻訳]"))
        assertFalse(AnthologyTitles.matches("新 友達の母親【第14話】", "[gonza] 新 友達の母親 第13話 (COMIC 真激 2024年12月号)"))
        assertTrue(AnthologyTitles.matches("潜入捜査姦～囚われの美人姉妹～", "[ドモン] 潜入捜査姦 ～囚われの美人姉妹～ (COMIC 真激)"))
        assertFalse(AnthologyTitles.matches("覚性", "[池竜静留] 千夜子の秘密"))
    }

    @Test
    fun mindControlPages() {
        assertEquals(listOf(47..47), MindControlHints.pages("mc in p47。"))
        assertEquals(listOf(153..153), MindControlHints.pages("The mind control one starts at page 153"))
        assertEquals(listOf(50..59), MindControlHints.pages("催眠那篇在50多页"))
        assertEquals(listOf(47..47), MindControlHints.pages("第47页是催眠"))
        assertEquals(listOf(47..47, 92..92), MindControlHints.pages("MC: p47, p92"))
        // A page of another thing in the next clause is not taken
        assertEquals(listOf(47..47), MindControlHints.pages("mc in p47, ntr in p80"))
        assertEquals(listOf(30..45), MindControlHints.pages("Hypnosis pages 30-45"))
        assertEquals(listOf(12..12), MindControlHints.pages("p12\n洗脳"))
        assertEquals(emptyList(), MindControlHints.pages("求个催眠本推荐"))
        assertEquals(emptyList(), MindControlHints.pages("第119页戴手套"))
        assertEquals(emptyList(), MindControlHints.pages("the MC is so dumb lol"))
        // "mc" inside a word is not it
        assertEquals(emptyList(), MindControlHints.pages("mcdonalds on p5"))
        assertTrue(MindControlHints.isMindControlTitle("催眠性指導 第3話"))
        assertFalse(MindControlHints.isMindControlTitle("MC Hammer"))
        // Hidden with a sign for a character
        assertTrue(MindControlHints.isMindControlTitle("催○アプリで生意気な妹を"))
        assertTrue(MindControlHints.isMindControlTitle("催o性指導"))
        assertTrue(MindControlHints.isMindControlTitle("催●術にかかった彼女"))
        assertTrue(MindControlHints.isMindControlTitle("●眠おじさん"))
        assertTrue(MindControlHints.isMindControlTitle("洗○された人妻"))
        assertTrue(MindControlHints.isMindControlTitle("さいみん彼女"))
        assertFalse(MindControlHints.isMindControlTitle("催促されて"))
        assertFalse(MindControlHints.isMindControlTitle("洗濯物"))
        assertFalse(MindControlHints.isMindControlTitle("睡眠不足"))
        assertEquals(listOf(52..52), MindControlHints.pages("催○那篇在p52"))
    }
}
