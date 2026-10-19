package com.hippo.ehviewer.download

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Fork: the pages of a ZIP (CBZ) archive, as the reader counts them: its files with an image extension of archive.c,
 * whatever folder they are in. Read from the central directory at the end, without going through the images. Null
 * when it is not a ZIP archive.
 */
internal fun countZipPages(channel: FileChannel): Int? {
    val size = channel.size()
    if (size < EOCD_SIZE) return null
    // The end of central directory record, before a comment of up to 64 KiB
    val tailSize = minOf(size, EOCD_SIZE + 0xFFFFL).toInt()
    val tail = channel.readAt(size - tailSize, tailSize)
    val eocd = (tailSize - EOCD_SIZE downTo 0).firstOrNull { tail.getInt(it) == EOCD_SIGNATURE } ?: return null
    var directorySize = tail.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
    var directoryOffset = tail.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
    if (directorySize == 0xFFFFFFFFL || directoryOffset == 0xFFFFFFFFL) {
        // ZIP64: the real values are in the ZIP64 record the locator before the end record points to
        val locator = size - tailSize + eocd - ZIP64_LOCATOR_SIZE
        if (locator < 0) return null
        val locatorRecord = channel.readAt(locator, ZIP64_LOCATOR_SIZE)
        if (locatorRecord.getInt(0) != ZIP64_LOCATOR_SIGNATURE) return null
        val record = channel.readAt(locatorRecord.getLong(8), ZIP64_EOCD_SIZE)
        if (record.getInt(0) != ZIP64_EOCD_SIGNATURE) return null
        directorySize = record.getLong(40)
        directoryOffset = record.getLong(48)
    }
    if (directoryOffset < 0 || directorySize < 0 || directoryOffset + directorySize > size || directorySize > MAX_DIRECTORY_SIZE) return null
    val directory = channel.readAt(directoryOffset, directorySize.toInt())
    var pages = 0
    var at = 0
    while (at + ENTRY_HEADER_SIZE <= directory.limit() && directory.getInt(at) == ENTRY_SIGNATURE) {
        val nameLength = directory.getShort(at + 28).toInt() and 0xFFFF
        val extraLength = directory.getShort(at + 30).toInt() and 0xFFFF
        val commentLength = directory.getShort(at + 32).toInt() and 0xFFFF
        val nameStart = at + ENTRY_HEADER_SIZE
        if (nameStart + nameLength > directory.limit()) break
        // The extension is ASCII whatever the encoding of the name
        val name = String(ByteArray(nameLength) { directory.get(nameStart + it) }, Charsets.ISO_8859_1)
        if (!name.endsWith('/') && name.substringAfterLast('.', "") in PAGE_EXTENSIONS) pages++
        at = nameStart + nameLength + extraLength + commentLength
    }
    return pages
}

private fun FileChannel.readAt(position: Long, length: Int): ByteBuffer {
    val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
    while (buffer.hasRemaining()) {
        if (read(buffer, position + buffer.position()) < 0) break
    }
    return buffer.flip() as ByteBuffer
}

// As supportExt in archive.c, which the reader shows
private val PAGE_EXTENSIONS = setOf("jpeg", "jpg", "png", "gif", "webp", "bmp", "ico", "wbmp", "heic", "heif", "avif")

private const val EOCD_SIGNATURE = 0x06054b50
private const val EOCD_SIZE = 22
private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50
private const val ZIP64_LOCATOR_SIZE = 20
private const val ZIP64_EOCD_SIGNATURE = 0x06064b50
private const val ZIP64_EOCD_SIZE = 56
private const val ENTRY_SIGNATURE = 0x02014b50
private const val ENTRY_HEADER_SIZE = 46
private const val MAX_DIRECTORY_SIZE = 64L * 1024 * 1024
