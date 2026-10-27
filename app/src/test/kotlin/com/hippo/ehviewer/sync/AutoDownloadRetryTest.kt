package com.hippo.ehviewer.sync

import com.ehviewer.core.database.model.DownloadInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoDownloadRetryTest {
    private fun plan(
        unstarted: Set<Long> = emptySet(),
        running: Map<Long, Int> = emptyMap(),
        states: Map<Long, Int>,
        online: Boolean = true,
        skip: Set<Long> = emptySet(),
        activeHere: Set<Long> = emptySet(),
    ) = AutoDownloadRetry.plan(unstarted, running, { states[it] ?: DownloadInfo.STATE_INVALID }, online, skip, activeHere::contains)

    @Test
    fun aListedDownloadIsStarted() {
        val plan = plan(unstarted = setOf(1L), states = mapOf(1L to DownloadInfo.STATE_NONE))
        assertEquals(listOf(1L), plan.start)
        assertEquals(emptySet(), plan.unstarted)
        assertEquals(mapOf(1L to 0), plan.running)
        // Still in the background: stays listed
        assertEquals(setOf(1L), plan.unstartedIfRefused)
        assertEquals(emptyMap(), plan.runningIfRefused)
    }

    @Test
    fun aFailedDownloadStartsAgainWhileOnline() {
        val plan = plan(running = mapOf(1L to 2), states = mapOf(1L to DownloadInfo.STATE_FAILED))
        assertEquals(listOf(1L), plan.start)
        assertEquals(mapOf(1L to 3), plan.running)
        assertEquals(mapOf(1L to 2), plan.runningIfRefused)
    }

    @Test
    fun aFailedDownloadWaitsForTheNetwork() {
        val plan = plan(running = mapOf(1L to 0), states = mapOf(1L to DownloadInfo.STATE_FAILED), online = false)
        assertEquals(emptyList(), plan.start)
        assertEquals(mapOf(1L to 0), plan.running)
    }

    @Test
    fun aDownloadFailingTooOftenIsLeftToTheUser() {
        val plan = plan(running = mapOf(1L to AutoDownloadRetry.MAX_RETRIES), states = mapOf(1L to DownloadInfo.STATE_FAILED))
        assertEquals(emptyList(), plan.start)
        assertEquals(emptyMap(), plan.running)
    }

    @Test
    fun finishedStoppedAndRemovedAreForgotten() {
        val plan = plan(
            running = mapOf(1L to 0, 2L to 0, 3L to 0, 4L to 1),
            states = mapOf(1L to DownloadInfo.STATE_FINISH, 2L to DownloadInfo.STATE_NONE, 4L to DownloadInfo.STATE_DOWNLOAD),
            activeHere = setOf(2L, 4L),
        )
        assertEquals(emptyList(), plan.start)
        assertEquals(mapOf(4L to 1), plan.running)
    }

    @Test
    fun oneStartedByTheUserIsFollowed() {
        val plan = plan(unstarted = setOf(1L, 2L), states = mapOf(1L to DownloadInfo.STATE_WAIT))
        assertEquals(emptyList(), plan.start)
        assertEquals(emptySet(), plan.unstarted)
        assertEquals(mapOf(1L to 0), plan.running)
    }

    @Test
    fun theOnesJustAddedAreLeftAlone() {
        // Started through the service: not in the downloads yet
        val plan = plan(unstarted = setOf(1L), running = mapOf(2L to 0), states = emptyMap(), skip = setOf(1L, 2L))
        assertEquals(emptyList(), plan.start)
        assertEquals(setOf(1L), plan.unstarted)
        assertEquals(mapOf(2L to 0), plan.running)
    }

    @Test
    fun oneCutOffByTheEndOfTheProcessStartsAgain() {
        // Read back not started after the app was killed while downloading
        val plan = plan(running = mapOf(1L to 0), states = mapOf(1L to DownloadInfo.STATE_NONE))
        assertEquals(listOf(1L), plan.start)
        assertEquals(mapOf(1L to 1), plan.running)
    }
}
