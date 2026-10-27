package com.hippo.ehviewer.coil

import arrow.core.merge
import arrow.fx.coroutines.raceN
import coil3.decode.DataSource
import coil3.imageLoader
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.tarsin.coroutines.Counter
import moe.tarsin.coroutines.Tracker
import moe.tarsin.coroutines.counter
import moe.tarsin.coroutines.runSuspendCatching
import moe.tarsin.coroutines.use

typealias F = suspend () -> Unit

suspend inline fun <T> CancellableContinuation<T>.evalAndResume(f: suspend () -> T) = runSuspendCatching { f() }.takeIf { isActive }?.let(::resumeWith)

class TightRope : CoroutineScope, Counter by counter() {
    override val coroutineContext = Dispatchers.IO + Job()

    // Fork: see SharedFailure
    val sharedFailure = SharedFailure()
    val actions = Channel<F>()
    val flow = actions.receiveAsFlow().map(F::invoke).buffer(0).shareIn(this, SharingStarted.WhileSubscribed(stopTimeoutMillis = 200))
    suspend inline fun <R> sendAndAwait(crossinline block: suspend () -> R) = raceN(
        { flow.collect {} },
        { suspendCancellableCoroutine { cont -> launch { actions.send { cont.evalAndResume(block) } } } },
    ).merge()
}

object TightRopeTracker : Tracker<TightRope, String>() {
    override fun new() = TightRope()
    override fun free(e: TightRope) = e.cancel()
}

object MergeInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val req = chain.request
        val key = req.memoryCacheKey
        return if (key != null) {
            val cacheKey = MemoryCache.Key(key, req.memoryCacheKeyExtras)
            val cached = req.context.imageLoader.memoryCache!![cacheKey] != null
            // Fork: a failure while waiting is shared, see SharedFailure
            val result = TightRopeTracker.use(key) {
                val ticket = sharedFailure.ticket()
                sendAndAwait {
                    sharedFailure.since(ticket, req.data)?.let { ErrorResult(req.error(), req, it) }
                        ?: chain.proceed().also { sharedFailure.record(req.data, (it as? ErrorResult)?.throwable) }
                }
            }
            when (result) {
                is SuccessResult if !cached && result.dataSource == DataSource.MEMORY_CACHE -> {
                    result.copy(dataSource = DataSource.MEMORY)
                }
                else -> result
            }
        } else {
            chain.proceed()
        }
    }
}
