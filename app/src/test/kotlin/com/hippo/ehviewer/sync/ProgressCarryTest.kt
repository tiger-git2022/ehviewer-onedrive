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
    fun aRowAtTheFirstPageCountsAsMissing() {
        // Opening the reader wrote page 0 over nothing: the synced page 37 still counts, a page 0 baseline is no change
        val baseline = listOf(SyncProgress(1L, 37, 1L), SyncProgress(2L, 0, 1L), SyncProgress(3L, 12, 1L), SyncProgress(4L, 5, 1L))
        val rows = mapOf(1L to 0, 2L to 0, 3L to 20)
        assertEquals(listOf(1L, 4L), ProgressSection.missingProgress(baseline, rows))
    }

    @Test
    fun beforeThePendingProgressIsKnownAllIsKept() {
        assertEquals(listOf(1L, 2L), ProgressSection.carriedProgress(listOf(1L, 2L), galleries = setOf(1L), pending = null))
    }
}
