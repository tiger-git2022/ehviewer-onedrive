package com.hippo.ehviewer.util

import kotlin.test.Test
import kotlin.test.assertEquals

class AdPagesTest {
    private fun checked(size: Int) = (0 until size).filter { isAdPageCandidate(it, size) }

    @Test
    fun shortGalleryChecksLastThree() {
        assertEquals(listOf(7, 8, 9), checked(10))
        assertEquals(listOf(0, 1), checked(2))
    }

    @Test
    fun longGalleryChecksLastSeven() {
        assertEquals((4..10).toList(), checked(11))
        assertEquals((193..199).toList(), checked(200))
    }
}
