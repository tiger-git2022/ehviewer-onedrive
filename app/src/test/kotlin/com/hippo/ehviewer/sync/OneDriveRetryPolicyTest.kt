package com.hippo.ehviewer.sync

import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneDriveRetryPolicyTest {
    @Test
    fun retryableStatusCodes() {
        for (code in listOf(429, 500, 502, 503, 504, 509)) {
            assertTrue(OneDriveRetryPolicy.isRetryableStatus(code), "Expected HTTP $code to be retryable")
        }
        for (code in listOf(200, 201, 204, 400, 401, 403, 404, 409, 412)) {
            assertFalse(OneDriveRetryPolicy.isRetryableStatus(code), "Expected HTTP $code not to be retryable")
        }
    }

    @Test
    fun retryableExceptions() {
        assertTrue(OneDriveRetryPolicy.isRetryableException(SocketTimeoutException("timeout")))
        assertTrue(OneDriveRetryPolicy.isRetryableException(EOFException("eof")))
        assertTrue(OneDriveRetryPolicy.isRetryableException(IOException("reset")))
        assertFalse(OneDriveRetryPolicy.isRetryableException(FileNotFoundException("missing")))
        assertFalse(OneDriveRetryPolicy.isRetryableException(OneDrivePreconditionException()))
        assertFalse(OneDriveRetryPolicy.isRetryableException(OneDriveAuthException("expired")))
        assertFalse(OneDriveRetryPolicy.isRetryableException(OneDriveException("graph error")))
    }

    @Test
    fun parseRetryAfterSecondsAndHttpDate() {
        val now = 1_700_000_000_000L
        assertEquals(5_000L, OneDriveRetryPolicy.parseRetryAfterMillis("5", now))
        assertEquals(1_200L, OneDriveRetryPolicy.parseRetryAfterMillis(" 1.2 ", now))
        assertEquals(OneDriveRetryPolicy.MIN_RETRY_MILLIS, OneDriveRetryPolicy.parseRetryAfterMillis("0", now))
        assertEquals(OneDriveRetryPolicy.MAX_RETRY_AFTER_MILLIS, OneDriveRetryPolicy.parseRetryAfterMillis("600", now))
        val futureDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + 12_000L).atOffset(ZoneOffset.UTC))
        assertEquals(12_000L, OneDriveRetryPolicy.parseRetryAfterMillis(futureDate, now))
        val pastDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now - 5_000L).atOffset(ZoneOffset.UTC))
        assertEquals(OneDriveRetryPolicy.MIN_RETRY_MILLIS, OneDriveRetryPolicy.parseRetryAfterMillis(pastDate, now))
        assertNull(OneDriveRetryPolicy.parseRetryAfterMillis(null, now))
        assertNull(OneDriveRetryPolicy.parseRetryAfterMillis("not-a-date", now))
    }

    @Test
    fun retryDelayUsesHeaderOrExponentialBackoff() {
        assertEquals(7_000L, OneDriveRetryPolicy.retryDelayMillis(attempt = 0, retryAfterHeader = "7"))
        assertEquals(1_000L, OneDriveRetryPolicy.retryDelayMillis(attempt = 0, retryAfterHeader = null))
        assertEquals(2_000L, OneDriveRetryPolicy.retryDelayMillis(attempt = 1, retryAfterHeader = null))
        assertEquals(4_000L, OneDriveRetryPolicy.retryDelayMillis(attempt = 2, retryAfterHeader = null))
    }
}
