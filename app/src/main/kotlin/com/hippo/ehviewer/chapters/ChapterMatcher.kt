package com.hippo.ehviewer.chapters

/**
 * Fork: where the pages of a chapter gallery sit in a tankoubon, by the [ThumbHash] of their thumbnails. Pages are
 * counted from 0.
 *
 * A page of the chapter is found when its closest page of the book is clearly closer than the next closest one: a
 * cover, a credit page of the translators or an ad is close to nothing and found nowhere. The found pages vote for the
 * shift between the chapter and the book; a shift needs [MIN_HITS] votes, so a lone look-alike page counts for nothing.
 * The chapter then takes the book from its first found page to its last, without the pages of the translators or the
 * ads at the ends that the book does not have.
 */
object ChapterMatcher {
    /** At most this many differing bits of 256: a page of a scan of the same picture is mostly 20 to 50 apart. */
    const val MAX_DISTANCE = 70

    /** The closest page must be this much closer than the next one; other pages are around 100 apart. */
    const val RATIO = 0.6f

    const val MIN_HITS = 3

    /** Two runs of a chapter this close are one: a page the book adds or leaves out shifts the rest by one. */
    private const val JOIN_GAP = 2

    /** A cover alone has no other pages to vote with it: it has to be much closer. */
    private const val COVER_MAX_DISTANCE = 40
    private const val COVER_RATIO = 0.5f

    /** The page of [book] that is [page], or null when none is clearly it. */
    fun find(book: List<LongArray?>, page: LongArray, maxDistance: Int = MAX_DISTANCE, ratio: Float = RATIO): Int? {
        var best = -1
        var bestDistance = Int.MAX_VALUE
        var second = Int.MAX_VALUE
        book.forEachIndexed { index, hash ->
            hash ?: return@forEachIndexed
            val d = ThumbHash.distance(hash, page)
            if (d < bestDistance) {
                second = bestDistance
                bestDistance = d
                best = index
            } else if (d < second) {
                second = d
            }
        }
        if (best < 0 || bestDistance > maxDistance) return null
        // A book of one page has no second one to tell it from: the distance alone then
        if (second != Int.MAX_VALUE && bestDistance >= ratio * second) return null
        return best
    }

    /** The pages of [book] that [chapter] takes, in order; empty when it is not in the book. */
    fun locate(book: List<LongArray?>, chapter: List<LongArray?>): List<IntRange> {
        val byShift = HashMap<Int, MutableList<Int>>()
        chapter.forEachIndexed { index, hash ->
            hash ?: return@forEachIndexed
            val page = find(book, hash) ?: return@forEachIndexed
            byShift.getOrPut(page - index) { mutableListOf() } += page
        }
        val runs = byShift.values.filter { it.size >= MIN_HITS }.map { it.min()..it.max() }.sortedBy { it.first }
        return join(runs)
    }

    /** Where the book starts a chapter whose cover is [cover], by that page alone: for a gallery that is gone. */
    fun locateCover(book: List<LongArray?>, cover: LongArray): Int? = find(book, cover, COVER_MAX_DISTANCE, COVER_RATIO)

    private fun join(runs: List<IntRange>): List<IntRange> {
        val joined = ArrayList<IntRange>()
        runs.forEach { run ->
            val last = joined.lastOrNull()
            if (last != null && run.first <= last.last + 1 + JOIN_GAP) {
                joined[joined.lastIndex] = last.first..maxOf(last.last, run.last)
            } else {
                joined += run
            }
        }
        return joined
    }
}
