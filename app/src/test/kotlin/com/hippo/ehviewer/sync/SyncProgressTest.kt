package com.hippo.ehviewer.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncProgressTest {
    @Test
    fun fractionCountsTheStepsDone() {
        assertEquals(0f, SyncProgress(title = 0, step = 1, steps = 4).fraction)
        assertEquals(0.5f, SyncProgress(title = 0, step = 3, steps = 4).fraction)
        assertEquals(50, SyncProgress(title = 0, step = 3, steps = 4).percent)
    }

    @Test
    fun fractionAddsTheKnownPartOfTheStep() {
        assertEquals(0.875f, SyncProgress(title = 0, step = 4, steps = 4, done = 50, total = 100).fraction)
        // More items than counted (favorites added while reading) never go past the step
        assertEquals(1f, SyncProgress(title = 0, step = 4, steps = 4, done = 120, total = 100).fraction)
    }

    @Test
    fun detailReplacesTheStepTitleAndItems() {
        val progress = SyncProgress(title = 1, step = 4, steps = 4, favoritesIndex = true).withDetail(StepProgress(2, 30, 60))
        assertEquals(SyncProgress(title = 2, step = 4, steps = 4, done = 30, total = 60, favoritesIndex = true), progress)
        assertEquals(50, StepProgress(2, 30, 60).percent)
        assertNull(StepProgress(2).percent)
    }
}
