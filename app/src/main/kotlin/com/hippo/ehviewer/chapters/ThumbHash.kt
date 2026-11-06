package com.hippo.ehviewer.chapters

/**
 * Fork: a fingerprint of a page thumbnail to find the same page in another gallery, see [ChapterMatcher]. A difference
 * hash: the picture, a [BORDER] trimmed off each side, is shrunk to 17 x 16 in gray and each of the 256 bits says
 * whether a cell is brighter than the one on its left. Another scan, a decensored version or another resolution of the
 * same page stays close; the files themselves differ, so the hashes of E-H (the page tokens) do not match.
 */
object ThumbHash {
    const val BITS = 256
    private const val SIZE = 16
    private const val BORDER = 0.05f

    /** The hash of a picture of [width] x [height] pixels, [argb] row by row; null when it is too small to tell. */
    fun of(argb: IntArray, width: Int, height: Int): LongArray? {
        val left = (width * BORDER).toInt()
        val top = (height * BORDER).toInt()
        val w = width - 2 * left
        val h = height - 2 * top
        if (w < SIZE + 1 || h < SIZE) return null
        // The mean gray of each cell of a grid of 17 x 16 over the picture
        val cols = SIZE + 1
        val sums = DoubleArray(cols * SIZE)
        val counts = IntArray(cols * SIZE)
        for (y in 0 until h) {
            val row = y * SIZE / h
            val offset = (top + y) * width + left
            for (x in 0 until w) {
                val pixel = argb[offset + x]
                val gray = 0.299 * (pixel shr 16 and 0xFF) + 0.587 * (pixel shr 8 and 0xFF) + 0.114 * (pixel and 0xFF)
                val cell = row * cols + x * cols / w
                sums[cell] += gray
                counts[cell]++
            }
        }
        val hash = LongArray(BITS / 64)
        var bit = 0
        for (row in 0 until SIZE) {
            for (col in 0 until SIZE) {
                val a = row * cols + col
                if (sums[a + 1] / counts[a + 1] > sums[a] / counts[a]) hash[bit / 64] = hash[bit / 64] or (1L shl (bit % 64))
                bit++
            }
        }
        return hash
    }

    /** How many of the bits differ, from 0 (the same) to [BITS]. */
    fun distance(a: LongArray, b: LongArray): Int {
        var d = 0
        for (i in a.indices) d += java.lang.Long.bitCount(a[i] xor b[i])
        return d
    }

    fun toHex(hash: LongArray) = hash.joinToString("") { it.toULong().toString(16).padStart(16, '0') }

    fun fromHex(hex: String): LongArray? {
        if (hex.length != BITS / 4) return null
        return LongArray(BITS / 64) { hex.substring(it * 16, it * 16 + 16).toULongOrNull(16)?.toLong() ?: return null }
    }
}
