package com.hippo.ehviewer.sync

import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Fork: finds what changed in a cloud slot whose count changed on the website from its [SlotSegments], instead of
 * reading every page again (a slot of 90 000 favorites is 1800 pages). E-Hentai has no way to count a part of a slot,
 * so the segments are checked one by one, from both ends in turn, and the check stops once the changes found make up
 * the difference of the counts. The pages are sorted by favorited time:
 * - new favorites, and favorites added again, come first: the first segment is always checked first;
 * - a segment read from its cursor shows its galleries still there; one of them missing is removed;
 * - a gallery of another slot moved here (it keeps its favorited time) shows up among the segment it falls in.
 */
object SlotVerifier {
    /** A favorites page: its galleries, newest first, and the cursor of the next one, null for the last. */
    data class Page(val galleries: List<IndexedGallery>, val next: String?)

    data class Result(
        /** Galleries of the known list of the slot no longer in it on the website. */
        val removed: Set<Long>,
        /** Galleries in the slot on the website the known list does not have in it. */
        val added: List<IndexedGallery>,
        /** The segments, those checked brought up to date. */
        val segments: List<Segment>,
        /** Segments checked. */
        val checked: Int,
    )

    /** Segment indexes from both ends in turn, the first one first: 0, n-1, 1, n-2, ... */
    fun order(count: Int): List<Int> = buildList {
        var front = 0
        var back = count - 1
        while (front <= back) {
            add(front++)
            if (front <= back) add(back--)
        }
    }

    /** Favorited time and gid of a cursor `<gid>-<favorited time>`, ordered like the pages (newest first). */
    private fun position(cursor: String?): Pair<Long, Long>? {
        val parts = cursor?.split('-') ?: return null
        if (parts.size != 2) return null
        val gid = parts[0].toLongOrNull() ?: return null
        val time = parts[1].toLongOrNull() ?: return null
        return time to gid
    }

    /** Whether the page whose last gallery is at cursor [next] went past the cursor [boundary]. */
    private fun reached(next: String?, boundary: String?): Boolean {
        if (next == null) return true
        val page = position(next) ?: return false
        val end = position(boundary) ?: return false
        return compareValuesBy(page, end, { it.first }, { it.second }) <= 0
    }

    /**
     * Checks the [segments] of a slot whose galleries on this device are [known], until the galleries added less
     * those removed make up [difference] (the count E-Hentai shows less the known one). [fetch] reads the page after
     * a cursor (null: the first page). Null when every segment is checked and the difference is still unexplained (the
     * segments do not fit any more): the slot is read page by page.
     *
     * Fork: supports resuming from [resume] and periodically saving progress via [onCheckpoint] (and immediately on any
     * error or cancellation) so an IP ban never throws away segments already checked.
     */
    suspend fun verify(
        segments: List<Segment>,
        known: Set<Long>,
        difference: Int,
        onChecked: (checked: Int, total: Int) -> Unit = { _, _ -> },
        resume: SlotVerifyCheckpoint? = null,
        saveEvery: Int = 5,
        expectedCount: Int = 0,
        userKey: String? = null,
        slot: Int = 0,
        now: Long = 0L,
        onCheckpoint: suspend (SlotVerifyCheckpoint) -> Unit = {},
        fetch: suspend (after: String?) -> Page,
    ): Result? {
        if (segments.isEmpty()) return null
        val segmentOf = HashMap<Long, Int>()
        segments.forEachIndexed { i, segment -> segment.gids.forEach { segmentOf.putIfAbsent(it, i) } }
        // Each segment checked becomes the pages read for it, so a segment grown by new favorites is not read whole
        // next time: the pages are the segments
        val updated = segments.map { listOf(it) }.toMutableList()
        val checkedSegments = LinkedHashSet<Int>()
        val seen = HashSet<Long>()
        val extraSeen = HashSet<Long>()
        val added = LinkedHashMap<Long, IndexedGallery>()
        val removed = HashSet<Long>()
        val startedAt = resume?.takeIf { it.segmentCount == segments.size && it.startedAt > 0L }?.startedAt ?: now
        if (resume != null && resume.segmentCount == segments.size) {
            val countChanged = expectedCount != 0 && resume.expectedCount != 0 && resume.expectedCount != expectedCount
            val keptChecked = resume.checked.filter { it in segments.indices && !(countChanged && it == 0) }
            keptChecked.forEach { i ->
                val list = resume.updated[i] ?: return@forEach
                updated[i] = list
                checkedSegments += i
                list.forEach { seg -> seg.gids.forEach { seen += it } }
            }
            if (!countChanged && 0 in checkedSegments) {
                extraSeen += resume.extraSeen
                seen += resume.extraSeen
            }
            resume.added.forEach { gallery ->
                if (gallery.gid in seen && gallery.gid !in known && gallery.gid !in added) added[gallery.gid] = gallery
            }
            checkedSegments.forEach { i ->
                segments[i].gids.filter { it in known && it !in seen }.forEach { removed += it }
            }
            if (checkedSegments.isNotEmpty()) onChecked(checkedSegments.size, segments.size)
        }
        fun net() = added.size - removed.size
        fun snapshot() = SlotVerifyCheckpoint(
            userKey = userKey,
            slot = slot,
            startedAt = startedAt,
            segmentCount = segments.size,
            expectedCount = expectedCount,
            checked = checkedSegments.toList(),
            updated = checkedSegments.associateWith { updated[it] },
            added = added.values.toList(),
            removed = removed.toSet(),
            extraSeen = extraSeen.toSet(),
        )
        var dirty = false
        var sinceSave = 0
        try {
            if (!(checkedSegments.isNotEmpty() && 0 in checkedSegments && net() == difference)) {
                for (i in order(segments.size)) {
                    if (i in checkedSegments) continue
                    // The galleries read for this segment, by the cursor of the page they came from
                    val pages = ArrayList<Pair<String?, MutableList<IndexedGallery>>>()
                    var cursor = segments[i].after
                    if (i == 0) {
                        // The newest favorites: new ones and favorites added again come first, read up to the next segment
                        val boundary = segments.getOrNull(1)?.after
                        while (true) {
                            val page = fetch(cursor)
                            pages += cursor to page.galleries.toMutableList()
                            if (boundary == null && page.next == null || reached(page.next, boundary)) break
                            // The same page again: nothing more to read
                            if (page.next == cursor) break
                            cursor = page.next ?: break
                        }
                        // The galleries of later segments after the last one of this segment are past its end; those before
                        // it were added again, they belong here now
                        var past = true
                        for ((_, galleries) in pages.asReversed()) {
                            val kept = galleries.indexOfLast { (segmentOf[it.gid] ?: 0) == 0 }
                            if (!past) continue
                            galleries.subList(kept + 1, galleries.size).forEach {
                                seen += it.gid
                                extraSeen += it.gid
                            }
                            galleries.subList(kept + 1, galleries.size).clear()
                            if (kept >= 0) past = false
                        }
                    } else {
                        val boundary = segments.getOrNull(i + 1)?.after
                        pages@ while (true) {
                            val page = fetch(cursor)
                            val galleries = ArrayList<IndexedGallery>()
                            pages += cursor to galleries
                            for (gallery in page.galleries) {
                                // Under the favorited time order, a gallery of a later segment is past the end of this one
                                if ((segmentOf[gallery.gid] ?: -1) > i) break@pages
                                galleries += gallery
                            }
                            // Ends at the next segment (unchanged, it ends right there) or past it
                            if (boundary != null && reached(page.next, boundary)) break
                            if (page.next == cursor) break
                            cursor = page.next ?: break
                        }
                    }
                    pages.forEach { (_, galleries) ->
                        galleries.forEach { gallery ->
                            seen += gallery.gid
                            if (gallery.gid !in known && gallery.gid !in added) added[gallery.gid] = gallery
                        }
                    }
                    val read = pages.filter { it.second.isNotEmpty() }.map { (after, galleries) -> Segment(after, galleries.map { it.gid }.distinct()) }
                    // A segment emptied keeps its cursor, so the segments still cover the slot from its start
                    updated[i] = read.ifEmpty { listOf(Segment(segments[i].after)) }.let { list ->
                        if (list.first().after == segments[i].after) list else listOf(Segment(segments[i].after)) + list
                    }
                    checkedSegments += i
                    // Gone from where it was and not seen anywhere else: removed
                    segments[i].gids.filter { it in known && it !in seen }.forEach { removed += it }
                    // Seen in a segment checked now (added again): no longer removed
                    removed.removeAll(seen)
                    dirty = true
                    sinceSave++
                    onChecked(checkedSegments.size, segments.size)
                    if (net() == difference) break
                    if (saveEvery > 0 && sinceSave >= saveEvery) {
                        onCheckpoint(snapshot())
                        dirty = false
                        sinceSave = 0
                    }
                }
            }
        } catch (e: Throwable) {
            if (dirty) {
                withContext(NonCancellable) {
                    runCatching { onCheckpoint(snapshot()) }
                }
            }
            throw e
        }
        // Seen in a segment checked later (added again): not removed after all
        removed.removeAll(seen)
        if (checkedSegments.size == segments.size) {
            // Everything was read: known galleries seen nowhere are gone too
            known.filterTo(removed) { it !in seen }
        }
        if (net() != difference) return null
        // A gallery listed by a segment checked now is dropped from the others
        val inChecked = checkedSegments.flatMapTo(HashSet()) { i -> updated[i].flatMap { it.gids } }
        val result = updated.flatMapIndexed { i, list ->
            if (i in checkedSegments) list else list.map { segment -> segment.copy(gids = segment.gids.filter { it !in inChecked && it !in removed }) }
        }
        return Result(removed = removed, added = added.values.toList(), segments = result, checked = checkedSegments.size)
    }

    /**
     * The segments of the slots read by a refresh: its [galleries] in the order read, cut by the [marks] of the pages
     * they came from.
     */
    fun segmentsOf(marks: List<PageMark>, galleries: List<IndexedGallery>): Map<Int, List<Segment>> {
        val result = LinkedHashMap<Int, MutableList<Segment>>()
        var from = 0
        for (mark in marks) {
            val to = minOf(from + mark.count, galleries.size)
            result.getOrPut(mark.slot) { ArrayList() } += Segment(mark.after, galleries.subList(from, to).map { it.gid })
            from = to
        }
        return result
    }
}

/** Fork: the [SlotSegments] of each slot, in [dir] as `slot-<n>.json.gz`. */
class SegmentStore(private val dir: File, private val json: Json) {
    private fun file(slot: Int) = File(dir, "slot-$slot.json.gz")

    fun load(slot: Int): SlotSegments? = file(slot).takeIf { it.exists() }
        ?.let { GidShards.readGzip(json, SlotSegments.serializer(), it) }
        ?.takeIf { it.version <= FAVORITES_INDEX_VERSION && it.slot == slot }

    fun save(segments: SlotSegments) = GidShards.writeGzip(json, SlotSegments.serializer(), segments, file(segments.slot))

    fun delete(slot: Int) {
        file(slot).delete()
    }
}

/** Fork: the mid-run [SlotVerifyCheckpoint] of each slot, in [dir] as `slot-<n>.json.gz`. */
class VerifyCheckpointStore(private val dir: File, private val json: Json) {
    private fun file(slot: Int) = File(dir, "slot-$slot.json.gz")

    fun load(slot: Int): SlotVerifyCheckpoint? = file(slot).takeIf { it.exists() }
        ?.let { GidShards.readGzip(json, SlotVerifyCheckpoint.serializer(), it) }
        ?.takeIf { it.version <= FAVORITES_INDEX_VERSION && it.slot == slot }

    fun save(checkpoint: SlotVerifyCheckpoint) = GidShards.writeGzip(json, SlotVerifyCheckpoint.serializer(), checkpoint, file(checkpoint.slot))

    fun delete(slot: Int) {
        file(slot).delete()
    }
}
