package com.hippo.ehviewer.sync

import java.io.FileNotFoundException
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/**
 * Fork: retry policy for transient Microsoft Graph / OneDrive failures (throttling, 5xx, network resets).
 * Kept pure for unit tests.
 */
object OneDriveRetryPolicy {
    const val MAX_RETRIES = 3
    const val MIN_RETRY_MILLIS = 1_000L
    const val MAX_BACKOFF_MILLIS = 16_000L
    const val MAX_RETRY_AFTER_MILLIS = 60_000L

    fun isRetryableStatus(code: Int): Boolean = code == 429 || code == 500 || code == 502 || code == 503 || code == 504 || code == 509

    fun isRetryableException(e: IOException): Boolean = e !is OneDriveException && e !is FileNotFoundException

    fun parseRetryAfterMillis(header: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val trimmed = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        trimmed.toLongOrNull()?.let { seconds ->
            return (seconds.coerceAtLeast(0L) * 1_000L).coerceIn(MIN_RETRY_MILLIS, MAX_RETRY_AFTER_MILLIS)
        }
        trimmed.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }?.let { seconds ->
            return ceil(seconds * 1_000.0).toLong().coerceIn(MIN_RETRY_MILLIS, MAX_RETRY_AFTER_MILLIS)
        }
        val parsed = runCatching { ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: return null
        return (parsed - nowMillis).coerceIn(MIN_RETRY_MILLIS, MAX_RETRY_AFTER_MILLIS)
    }

    fun retryDelayMillis(attempt: Int, retryAfterHeader: String?, nowMillis: Long = System.currentTimeMillis()): Long =
        parseRetryAfterMillis(retryAfterHeader, nowMillis)
            ?: (MIN_RETRY_MILLIS shl attempt.coerceIn(0, 4)).coerceAtMost(MAX_BACKOFF_MILLIS)
}
