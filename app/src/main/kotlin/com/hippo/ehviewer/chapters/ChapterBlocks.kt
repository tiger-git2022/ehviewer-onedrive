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

/** Fork: pages [first] to [last] of a tankoubon, from 1; [source] the favorite they are, null when none is. */
@Serializable
data class ChapterBlock(val first: Int, val last: Int, val source: ChapterSource? = null) {
    val size get() = last - first + 1
    val pages get() = first..last
}

object ChapterBlocks {
    /**
     * The tankoubon of [pages] pages cut in blocks: those of [found] (pages from 0), the others in between. A page is
     * taken by the chapter found first there.
     */
    fun build(pages: Int, found: List<Pair<IntRange, ChapterSource>>): List<ChapterBlock> {
        val owner = arrayOfNulls<ChapterSource>(pages)
        found.sortedBy { it.first.first }.forEach { (range, source) ->
            for (page in range.first.coerceAtLeast(0)..range.last.coerceAtMost(pages - 1)) {
                if (owner[page] == null) owner[page] = source
            }
        }
        val blocks = ArrayList<ChapterBlock>()
        var start = 0
        for (page in 1..pages) {
            if (page == pages || owner[page] != owner[start]) {
                blocks += ChapterBlock(start + 1, page, owner[start])
                start = page
            }
        }
        return blocks
    }
}
