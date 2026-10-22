package com.hippo.ehviewer.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressCarryTest {
    @Test
    fun progressOfGalleriesNotHereIsKept() {
        assertEquals(listOf(1L), ProgressSection.carriedProgress(listOf(1L), galleries = emptySet(), pending = emptySet()))
    }

    @Test
    fun pendingProgressIsKeptOnceTheGalleryIsHere() {
        // Synced while this device had no gallery 1, which was opened since: not a deletion
        assertEquals(listOf(1L), ProgressSection.carriedProgress(listOf(1L, 2L), galleries = setOf(1L, 2L), pending = setOf(1L)))
    }

    @Test
    fun progressRemovedHereIsADeletion() {
        assertEquals(emptyList(), ProgressSection.carriedProgress(listOf(2L), galleries = setOf(2L), pending = setOf(1L)))
    }

    @Test
    fun beforeThePendingProgressIsKnownAllIsKept() {
        assertEquals(listOf(1L, 2L), ProgressSection.carriedProgress(listOf(1L, 2L), galleries = setOf(1L), pending = null))
    }
}
