package com.hippo.ehviewer.chapters

import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.client.executeSafely
import com.hippo.ehviewer.util.ensureSuccess
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Fork: a few pages of an archive of panda.chaika.moe, to compare a favorite gone from E-H with its raw there. The site
 * has no thumbnails, only the zip of the full pages (several MB each): its index is read from the end of the zip, then
 * only the pages taken, each by a range of bytes.
 */
object PandaPages {
    /** A file of a zip: [offset] that of its local header. */
    data class Entry(val name: String, val method: Int, val compressedSize: Long, val offset: Long)

    private const val TAIL = 65_536L
    private const val LOCAL_HEADER = 30
    private const val MAX_LOCAL_EXTRA = 1024
    private val IMAGE = Regex("""\.(jpe?g|png|webp|gif|avif)$""", RegexOption.IGNORE_CASE)
    private val DIGITS = Regex("""\d+""")

    /**
     * Where the central directory of a zip is, (offset, size), from its last bytes [tail]; null when not found or a
     * zip64 one.
     */
    fun centralDirectory(tail: ByteArray): Pair<Long, Int>? {
        for (i in tail.size - 22 downTo 0) {
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 5.toByte() && tail[i + 3] == 6.toByte()) {
                val buffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
                val size = buffer.getInt(i + 12).toLong() and 0xffffffffL
                val offset = buffer.getInt(i + 16).toLong() and 0xffffffffL
                if (size == 0xffffffffL || offset == 0xffffffffL) return null
                return offset to size.toInt()
            }
        }
        return null
    }

    /** The files of the central directory [cd]. */
    fun entries(cd: ByteArray): List<Entry> {
        val buffer = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
        val entries = ArrayList<Entry>()
        var p = 0
        while (p + 46 <= cd.size && buffer.getInt(p) == 0x02014b50) {
            val method = buffer.getShort(p + 10).toInt() and 0xffff
            val compressed = buffer.getInt(p + 20).toLong() and 0xffffffffL
            val nameLength = buffer.getShort(p + 28).toInt() and 0xffff
            val extraLength = buffer.getShort(p + 30).toInt() and 0xffff
            val commentLength = buffer.getShort(p + 32).toInt() and 0xffff
            val offset = buffer.getInt(p + 42).toLong() and 0xffffffffL
            if (p + 46 + nameLength > cd.size) break
            val name = String(cd, p + 46, nameLength, Charsets.UTF_8)
            entries += Entry(name, method, compressed, offset)
            p += 46 + nameLength + extraLength + commentLength
        }
        return entries
    }

    /** The images of [entries] in the order of the pages: by name, the numbers in it by their value. */
    fun pages(entries: List<Entry>): List<Entry> = entries.filter { IMAGE.containsMatchIn(it.name) && !it.name.startsWith("__MACOSX") }
        .sortedBy { entry -> entry.name.lowercase().replace(DIGITS) { it.value.trimStart('0').padStart(12, '0') } }

    /** [count] pages of [total] (from 0), spread from the start to the end, the very first and last left out. */
    fun spread(total: Int, count: Int): List<Int> {
        if (total <= count) return (0 until total).toList()
        return (0 until count).map { i -> (((i + 0.5) / count) * total).toInt().coerceIn(0, total - 1) }.distinct()
    }

    /** The bytes of [url] in [spec] ("first-last", or "-n" for the last n), and the size of the whole file. */
    private suspend fun range(url: String, spec: String): Pair<ByteArray, Long?> = ktorClient.prepareGet(url) {
        header(HttpHeaders.Range, "bytes=$spec")
    }.executeSafely {
        it.status.ensureSuccess()
        val total = it.headers[HttpHeaders.ContentRange]?.substringAfterLast('/')?.toLongOrNull()
        it.readRawBytes() to total
    }

    /**
     * The pages of the zip at [url]: how many, and the bytes of those of [pick] (given that count, it answers the
     * indices to take), [onPage] told after each. Null when it is not a zip read this way.
     */
    suspend fun sample(url: String, pick: (Int) -> List<Int>, onPage: (Int) -> Unit): Pair<Int, List<Pair<Int, ByteArray>>>? {
        val (tail, size) = range(url, "-$TAIL")
        size ?: return null
        val (cdOffset, cdSize) = centralDirectory(tail) ?: return null
        val tailStart = size - tail.size
        val cd = if (cdOffset >= tailStart) {
            tail.copyOfRange((cdOffset - tailStart).toInt(), (cdOffset - tailStart).toInt() + cdSize)
        } else {
            range(url, "$cdOffset-${cdOffset + cdSize - 1}").first
        }
        val pages = pages(entries(cd))
        if (pages.isEmpty()) return null
        val taken = pick(pages.size).mapIndexedNotNull { done, index ->
            val bytes = runCatching { read(url, pages[index]) }.getOrNull()
            onPage(done + 1)
            bytes?.let { index to it }
        }
        return pages.size to taken
    }

    /** The bytes of the file [entry] of the zip at [url], uncompressed. */
    private suspend fun read(url: String, entry: Entry): ByteArray? {
        val end = entry.offset + LOCAL_HEADER + entry.name.toByteArray().size + MAX_LOCAL_EXTRA + entry.compressedSize - 1
        val (bytes, _) = range(url, "${entry.offset}-$end")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < LOCAL_HEADER || buffer.getInt(0) != 0x04034b50) return null
        val start = LOCAL_HEADER + (buffer.getShort(26).toInt() and 0xffff) + (buffer.getShort(28).toInt() and 0xffff)
        val stop = start + entry.compressedSize.toInt()
        if (stop > bytes.size) return null
        val data = bytes.copyOfRange(start, stop)
        return when (entry.method) {
            0 -> data
            8 -> inflate(data)
            else -> null
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream(data.size * 2)
            val chunk = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
