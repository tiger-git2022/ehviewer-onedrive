package com.hippo.ehviewer.download

/**
 * Fork: the pages picked for a partial download, as typed: "1-30, 60-80", "5", "60-" (to the last page), "-10" (from
 * the first). Numbers start at 1. Commas (also "，" and "、"), semicolons and spaces separate the ranges; "~" and the
 * long dashes work as "-". A range typed backwards is turned round.
 */
data class PageRange(val first: Int, val last: Int?) {
    override fun toString() = when (last) {
        null -> "$first-"
        first -> "$first"
        else -> "$first-$last"
    }
}

object PageRanges {
    private val DASH = Regex("\\s*[-~～–—]\\s*")
    private val SEPARATOR = Regex("[,，、;；\\s]+")
    private val RANGE = Regex("(\\d*)-(\\d*)")

    /** The ranges in [text]; empty when there are none; null when a part is not a page or a range of pages. */
    fun parse(text: String): List<PageRange>? {
        val tokens = text.replace(DASH, "-").split(SEPARATOR).filter { it.isNotEmpty() }
        return tokens.map { token ->
            // Not "-10", which toIntOrNull reads as a negative number
            if (token.all(Char::isDigit)) {
                val page = token.toIntOrNull()?.takeIf { it >= 1 } ?: return null
                return@map PageRange(page, page)
            }
            val match = RANGE.matchEntire(token) ?: return null
            val (a, b) = match.destructured
            if (a.isEmpty() && b.isEmpty()) return null
            val from = if (a.isEmpty()) 1 else a.toIntOrNull() ?: return null
            val to = if (b.isEmpty()) null else b.toIntOrNull() ?: return null
            if (from < 1 || to != null && to < 1) return null
            if (to != null && to < from) PageRange(to, from) else PageRange(from, to)
        }
    }

    /** The pages of [ranges] in a gallery of [pages] pages, from 0, in order; those past the last page are left out. */
    fun indices(ranges: List<PageRange>, pages: Int): List<Int> {
        val picked = BooleanArray(pages.coerceAtLeast(0))
        ranges.forEach { range ->
            val last = minOf(range.last ?: pages, pages)
            for (page in range.first..last) picked[page - 1] = true
        }
        return picked.indices.filter { picked[it] }
    }

    /** The pages [picked] (from 0, a page of each) as the fewest ranges, in order. */
    fun runs(picked: BooleanArray): List<PageRange> {
        val ranges = ArrayList<PageRange>()
        var start = -1
        for (index in 0..picked.size) {
            val on = index < picked.size && picked[index]
            if (on && start < 0) start = index
            if (!on && start >= 0) {
                ranges += PageRange(start + 1, index)
                start = -1
            }
        }
        return ranges
    }

    /** [ranges] as text, to be read again by [parse]. */
    fun format(ranges: List<PageRange>) = ranges.joinToString(",")
}
