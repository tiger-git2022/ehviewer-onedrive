package com.hippo.ehviewer.coil

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class SharedFailureTest {
    private val url = "https://abc.hath.network/c2/token/123-0.webp"

    @Test
    fun requestsWaitingWhenTheImageFailedShareTheError() {
        val shared = SharedFailure()
        val first = shared.ticket()
        val second = shared.ticket()
        val third = shared.ticket()
        assertNull(shared.since(first, url))
        val error = IOException("timeout")
        shared.record(url, error)
        assertSame(error, shared.since(second, url))
        assertSame(error, shared.since(third, url))
    }

    @Test
    fun aRequestQueuedAfterTheFailureTriesAgain() {
        val shared = SharedFailure()
        shared.ticket()
        shared.record(url, IOException("timeout"))
        val retry = shared.ticket()
        assertNull(shared.since(retry, url))
    }

    @Test
    fun aNewLinkForTheSameImageTriesAgain() {
        val shared = SharedFailure()
        val waiting = shared.ticket()
        shared.record(url, IOException("timeout"))
        assertNull(shared.since(waiting, "https://abc.hath.network/c2/newtoken/123-0.webp"))
    }

    @Test
    fun aLoadClearsTheFailure() {
        val shared = SharedFailure()
        val waiting = shared.ticket()
        shared.record(url, IOException("timeout"))
        shared.record(url, null)
        assertNull(shared.since(waiting, url))
    }
}
