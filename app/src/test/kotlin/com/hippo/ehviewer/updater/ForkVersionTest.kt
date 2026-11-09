package com.hippo.ehviewer.updater

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForkVersionTest {
    @Test
    fun higherBuildNumberIsNewer() {
        assertTrue(ForkVersion.isNewer("v1.15.0-t3", "1.15.0-t2"))
        assertTrue(ForkVersion.isNewer("1.15.0-t10", "1.15.0-t9"))
        assertFalse(ForkVersion.isNewer("1.15.0-t2", "1.15.0-t2"))
        assertFalse(ForkVersion.isNewer("1.15.0-t1", "1.15.0-t2"))
    }

    @Test
    fun paddedBuildNumbersCompareWithOlderOnes() {
        assertTrue(ForkVersion.isNewer("v1.15.0-t102", "1.15.0-t101"))
        assertTrue(ForkVersion.isNewer("1.16.0-t001", "1.15.0-t101"))
        assertTrue(ForkVersion.isNewer("1.15.0-t012", "1.15.0-t9"))
        assertFalse(ForkVersion.isNewer("1.15.0-t009", "1.15.0-t9"))
    }

    @Test
    fun upstreamVersionComesFirst() {
        assertTrue(ForkVersion.isNewer("1.16.0-t1", "1.15.0-t9"))
        assertTrue(ForkVersion.isNewer("1.15.1-t1", "1.15-t5"))
        assertFalse(ForkVersion.isNewer("1.14.6-t9", "1.15.0-t1"))
    }

    @Test
    fun buildsWithoutForkVersion() {
        // Local or earlier builds are older than any release
        assertTrue(ForkVersion.isNewer("1.15.0-t1", "1.15.0-SNAPSHOT"))
        assertTrue(ForkVersion.isNewer("3.18.0", "1.15.0-SNAPSHOT"))
        // Releases without a version (the old nightly) are never offered
        assertFalse(ForkVersion.isNewer("nightly", "1.15.0-t1"))
    }

    @Test
    fun forkVersionsAreNewerThanNumberedBuilds() {
        assertTrue(ForkVersion.isNewer("v3.18.0", "1.15.0-t129"))
        assertTrue(ForkVersion.isNewer("3.18.0", "1.15.0-t9"))
        assertFalse(ForkVersion.isNewer("1.15.0-t130", "3.18.0"))
    }

    @Test
    fun forkVersionsCompareByNumber() {
        assertTrue(ForkVersion.isNewer("v3.18.1", "3.18.0"))
        assertTrue(ForkVersion.isNewer("3.19.0", "3.18.12"))
        assertTrue(ForkVersion.isNewer("3.18.10", "3.18.9"))
        assertTrue(ForkVersion.isNewer("4.0.0", "3.99.99"))
        assertFalse(ForkVersion.isNewer("3.18.0", "3.18.0"))
        assertFalse(ForkVersion.isNewer("3.17.9", "3.18.0"))
    }
}
