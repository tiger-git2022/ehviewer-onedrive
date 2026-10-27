package com.hippo.ehviewer.coil

import java.util.concurrent.atomic.AtomicLong

/*
 * Fork: MergeInterceptor runs the requests for one image one at a time, so that once it has loaded the others take
 * it from the memory cache. When it fails, though, each of them tried the network in turn and waited out its own
 * timeout: the 20 previews of one sprite, from a server that cannot be reached, took minutes to fail (with their
 * retries), and the gallery showed "Loading previews 0/15" all along. Now the requests that were waiting when the
 * image failed fail at once with the same error; a request queued after that (a retry) tries the network again.
 */
class SharedFailure {
    private class Failure(val data: Any, val seq: Long, val throwable: Throwable)

    private val seq = AtomicLong()

    @Volatile
    private var last: Failure? = null

    /** Taken when a request is queued, to tell later which failures happened while it waited. */
    fun ticket() = seq.get()

    /** The error of [data] failing after [ticket] was taken, else null. */
    fun since(ticket: Long, data: Any): Throwable? = last?.takeIf { it.seq > ticket && it.data == data }?.throwable

    /** How the request for [data] ended: [throwable] when it failed, null when it loaded. */
    fun record(data: Any, throwable: Throwable?) {
        last = throwable?.let { Failure(data, seq.incrementAndGet(), it) }
    }
}
