package com.hippo.ehviewer.anthology

import com.hippo.ehviewer.chapters.ChapterTitles
import java.text.Normalizer

/**
 * Fork: a title in a contents list against the title of a gallery of one work: "乳辱エージェント" is
 * "[松沢夢丹] 乳辱エージェント (COMIC 真激 2025年1月号) [中国翻訳] [DL版]". Told by the title without what is in
 * brackets, its letters and digits only; the part ("前編", "後編", "第5話") must not differ.
 */
object AnthologyTitles {
    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val PART = Regex("""(前|中|後|后)\s*[編篇编]|第\s*(\d+)\s*[話话回章]|(?:part|chapter|ch\.?|vol\.?)\s*(\d+)""", RegexOption.IGNORE_CASE)

    /** Taken as one title by containing the other only that long, a short title only when the same. */
    private const val MIN_CONTAINED = 4

    fun key(title: String): String = NOT_WORD.replace(ChapterTitles.core(title), "")

    private fun parts(title: String) = PART.findAll(Normalizer.normalize(title, Normalizer.Form.NFKC)).map { m ->
        m.groupValues[1].replace('后', '後').ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] }.trimStart('0') }
    }.toSet()

    fun matches(entryTitle: String, galleryTitle: String): Boolean {
        val a = key(entryTitle)
        val b = key(galleryTitle)
        if (a.isEmpty() || b.isEmpty()) return false
        val same = a == b || (minOf(a.length, b.length) >= MIN_CONTAINED && (a in b || b in a))
        if (!same) return false
        val pa = parts(entryTitle)
        val pb = parts(galleryTitle)
        return pa.isEmpty() || pb.isEmpty() || pa.any { it in pb }
    }
}

/**
 * Fork: comments telling where the mind control (催眠) work of an anthology is: "mc in p47", "The mind control one
 * starts at page 153", "催眠那篇在50多页". The pages are those said next to the word, in the same clause or in clauses
 * of pages alone after it ("mc: p47, p92"); a comment of one page and the word anywhere counts too. Without a page,
 * nothing ("求个催眠本推荐").
 */
object MindControlHints {
    /**
     * The words of mind control, written out or with a sign for a character, as a title or a comment hides them:
     * "催○", "催o", "催●術", "●眠", "洗○", "さいみん". "MC" is not among them, too short to be sure of in a title.
     */
    private const val WORDS = """mind[\s-]*control|hypno|催眠|洗脑|洗腦|洗脳|さいみん|サイミン|せんのう|センノウ""" +
        """|催[○〇●◯◎□■◇◆×✕✖*＊?？xo0_＿♡♥☆★]|[○〇●◯◎□■◇◆×✕✖*＊♡♥☆★]眠|洗[○〇●◯◎□■◇◆×✕✖*＊?？xo0_＿♡♥☆★]"""

    private val KEYWORD = Regex("""(?<![\p{L}\p{N}])mc(?![\p{L}\p{N}])|$WORDS""", RegexOption.IGNORE_CASE)

    private val TITLE_KEYWORD = Regex(WORDS, RegexOption.IGNORE_CASE)

    // "p47", "p.47", "page 153", "pages 30-45", "pg 12"; after Chinese too ("在p47")
    private val LATIN_PAGE = Regex("""(?<![a-z0-9])(?:pages?|pgs?|p)\.?\s*(\d{1,4})(?:\s*(?:-|–|~|～|to)\s*(?:p\.?\s*)?(\d{1,4}))?""", RegexOption.IGNORE_CASE)

    // "第47页", "第 47 頁", "47页", "47ページ", "50多页" (50 to 59), "100多页" (100 to 199), "47-60页"
    private val CJK_PAGE = Regex("""第?\s*(\d{1,4})\s*(?:[-–~～到至]\s*(\d{1,4})\s*)?(多|几|幾)?\s*(?:[页頁]|ページ|p(?![\p{L}]))(?![数數])""", RegexOption.IGNORE_CASE)
    private val CLAUSE = Regex("""[\n。！？!?；;，,、]""")
    private val LETTER = Regex("""\p{L}""")

    /** A comment of one page and the word is that short at most. */
    private const val SHORT = 80

    fun isMindControlTitle(title: String) = TITLE_KEYWORD.containsMatchIn(title)

    /** The pages (from 1) the mind control work is said to be at in [text], none when it says no such thing. */
    fun pages(text: String): List<IntRange> {
        if (!KEYWORD.containsMatchIn(text)) return emptyList()
        val clauses = CLAUSE.split(text)
        val result = ArrayList<IntRange>()
        var following = false
        clauses.forEach { clause ->
            val pages = pagesIn(clause)
            when {
                KEYWORD.containsMatchIn(clause) -> {
                    result += pages
                    following = true
                }
                following && pages.isNotEmpty() && !LETTER.containsMatchIn(stripPages(clause)) -> result += pages
                else -> following = false
            }
        }
        if (result.isEmpty() && text.length <= SHORT) {
            pagesIn(text).singleOrNull()?.let { result += it }
        }
        return result.distinct()
    }

    private fun stripPages(text: String) = CJK_PAGE.replace(LATIN_PAGE.replace(text, " "), " ")

    private fun pagesIn(text: String): List<IntRange> {
        val taken = BooleanArray(text.length)
        val found = ArrayList<Pair<Int, IntRange>>()
        fun add(m: MatchResult, range: IntRange) {
            if (m.range.any { taken[it] }) return
            m.range.forEach { taken[it] = true }
            if (range.first in 1..9999 && range.last >= range.first) found += m.range.first to range
        }
        CJK_PAGE.findAll(text).forEach { m ->
            val first = m.groupValues[1].toInt()
            val to = m.groupValues[2].toIntOrNull()
            val range = when {
                to != null -> first..to
                // "50多页": fifty something; "100多页": a hundred something
                m.groupValues[3].isNotEmpty() -> first..(first + if (first >= 100 && first % 100 == 0) 99 else 9)
                else -> first..first
            }
            add(m, range)
        }
        LATIN_PAGE.findAll(text).forEach { m ->
            val first = m.groupValues[1].toInt()
            add(m, first..(m.groupValues[2].toIntOrNull()?.takeIf { it >= first } ?: first))
        }
        return found.sortedBy { it.first }.map { it.second }
    }
}
