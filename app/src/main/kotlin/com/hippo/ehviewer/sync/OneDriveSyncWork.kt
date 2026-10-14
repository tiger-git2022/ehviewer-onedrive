package com.hippo.ehviewer.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import java.util.concurrent.TimeUnit

private const val PERIODIC_WORK_NAME = "OneDriveSyncWork"
private const val CATCH_UP_WORK_NAME = "OneDriveSyncCatchUpWork"
private const val CONTINUATION_WORK_NAME = "OneDriveSyncContinuationWork"
private const val KEY_CONTINUATION = "continuation"
private const val MAX_CONTINUATION_RETRIES = 5

class OneDriveSyncWork(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result = withIOContext {
        if (!OneDriveClient.isSupported || !OneDriveSyncState.isSignedIn) return@withIOContext Result.success()
        OneDriveSync.sync(SyncTrigger.BACKGROUND).fold(
            onSuccess = {
                // A continuation whose refresh failed on E-Hentai (it does not fail the sync) tries again later
                val unfinished = inputData.getBoolean(KEY_CONTINUATION, false) && FavoritesIndexSync.hasUnfinishedRefresh()
                if (unfinished && runAttemptCount < MAX_CONTINUATION_RETRIES) Result.retry() else Result.success()
            },
            onFailure = {
                logcat(PERIODIC_WORK_NAME, it)
                // Signing in again needs the user, retrying cannot help
                if (it is OneDriveAuthException) Result.failure() else Result.retry()
            },
        )
    }
}

private fun syncConstraints() = Constraints.Builder()
    .setRequiredNetworkType(if (OneDriveSyncState.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
    .build()

/** Schedules or cancels the periodic sync after a change of the account or the auto sync options. */
fun updateOneDriveSyncWork(context: Context) {
    val workManager = WorkManager.getInstance(context)
    if (OneDriveClient.isSupported && OneDriveSyncState.isSignedIn && OneDriveSyncState.autoSync.value) {
        val interval = OneDriveSyncState.autoSyncIntervalMinutes.value.toLong().coerceAtLeast(15)
        val request = PeriodicWorkRequestBuilder<OneDriveSyncWork>(interval, TimeUnit.MINUTES)
            .setConstraints(syncConstraints())
            .setInitialDelay(interval, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    } else {
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(CATCH_UP_WORK_NAME)
    }
}

/** Syncs pending local changes once the network allows it, also after the app went to the background. */
fun enqueueOneDriveCatchUpWork(context: Context) {
    val request = OneTimeWorkRequestBuilder<OneDriveSyncWork>()
        .setConstraints(syncConstraints())
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(CATCH_UP_WORK_NAME, ExistingWorkPolicy.KEEP, request)
}

/**
 * Another sync after this one, for a refresh of the cloud favorites that ran out of time. Queued after a running one
 * (also when this sync is that work), whatever the automatic sync options.
 */
fun enqueueOneDriveContinuationWork(context: Context) {
    val request = OneTimeWorkRequestBuilder<OneDriveSyncWork>()
        .setConstraints(syncConstraints())
        .setInputData(workDataOf(KEY_CONTINUATION to true))
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(CONTINUATION_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
}
