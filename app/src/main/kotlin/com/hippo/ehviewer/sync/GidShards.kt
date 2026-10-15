package com.hippo.ehviewer.sync

import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

/**
 * Fork: large gallery lists (tens of thousands of favorites) are kept in shards by gid range, so a change rewrites,
 * uploads and downloads only the shards it touches. A shard holds the gids from its [GidRange.minGid] up to the next
 * shard's. The ranges are kept from one write to the next and only change where a shard grows past [MAX_COUNT]
 * (split in halves) or two neighbours shrink to [MERGE_COUNT] together (merged): more data, more shards.
 */
object GidShards {
    /** A shard over this many entries is split in halves. */
    const val MAX_COUNT = 2_000

    /** Two neighbouring shards with this many entries or fewer together are merged. */
    const val MERGE_COUNT = 1_000

    /** Shard size of a list written the first time, leaving room to grow before splitting. */
    const val INITIAL_COUNT = 1_000

    /** Entries [from] until [to] of the gid sorted list, the gids from [minGid] up to the next range's. */
    data class GidRange(val minGid: Long, val from: Int, val to: Int) {
        val count get() = to - from
    }

    /**
     * Ranges of [gids] (ascending, distinct) starting at the [bounds] of the previous write (ascending min gids; empty
     * for a first write), split and merged as needed. The first range always starts at 0. Ranges are never empty,
     * except the single range of an empty list.
     */
    fun split(gids: LongArray, bounds: List<Long>): List<GidRange> {
        if (gids.isEmpty()) return listOf(GidRange(0, 0, 0))
        val ranges = ArrayList<GidRange>()
        if (bounds.isEmpty()) {
            var from = 0
            while (from < gids.size) {
                val to = minOf(from + INITIAL_COUNT, gids.size)
                ranges += GidRange(if (from == 0) 0 else gids[from], from, to)
                from = to
            }
            return ranges
        }
        // Assign to the previous ranges; an empty one is dropped, the range before it takes its gids
        val starts = (bounds.filter { it > 0 } + 0L).distinct().sorted()
        var from = 0
        for ((i, start) in starts.withIndex()) {
            val upper = starts.getOrElse(i + 1) { Long.MAX_VALUE }
            var to = from
            while (to < gids.size && gids[to] < upper) to++
            if (to > from) ranges += GidRange(start, from, to)
            from = to
        }
        // The first range starts at 0 whatever its first entry
        ranges[0] = ranges[0].copy(minGid = 0)
        // Split ranges grown too large, in halves until small enough
        val split = ArrayList<GidRange>()
        for (range in ranges) splitInto(range, gids, split)
        // Merge neighbours that shrank
        val merged = ArrayList<GidRange>()
        for (range in split) {
            val last = merged.lastOrNull()
            if (last != null && last.count + range.count <= MERGE_COUNT) {
                merged[merged.lastIndex] = last.copy(to = range.to)
            } else {
                merged += range
            }
        }
        return merged
    }

    private fun splitInto(range: GidRange, gids: LongArray, out: MutableList<GidRange>) {
        if (range.count <= MAX_COUNT) {
            out += range
            return
        }
        val middle = range.from + range.count / 2
        splitInto(GidRange(range.minGid, range.from, middle), gids, out)
        splitInto(GidRange(gids[middle], middle, range.to), gids, out)
    }

    /** Index of the range holding [gid], given the min gids of the ranges in ascending order. */
    fun rangeOf(minGids: LongArray, gid: Long): Int {
        var low = 0
        var high = minGids.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (minGids[mid] <= gid) low = mid else high = mid - 1
        }
        return low
    }

    /**
     * Order numbers keeping the order of a list across writes: entries keep their [old] number while the order allows
     * it, others get the next free one. Spaced by [ORDER_STEP] the first time, so an entry inserted between two others
     * changes nothing else. Removing or appending entries changes no other number.
     */
    fun orders(gids: List<Long>, old: Map<Long, Long>): LongArray {
        // Largest kept number from each position on: past it nothing later needs room
        val laterMax = LongArray(gids.size + 1)
        laterMax[gids.size] = Long.MIN_VALUE
        for (i in gids.indices.reversed()) laterMax[i] = maxOf(laterMax[i + 1], old[gids[i]] ?: Long.MIN_VALUE)
        val result = LongArray(gids.size)
        var previous = Long.MIN_VALUE
        for ((i, gid) in gids.withIndex()) {
            val kept = old[gid]
            val order = when {
                kept != null && kept > previous -> kept
                previous == Long.MIN_VALUE -> 0L
                // No later entry keeps a larger number: spaced, so later inserts fit in between
                laterMax[i + 1] <= previous -> previous + ORDER_STEP
                else -> previous + 1
            }
            result[i] = order
            previous = order
        }
        return result
    }

    const val ORDER_STEP = 1L shl 10

    /** 64-bit FNV-1a, to tell cheaply whether a shard changed without writing it out. */
    class Fingerprint {
        var value = -0x340d631b7bdddcdbL
            private set

        private fun byte(b: Int) {
            value = (value xor (b.toLong() and 0xff)) * 0x100000001b3L
        }

        fun add(v: Long) = apply { for (i in 0 until 8) byte((v ushr (i * 8)).toInt()) }

        fun add(v: Int) = apply { for (i in 0 until 4) byte(v ushr (i * 8)) }

        fun add(v: Boolean) = apply { byte(if (v) 1 else 0) }

        fun add(v: String?) = apply {
            if (v == null) {
                byte(0xfe)
                return@apply
            }
            add(v.length)
            for (c in v) {
                byte(c.code)
                byte(c.code ushr 8)
            }
        }

        fun add(gallery: IndexedGallery) = apply {
            add(gallery.gid).add(gallery.token).add(gallery.title).add(gallery.titleJpn).add(gallery.uploader)
            add(gallery.category).add(gallery.local).add(gallery.slot).add(gallery.titlesLookedUp)
        }
    }

    /** Hex of the first 16 bytes of a SHA-256 digest: the name of a content addressed shard. */
    private fun MessageDigest.hex() = digest().take(16).joinToString("") { "%02x".format(it) }

    /** A content addressed file written by [writeHashed]: [hash] names it, [size] is its compressed size. */
    data class Hashed(val hash: String, val size: Long)

    /** Name of the content addressed file of [hash] in a folder of files starting with [prefix]. */
    fun fileName(prefix: String, hash: String) = "$prefix$hash.json.gz"

    /**
     * Writes [value] gzipped into [dir] as [fileName] of its content hash: SHA-256 of the JSON before compression,
     * the first 16 bytes in hex. The same content always gets the same name, an existing file is kept.
     */
    fun <T> writeHashed(json: Json, serializer: KSerializer<T>, value: T, dir: File, prefix: String): Hashed {
        dir.mkdirs()
        val temp = File.createTempFile(prefix, ".tmp", dir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            temp.outputStream().buffered().use { file ->
                GZIPOutputStream(file).use { gzip ->
                    DigestOutputStream(NonClosing(gzip), digest).buffered().use { json.encodeToStream(serializer, value, it) }
                }
            }
            val hash = digest.hex()
            val dest = File(dir, fileName(prefix, hash))
            if (!dest.exists() && !temp.renameTo(dest)) temp.copyTo(dest, overwrite = true)
            return Hashed(hash, dest.length())
        } finally {
            temp.delete()
        }
    }

    /** Writes [text] to [file] through a temporary file, so a reader never sees half of it. */
    fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    /** Writes [value] gzipped to [file] through a temporary file. */
    fun <T> writeGzip(json: Json, serializer: KSerializer<T>, value: T, file: File) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        GZIPOutputStream(temp.outputStream().buffered()).use { json.encodeToStream(serializer, value, it) }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    fun <T> readGzip(json: Json, serializer: KSerializer<T>, file: File): T = GZIPInputStream(file.inputStream().buffered()).use { json.decodeFromStream(serializer, it) }

    /** Keeps the gzip stream open when the JSON writer closes its stream, the gzip trailer is written after. */
    private class NonClosing(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() = out.flush()
        override fun close() = out.flush()
    }
}
