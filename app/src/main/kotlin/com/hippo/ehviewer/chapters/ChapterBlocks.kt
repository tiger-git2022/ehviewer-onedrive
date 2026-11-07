package com.hippo.ehviewer.chapters

import kotlinx.serialization.Serializable

/** Fork: a favorited gallery found in a tankoubon, see [ChapterBlocks]. */
@Serializable
data class ChapterSource(
    val gid: Long,
    val token: String,
    val title: String,
    /** Its cover, to show it; null when not known. */
    val thumbKey: String? = null,
)

/**
 * Fork: pages [first] to [last] of a tankoubon, from 1; [sources] the favorites found there (more than one when several
 * are the same chapter, in other languages), none when no favorite is.
 */
@Serializable
data class ChapterBlock(val first: Int, val last: Int, val sources: List<ChapterSource> = emptyList()) {
    constructor(first: Int, last: Int, source: ChapterSource) : this(first, last, listOf(source))

    val source get() = sources.firstOrNull()
    val size get() = last - first + 1
    val pages get() = first..last
}

object ChapterBlocks {
    /**
     * The tankoubon of [pages] pages cut in blocks: one for each range of [found] (pages from 0) with all the favorites
     * found there, and those no favorite is in between. The blocks of favorites may overlap (one of pages 1-40 and
     * another of 1-180): each is kept rather than one hiding the other. In the order of the book, the longer first.
     */
    fun build(pages: Int, found: List<Pair<IntRange, ChapterSource>>): List<ChapterBlock> {
        val bySpan = LinkedHashMap<IntRange, MutableList<ChapterSource>>()
        found.forEach { (range, source) ->
            val first = range.first.coerceAtLeast(0)
            val last = range.last.coerceAtMost(pages - 1)
            if (first > last) return@forEach
            val list = bySpan.getOrPut(first + 1..last + 1) { ArrayList() }
            if (list.none { it.gid == source.gid }) list += source
        }
        val covered = BooleanArray(pages)
        bySpan.keys.forEach { span -> span.forEach { covered[it - 1] = true } }
        val gaps = ArrayList<ChapterBlock>()
        var start = -1
        for (page in 0..pages) {
            val free = page < pages && !covered[page]
            if (free && start < 0) start = page
            if (!free && start >= 0) {
                gaps += ChapterBlock(start + 1, page)
                start = -1
            }
        }
        val favorites = bySpan.map { (span, sources) -> ChapterBlock(span.first, span.last, sources.toList()) }
        return (favorites + gaps).sortedWith(compareBy({ it.first }, { -it.size }))
    }

    /**
     * [blocks] as the segments of a bar, side by side: cut where any block starts or ends, each with the favorites of
     * all the blocks over it.
     */
    fun segments(pages: Int, blocks: List<ChapterBlock>): List<ChapterBlock> {
        if (pages <= 0) return emptyList()
        val cuts = sortedSetOf(1, pages + 1)
        blocks.forEach {
            cuts += it.first.coerceIn(1, pages + 1)
            cuts += (it.last + 1).coerceIn(1, pages + 1)
        }
        return cuts.zipWithNext { first, next ->
            val last = next - 1
            val sources = blocks.filter { it.first <= first && it.last >= last }.flatMap { it.sources }.distinctBy { it.gid }
            ChapterBlock(first, last, sources)
        }
    }

    /** How many of the [pages] pages a favorite is found in. */
    fun favoritedPages(pages: Int, blocks: List<ChapterBlock>): Int {
        val covered = BooleanArray(pages)
        blocks.filter { it.sources.isNotEmpty() }.forEach { block -> block.pages.forEach { if (it in 1..pages) covered[it - 1] = true } }
        return covered.count { it }
    }
}
