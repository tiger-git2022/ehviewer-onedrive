package com.hippo.ehviewer.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncRunProgressTest {
    @Test
    fun fractionCountsTheStepsDone() {
        assertEquals(0f, SyncRunProgress(title = 0, step = 1, steps = 4).fraction)
        assertEquals(0.5f, SyncRunProgress(title = 0, step = 3, steps = 4).fraction)
        assertEquals(50, SyncRunProgress(title = 0, step = 3, steps = 4).percent)
    }

    @Test
    fun fractionAddsTheKnownPartOfTheStep() {
        assertEquals(0.875f, SyncRunProgress(title = 0, step = 4, steps = 4, done = 50, total = 100).fraction)
        // More items than counted (favorites added while reading) never go past the step
        assertEquals(1f, SyncRunProgress(title = 0, step = 4, steps = 4, done = 120, total = 100).fraction)
    }

    @Test
    fun detailReplacesTheStepTitleAndItems() {
        val progress = SyncRunProgress(title = 1, step = 4, steps = 4, favoritesIndex = true).withDetail(StepProgress(2, 30, 60))
        assertEquals(SyncRunProgress(title = 2, step = 4, steps = 4, done = 30, total = 60, favoritesIndex = true), progress)
        assertEquals(50, StepProgress(2, 30, 60).percent)
        assertNull(StepProgress(2).percent)
    }

    @Test
    fun subStepsOfTheLastStepOnlyMoveForward() {
        val step = SyncRunProgress(title = 0, step = 4, steps = 4, favoritesIndex = true)
        val readAll = step.withDetail(StepProgress(1, 100, 100, StepProgress.READ)).fraction
        val titlesStart = step.withDetail(StepProgress(2, 0, 50, StepProgress.TITLES)).fraction
        val upload = step.withDetail(StepProgress(3, part = StepProgress.UPLOAD)).fraction
        assertEquals(titlesStart, readAll)
        assertTrue(upload >= titlesStart)
        assertTrue(readAll < 1f)
    }
}
