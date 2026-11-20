package com.hippo.ehviewer.anthology

import com.hippo.ehviewer.translate.CommentListing

/** Fork: a link in the text of a comment, over [start] until [end] of the text. */
data class TextLink(val start: Int, val end: Int, val url: String)

/** Fork: a link to an E-Hentai/ExHentai gallery found in a comment, at [start] until [end] of the text. */
data class GalleryLinkRef(val gid: Long, val token: String, val start: Int, val end: Int, val url: String)

/** Fork: a name of the artist of an entry, at [start] until [end] of the text of the comment. */
data class TocName(val text: String, val start: Int, val end: Int) {
    /** Romanized ("Matsuzawa Muni") rather than as written ("松沢夢丹"). */
    val latin get() = text.none(::isCjk)
}

/**
 * Fork: one work of a contents list, see [AnthologyToc]: [label] as written ("005", "Cover"), its pages [first] to
 * [last] of the gallery (from 1), the names of its artist and its title, either may be missing. [lineEnd] is where its
 * line ends in the text, to mark it there. [galleryLink] is a linked gallery (gid, token) on the line, if any.
 */
data class TocEntry(
    val label: String,
    val first: Int,
    val last: Int,
    val names: List<TocName>,
    val title: String?,
    val lineStart: Int,
    val lineEnd: Int,
    val galleryLink: Pair<Long, String>? = null,
) {
    val pages get() = first..last

    /** A key that stays the same for the same entry, to keep what was found for it. */
    val key get() = "$label|${galleryLink?.first ?: title?.let(AnthologyTitles::key).orEmpty()}"
}

/**
 * Fork: the contents of an anthology or a magazine issue, as a comment lists them, in either of the two usual ways:
 *
 *     005 - Matsuzawa Muni / 松沢夢丹 ........................ 乳辱エージェント
 *     008 ■ 自称不感症理系女子 小松田みちる (27)【後編】 .......... doumou / Doumou
 *
 * Also handles two-line entries where the title sits on the line right below the leader dots, and magazines that list
 * Color Comic / Pinup sections from the back of the issue before the main Comic section.
 *
 * The side of the names is the one of two names, romanized and as written, apart by a slash; a line where neither side
 * tells goes the way most of the others go. The page of an entry is the one its link leads to, when it has one (to the
 * page in the gallery), else the number written, moved as much as the others with links are.
 */
object AnthologyToc {
    // "005 - ", "008 ■ ", "p.12 ", "Cover - "; not a date like "2026.11.14" or decimal like "1.5"
    private val ENTRY = Regex(
        """^(?:(cover|表紙|カバー)\s*[-–—:：|]+\s*|(?:pp?\.?\s*)?(\d{1,4})\s*(?:(?:[-–—~～:：)）|■□◆◇●○★☆▪▶►]|\.(?!\d))+\s*|\s+))(\S.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val DATE_LINE = Regex("""^(?:\d{4}[.\-/]\d{1,2}[.\-/]\d{1,2}|\d{1,2}[.\-/]\d{1,2}[.\-/]\d{2,4})(?:\s|$)""")
    private val URL_ONLY = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE)
    private val LEADER = Regex("""\s*(?:\.{3,}|…{2,}|・{3,}|·{3,}|‥{2,}|-{4,}|_{4,}|―{2,})\s*""")
    private val SLASH = Regex("""\s*[/／]\s*""")
    private val HEADING = Regex(
        """^[\p{P}\p{S}\s]*(?:table of contents|contents|目次|もくじ|目录|目錄|收录|收錄|収録作品|収録)[\p{P}\p{S}\s]*$""",
        RegexOption.IGNORE_CASE,
    )

    // A link to a page of the gallery: https://e-hentai.org/s/0123abcdef/1234567-5
    private val PAGE_LINK = Regex("""/s/[0-9a-f]+/(\d+)-(\d+)""")

    // A link to a gallery: https://e-hentai.org/g/1234567/0123abcdef/
    private val GALLERY_LINK = Regex("""https?://(?:e-hentai|exhentai)\.org/g/(\d+)/([0-9a-f]+)/?""", RegexOption.IGNORE_CASE)
    private const val MIN_ENTRIES = 3

    private class RawLine(val trimmed: String, val start: Int, val end: Int)

    private class Line(
        val label: String,
        val number: Int?,
        val start: Int,
        val end: Int,
        val rest: String,
        val restStart: Int,
        /** Title/names on the line directly below trailing leader dots, if any. */
        val continuation: String? = null,
        val continuationStart: Int = 0,
    )

    /** All links to E-Hentai/ExHentai galleries in [text] (from [links] spans and raw URLs), ordered by position. */
    fun findGalleryLinks(text: String, links: List<TextLink>): List<GalleryLinkRef> {
        val result = ArrayList<GalleryLinkRef>()
        fun overlaps(start: Int, end: Int) = result.any { it.start < end && it.end > start }
        links.sortedBy { it.start }.forEach { link ->
            val m = GALLERY_LINK.find(link.url) ?: return@forEach
            val gid = m.groupValues[1].toLongOrNull() ?: return@forEach
            val token = m.groupValues[2]
            if (link.start in 0..text.length && link.end in link.start..text.length && !overlaps(link.start, link.end)) {
                result += GalleryLinkRef(gid, token, link.start, link.end, link.url)
            }
        }
        GALLERY_LINK.findAll(text).forEach { m ->
            val start = m.range.first
            val end = m.range.last + 1
            if (overlaps(start, end)) return@forEach
            val gid = m.groupValues[1].toLongOrNull() ?: return@forEach
            result += GalleryLinkRef(gid, m.groupValues[2], start, end, m.value)
        }
        result.sortBy { it.start }
        return result
    }

    /** The contents listed in [text] of a comment on the gallery [gid] of [pages] pages, null when it lists none. */
    fun parse(text: String, links: List<TextLink>, gid: Long, pages: Int): List<TocEntry>? {
        if (!CommentListing.isListing(text)) return null
        val rawLines = ArrayList<RawLine>()
        var offset = 0
        text.split('\n').forEach { raw ->
            val lead = raw.length - raw.trimStart().length
            val trimmed = raw.trim()
            val start = offset + lead
            rawLines += RawLine(trimmed, start, start + trimmed.length)
            offset += raw.length + 1
        }
        val lines = ArrayList<Line>()
        var i = 0
        while (i < rawLines.size) {
            val raw = rawLines[i]
            val line = raw.trimmed
            if (line.isEmpty()) {
                i++
                continue
            }
            if (HEADING.matches(line)) {
                lines.clear()
                i++
                continue
            }
            if (DATE_LINE.containsMatchIn(line)) {
                i++
                continue
            }
            val match = ENTRY.find(line)
            if (match == null) {
                i++
                continue
            }
            val restGroup = match.groups[3]!!
            if (URL_ONLY.matches(restGroup.value.trim())) {
                i++
                continue
            }
            val label = match.groupValues[1].ifEmpty { match.groupValues[2] }
            val number = match.groupValues[2].toIntOrNull()
            val restText = restGroup.value
            val restStart = raw.start + restGroup.range.first
            // Check if the line ends with leader dots and the next non-blank line directly below is its title
            val leaders = LEADER.findAll(restText).toList()
            val trailingLeader = leaders.isNotEmpty() && restText.substring(leaders.last().range.last + 1).isBlank()
            val nextRaw = if (trailingLeader) rawLines.getOrNull(i + 1) else null
            val hasContinuation = nextRaw != null && nextRaw.trimmed.isNotEmpty() &&
                !HEADING.matches(nextRaw.trimmed) && !DATE_LINE.containsMatchIn(nextRaw.trimmed) &&
                ENTRY.find(nextRaw.trimmed) == null
            if (hasContinuation) {
                lines += Line(
                    label = label,
                    number = number,
                    start = raw.start,
                    end = nextRaw!!.end,
                    rest = restText,
                    restStart = restStart,
                    continuation = nextRaw.trimmed,
                    continuationStart = nextRaw.start,
                )
                i += 2
            } else {
                lines += Line(label, number, raw.start, raw.end, restText, restStart)
                i++
            }
        }
        if (lines.size < MIN_ENTRIES) return null
        // Where the names are: Left, Right, or neither told
        val sides = lines.map { line -> namesSide(line) }
        val left = sides.count { it == Side.Left }
        val right = sides.count { it == Side.Right }
        val usual = if (right > left) Side.Right else Side.Left
        // The page each link leads to
        val linked = lines.map { line ->
            links.filter { it.start < line.end && it.end > line.start }.firstNotNullOfOrNull { link ->
                PAGE_LINK.find(link.url)?.takeIf { it.groupValues[1].toLongOrNull() == gid }?.groupValues?.get(2)?.toIntOrNull()
            }
        }
        val galleryLinks = findGalleryLinks(text, links)
        // The pages written as far from those of the gallery as the linked ones are
        val shift = lines.indices.mapNotNull { idx -> lines[idx].number?.let { n -> linked[idx]?.minus(n) } }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
        val firsts = lines.indices.map { idx -> linked[idx] ?: lines[idx].number?.plus(shift) ?: 1 }
        // Pages go up through each section; magazines sometimes list Color Comic / Pinup from the back before Comic
        val drops = firsts.zipWithNext().count { (a, b) -> b < a }
        val structured = lines.count { LEADER.containsMatchIn(it.rest) || nameScore(it.rest) > 0 } >= MIN_ENTRIES
        val ordered = drops == 0 || (structured && drops <= 2 && firsts.size >= 5 && drops * 3 < firsts.size)
        if (!ordered) return null
        val sortedFirsts = firsts.distinct().sorted()
        val entries = lines.indices.map { idx ->
            val line = lines[idx]
            val first = firsts[idx].coerceAtLeast(1).let { if (pages > 0) it.coerceAtMost(pages) else it }
            val next = sortedFirsts.firstOrNull { it > firsts[idx] }
            val last = when {
                next != null -> next - 1
                pages > 0 -> pages
                else -> first
            }.let { if (pages > 0) it.coerceAtMost(pages) else it }.coerceAtLeast(first)
            val side = sides[idx].takeIf { it != Side.None } ?: usual
            val (names, title) = split(line, side)
            val galleryLink = galleryLinks.firstOrNull { it.start < line.end && it.end > line.start && it.gid != gid }
                ?.let { it.gid to it.token }
            TocEntry(line.label, first, last, names, title, line.start, line.end, galleryLink)
        }
        if (entries.count { it.names.isNotEmpty() || it.title != null || it.galleryLink != null } < MIN_ENTRIES) return null
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

    private fun namesSide(line: Line): Side {
        val leaders = LEADER.findAll(line.rest).toList()
        if (leaders.isEmpty()) return Side.None
        val leftText = line.rest.substring(0, leaders.first().range.first)
        val rightText = line.continuation ?: line.rest.substring(leaders.last().range.last + 1)
        if (rightText.isBlank()) return Side.None
        val l = nameScore(leftText)
        val r = nameScore(rightText)
        return when {
            l > r -> Side.Left
            r > l -> Side.Right
            else -> Side.None
        }
    }

    private fun cleanTitle(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val unwrapped = when {
            trimmed.length >= 2 && trimmed.startsWith('『') && trimmed.endsWith('』') -> trimmed.substring(1, trimmed.length - 1).trim()
            trimmed.length >= 2 && trimmed.startsWith('「') && trimmed.endsWith('」') -> trimmed.substring(1, trimmed.length - 1).trim()
            else -> trimmed
        }
        return unwrapped.ifEmpty { trimmed }
    }

    /** The names and the title of [line], the names on [side] of the leader. */
    private fun split(line: Line, side: Side): Pair<List<TocName>, String?> {
        val leaders = LEADER.findAll(line.rest).toList()
        if (leaders.isEmpty()) {
            // A line without a leader: names alone ("Cover - Shindou / しんどう"), or a title alone
            return if (nameScore(line.rest) > 0) names(line.rest, line.restStart) to null else emptyList<TocName>() to cleanTitle(line.rest)
        }
        val leftText = line.rest.substring(0, leaders.first().range.first)
        val rightStart = if (line.continuation != null) line.continuationStart else line.restStart + leaders.last().range.last + 1
        val rightText = line.continuation ?: line.rest.substring(leaders.last().range.last + 1)
        return if (side == Side.Right) {
            names(rightText, rightStart) to cleanTitle(leftText)
        } else {
            names(leftText, line.restStart) to cleanTitle(rightText)
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
