package com.hippo.ehviewer.client

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay

/**
 * Fork: marks a coroutine as making background E-Hentai requests (cloud favorites sync, title lookups), so it does not
 * count as foreground browsing traffic in [EhTraffic].
 */
object BackgroundEhRequest : AbstractCoroutineContextElement(BackgroundEhRequest), CoroutineContext.Key<BackgroundEhRequest>

/**
 * Fork: coordinates foreground browsing with background E-Hentai sync so they do not pile requests on top of each
 * other and trigger IP bans.
 */
object EhTraffic {
    private val lastForegroundNanos = AtomicLong(0L)

    /** Records a foreground E-Hentai request unless the caller is running in [BackgroundEhRequest]. */
    suspend fun recordIfForeground() {
        if (coroutineContext[BackgroundEhRequest] == null) {
            lastForegroundNanos.set(System.nanoTime())
        }
    }

    /** Yields briefly when foreground browsing hit E-Hentai within [quietMillis]. */
    suspend fun waitForQuiet(quietMillis: Long = 3_000L, maxWaitMillis: Long = 2_500L) {
        val last = lastForegroundNanos.get()
        if (last == 0L) return
        val elapsedMs = (System.nanoTime() - last) / 1_000_000L
        if (elapsedMs in 0 until quietMillis) {
            delay((quietMillis - elapsedMs).coerceAtMost(maxWaitMillis))
        }
    }
}
