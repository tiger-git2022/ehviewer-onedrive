package com.hippo.ehviewer.anthology

import com.hippo.ehviewer.translate.CommentListing

/** Fork: a link in the text of a comment, over [start] until [end] of the text. */
data class TextLink(val start: Int, val end: Int, val url: String)

/** Fork: a name of the artist of an entry, at [start] until [end] of the text of the comment. */
data class TocName(val text: String, val start: Int, val end: Int) {
    /** Romanized ("Matsuzawa Muni") rather than as written ("松沢夢丹"). */
    val latin get() = text.none(::isCjk)
}

/**
 * Fork: one work of a contents list, see [AnthologyToc]: [label] as written ("005", "Cover"), its pages [first] to
 * [last] of the gallery (from 1), the names of its artist and its title, either may be missing. [lineEnd] is where its
 * line ends in the text, to mark it there.
 */
data class TocEntry(
    val label: String,
    val first: Int,
    val last: Int,
    val names: List<TocName>,
    val title: String?,
    val lineStart: Int,
    val lineEnd: Int,
) {
    val pages get() = first..last

    /** A key that stays the same for the same entry, to keep what was found for it. */
    val key get() = "$label|${title?.let(AnthologyTitles::key).orEmpty()}"
}

/**
 * Fork: the contents of an anthology or a magazine issue, as a comment lists them, in either of the two usual ways:
 *
 *     005 - Matsuzawa Muni / 松沢夢丹 ........................ 乳辱エージェント
 *     008 ■ 自称不感症理系女子 小松田みちる (27)【後編】 .......... doumou / Doumou
 *
 * The side of the names is the one of two names, romanized and as written, apart by a slash; a line where neither side
 * tells goes the way most of the others go. The page of an entry is the one its link leads to, when it has one (to the
 * page in the gallery), else the number written, moved as much as the others with links are.
 */
object AnthologyToc {
    // "005 - ", "008 ■ ", "p.12 ", "Cover - "; a cover "Cover Illustration 茶野みな / Chano Mina" above the list is none
    private val ENTRY = Regex(
        """^(?:(cover|表紙|カバー)\s*[-–—:：|]+\s*|(?:pp?\.?\s*)?(\d{1,4})\s*(?:[-–—~～:：.)）|■□◆◇●○★☆▪▶►]+\s*|\s+))(\S.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val LEADER = Regex("""\s*(?:\.{3,}|…{2,}|・{3,}|·{3,}|‥{2,}|-{4,}|_{4,}|―{2,})\s*""")
    private val SLASH = Regex("""\s*[/／]\s*""")
    private val HEADING = Regex(
        """^[\p{P}\p{S}\s]*(?:table of contents|contents|目次|もくじ|目录|目錄|收录|收錄|収録作品|収録)[\p{P}\p{S}\s]*$""",
        RegexOption.IGNORE_CASE,
    )

    // A link to a page of the gallery: https://e-hentai.org/s/0123abcdef/1234567-5
    private val PAGE_LINK = Regex("""/s/[0-9a-f]+/(\d+)-(\d+)""")
    private const val MIN_ENTRIES = 3

    private class Line(val label: String, val number: Int?, val start: Int, val end: Int, val rest: String, val restStart: Int)

    /** The contents listed in [text] of a comment on the gallery [gid] of [pages] pages, null when it lists none. */
    fun parse(text: String, links: List<TextLink>, gid: Long, pages: Int): List<TocEntry>? {
        if (!CommentListing.isListing(text)) return null
        val lines = ArrayList<Line>()
        var offset = 0
        var heading = false
        text.split('\n').forEach { raw ->
            val start = offset + (raw.length - raw.trimStart().length)
            val line = raw.trim()
            offset += raw.length + 1
            if (HEADING.matches(line)) {
                heading = true
                lines.clear()
                return@forEach
            }
            val match = ENTRY.find(line) ?: return@forEach
            val label = match.groupValues[1].ifEmpty { match.groupValues[2] }
            val rest = match.groups[3]!!
            lines += Line(label, match.groupValues[2].toIntOrNull(), start, start + line.length, rest.value, start + rest.range.first)
        }
        if (lines.size < MIN_ENTRIES) return null
        // Where the names are: Left, Right, or neither told
        val sides = lines.map { line -> namesSide(line.rest) }
        val left = sides.count { it == Side.Left }
        val right = sides.count { it == Side.Right }
        val usual = if (right > left) Side.Right else Side.Left
        // The page each link leads to
        val linked = lines.map { line ->
            links.filter { it.start < line.end && it.end > line.start }.firstNotNullOfOrNull { link ->
                PAGE_LINK.find(link.url)?.takeIf { it.groupValues[1].toLongOrNull() == gid }?.groupValues?.get(2)?.toIntOrNull()
            }
        }
        // The pages written as far from those of the gallery as the linked ones are
        val shift = lines.indices.mapNotNull { i -> lines[i].number?.let { n -> linked[i]?.minus(n) } }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
        val firsts = lines.indices.map { i -> linked[i] ?: lines[i].number?.plus(shift) ?: 1 }
        // Pages go up through the issue; a list that does not is no contents of this gallery
        if (firsts.zipWithNext().any { (a, b) -> b < a }) return null
        val entries = lines.indices.map { i ->
            val line = lines[i]
            val first = firsts[i].coerceAtLeast(1).let { if (pages > 0) it.coerceAtMost(pages) else it }
            val next = firsts.drop(i + 1).firstOrNull { it > firsts[i] }
            val last = when {
                next != null -> next - 1
                pages > 0 -> pages
                else -> first
            }.let { if (pages > 0) it.coerceAtMost(pages) else it }.coerceAtLeast(first)
            val side = sides[i].takeIf { it != Side.None } ?: usual
            val (names, title) = split(line, side)
            TocEntry(line.label, first, last, names, title, line.start, line.end)
        }
        if (entries.count { it.names.isNotEmpty() || it.title != null } < MIN_ENTRIES) return null
        return entries
    }

    private enum class Side { Left, Right, None }

    private fun parts(text: String) = text.split(SLASH).map(String::trim).filter(String::isNotEmpty)

    /** How much [text] reads as the names of an artist: romanized and as written, apart by a slash. */
    private fun nameScore(text: String): Int {
        val parts = parts(text)
        if (parts.size < 2 || parts.any { it.length > 40 }) return 0
        return if (parts.any { p -> p.none(::isCjk) } && parts.any { p -> p.any(::isCjk) }) 2 else 1
    }

    private fun namesSide(rest: String): Side {
        val pieces = LEADER.split(rest)
        if (pieces.size < 2) return Side.None
        val l = nameScore(pieces.first())
        val r = nameScore(pieces.last())
        return when {
            l > r -> Side.Left
            r > l -> Side.Right
            else -> Side.None
        }
    }

    /** The names and the title of [line], the names on [side] of the leader. */
    private fun split(line: Line, side: Side): Pair<List<TocName>, String?> {
        val leaders = LEADER.findAll(line.rest).toList()
        if (leaders.isEmpty()) {
            // A line without a leader: names alone ("Cover - Shindou / しんどう"), or a title alone
            return if (nameScore(line.rest) > 0) names(line.rest, line.restStart) to null else emptyList<TocName>() to line.rest.trim().ifEmpty { null }
        }
        val leftText = line.rest.substring(0, leaders.first().range.first)
        val rightStart = leaders.last().range.last + 1
        val rightText = line.rest.substring(rightStart)
        return if (side == Side.Right) {
            names(rightText, line.restStart + rightStart) to leftText.trim().ifEmpty { null }
        } else {
            names(leftText, line.restStart) to rightText.trim().ifEmpty { null }
        }
    }

    private fun names(text: String, start: Int): List<TocName> {
        val result = ArrayList<TocName>()
        var from = 0
        (SLASH.findAll(text).map { it.range } + sequenceOf(text.length until text.length)).forEach { cut ->
            val piece = text.substring(from, cut.first)
            val trimmedStart = piece.length - piece.trimStart().length
            val name = piece.trim()
            if (name.isNotEmpty()) result += TocName(name, start + from + trimmedStart, start + from + trimmedStart + name.length)
            from = cut.last + 1
        }
        return result
    }
}

/** Fork: Chinese characters or kana. */
internal fun isCjk(c: Char) = when (Character.UnicodeScript.of(c.code)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
    else -> c == 'ー'
}
