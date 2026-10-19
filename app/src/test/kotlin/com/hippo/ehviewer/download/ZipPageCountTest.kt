package com.hippo.ehviewer.download

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZipPageCountTest {
    private val dir: File = Files.createTempDirectory("zip-pages").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun zip(vararg names: String, comment: String? = null) = File(dir, "test.cbz").apply {
        ZipOutputStream(outputStream()).use { out ->
            names.forEach { name ->
                out.putNextEntry(ZipEntry(name))
                if (!name.endsWith('/')) out.write(ByteArray(100) { it.toByte() })
                out.closeEntry()
            }
            comment?.let(out::setComment)
        }
    }

    private fun count(file: File) = RandomAccessFile(file, "r").use { countZipPages(it.channel) }

    @Test
    fun countsTheImagesAsTheReaderDoes() {
        val file = zip("001.jpg", "002.png", "pages/003.webp", "004.jpeg", "ComicInfo.xml", "pages/", "notes.txt", "005.JPG")
        // The reader takes the extensions in lower case only, so 005.JPG is not a page
        assertEquals(4, count(file))
    }

    @Test
    fun aRewrittenArchiveWithFewerPages() {
        assertEquals(25, count(zip(*Array(25) { "%03d.jpg".format(it + 1) })))
        assertEquals(20, count(zip(*Array(20) { "%03d.png".format(it + 1) })))
    }

    @Test
    fun anArchiveWithAComment() {
        assertEquals(2, count(zip("1.jpg", "2.jpg", comment = "x".repeat(1000))))
    }

    @Test
    fun notAnArchive() {
        val file = File(dir, "not.cbz").apply { writeBytes(ByteArray(4096) { 7 }) }
        assertNull(count(file))
        val tiny = File(dir, "tiny.cbz").apply { writeBytes(ByteArray(3)) }
        assertNull(count(tiny))
    }
}
