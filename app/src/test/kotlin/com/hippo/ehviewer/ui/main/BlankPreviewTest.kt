package com.hippo.ehviewer.ui.main

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlankPreviewTest {
    private val clear = 0x00000000
    private val noise = 0x08FFFFFF
    private val opaque = 0xFF336699.toInt()

    // A sprite of 3 previews, 2 px wide each, 3 rows: the first is visible, the second is empty,
    // the third has its picture 1 row high and transparent padding below
    private val pixels = arrayOf(
        intArrayOf(opaque, opaque, clear, noise, opaque, clear),
        intArrayOf(opaque, opaque, noise, clear, clear, clear),
        intArrayOf(clear, clear, clear, clear, clear, clear),
    )

    private val rows = firstVisibleRows(6, 3) { y, line -> pixels[y].copyInto(line) }

    @Test
    fun findsTheFirstVisibleRowOfEachColumn() {
        val none = Int.MAX_VALUE
        assertContentEquals(intArrayOf(0, 0, none, none, 0, none), rows)
    }

    @Test
    fun emptyPreviewIsBlank() {
        assertTrue(isBlankArea(rows, x = 2, width = 2, height = 3))
    }

    @Test
    fun previewWithAPictureIsNotBlank() {
        assertFalse(isBlankArea(rows, x = 0, width = 2, height = 3))
        assertFalse(isBlankArea(rows, x = 4, width = 2, height = 1))
    }

    @Test
    fun areaOutsideTheImageIsNotCalledBlank() {
        assertFalse(isBlankArea(rows, x = 6, width = 2, height = 3))
    }
}
