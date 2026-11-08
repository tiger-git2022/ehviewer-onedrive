package com.hippo.ehviewer.chapters

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PandaPagesTest {
    private fun zip(vararg names: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun readsTheIndexFromTheEnd() {
        val bytes = zip("_010.jpg", "_002.jpg", "_001.jpg", "info.txt", "__MACOSX/._001.jpg")
        val (offset, size) = assertNotNull(PandaPages.centralDirectory(bytes))
        val entries = PandaPages.entries(bytes.copyOfRange(offset.toInt(), offset.toInt() + size))
        assertEquals(5, entries.size)
        assertEquals(listOf("_001.jpg", "_002.jpg", "_010.jpg"), PandaPages.pages(entries).map { it.name })
        assertEquals(0L, entries.first().offset)
    }

    @Test
    fun spreadsThePagesTaken() {
        assertEquals(listOf(9, 27, 46, 64, 82), PandaPages.spread(92, 5))
        assertEquals(listOf(0, 1, 2), PandaPages.spread(3, 5))
    }

    @Test
    fun readsFullWidthSignsAsPlainOnes() {
        assertEquals(ChapterTitles.core("[フグタ家] #純愛カノジョ"), ChapterTitles.core("[フグタ家] ＃純愛カノジョ [DL版]"))
    }
}
