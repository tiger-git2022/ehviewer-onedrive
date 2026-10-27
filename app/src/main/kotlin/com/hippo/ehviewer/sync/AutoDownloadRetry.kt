package com.hippo.ehviewer.sync

import com.ehviewer.core.database.model.DownloadInfo

/**
 * Fork: what [AutoDownload] does with the galleries it added that are not downloaded yet, when the network comes back
 * or the app comes to the front. Kept apart from it so it can be tested without Android.
 */
internal object AutoDownloadRetry {
    /** Times a failed download is started again by itself; after that it is left to the user. */
    const val MAX_RETRIES = 5

    /**
     * [unstarted]: listed while Android did not let the app start a download from the background. [running]: started,
     * with the times a failed one was started again. [stateOf]: the [DownloadInfo] state of a gallery,
     * [DownloadInfo.STATE_INVALID] once the user removed it. [online]: the network works now. [skip]: just added,
     * left for the next time.
     */
    data class Plan(
        /** To start now: the unstarted, and the failed ones while [online]. */
        val start: List<Long>,
        val unstarted: Set<Long>,
        val running: Map<Long, Int>,
        /** [unstarted] and [running] if starting [start] is refused: nothing was started. */
        val unstartedIfRefused: Set<Long>,
        val runningIfRefused: Map<Long, Int>,
    )

    fun plan(
        unstarted: Set<Long>,
        running: Map<Long, Int>,
        stateOf: (Long) -> Int,
        online: Boolean,
        skip: Set<Long> = emptySet(),
    ): Plan {
        val start = ArrayList<Long>()
        val newUnstarted = HashSet<Long>()
        val newRunning = HashMap<Long, Int>()
        val refusedUnstarted = HashSet<Long>()
        val refusedRunning = HashMap<Long, Int>()
        unstarted.forEach { gid ->
            if (gid in skip) {
                newUnstarted += gid
                refusedUnstarted += gid
                return@forEach
            }
            when (stateOf(gid)) {
                // Removed by the user
                DownloadInfo.STATE_INVALID -> Unit
                DownloadInfo.STATE_NONE -> {
                    start += gid
                    newRunning[gid] = 0
                    refusedUnstarted += gid
                }
                // Started by the user meanwhile
                else -> {
                    newRunning[gid] = 0
                    refusedRunning[gid] = 0
                }
            }
        }
        running.forEach { (gid, tries) ->
            if (gid in skip) {
                newRunning[gid] = tries
                refusedRunning[gid] = tries
                return@forEach
            }
            when (stateOf(gid)) {
                DownloadInfo.STATE_WAIT, DownloadInfo.STATE_DOWNLOAD -> {
                    newRunning[gid] = tries
                    refusedRunning[gid] = tries
                }
                DownloadInfo.STATE_FAILED -> when {
                    // Given up: the user can start it again from the downloads
                    tries >= MAX_RETRIES -> Unit
                    // Without network it would only fail again and use up a try
                    !online -> {
                        newRunning[gid] = tries
                        refusedRunning[gid] = tries
                    }
                    else -> {
                        start += gid
                        newRunning[gid] = tries + 1
                        refusedRunning[gid] = tries
                    }
                }
                // Finished, stopped or removed by the user
                else -> Unit
            }
        }
        return Plan(start, newUnstarted, newRunning, refusedUnstarted, refusedRunning)
    }
}
