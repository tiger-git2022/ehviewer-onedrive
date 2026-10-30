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
        // Releases without a fork version (the old nightly) are never offered
        assertFalse(ForkVersion.isNewer("nightly", "1.15.0-t1"))
        assertFalse(ForkVersion.isNewer("1.15.0", "1.15.0-SNAPSHOT"))
    }
}
