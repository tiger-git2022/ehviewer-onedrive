package com.hippo.ehviewer.sync

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PERIODIC_WORK_NAME = "OneDriveSyncWork"
private const val CATCH_UP_WORK_NAME = "OneDriveSyncCatchUpWork"

// A continuation of a refresh by the sync itself, from before CloudFavoritesWork: only cancelled now
private const val CONTINUATION_WORK_NAME = "OneDriveSyncContinuationWork"
private const val CLOUD_FAVORITES_WORK_NAME = "CloudFavoritesWork"
private const val MANUAL_WORK_NAME = "OneDriveManualSyncWork"
private const val KEY_FULL = "full"
private const val MAX_CLOUD_FAVORITES_ATTEMPTS = 10
private const val CHANNEL_ID = "onedrive_sync"
private const val NOTIFICATION_ID = 0x0D51
private const val MANUAL_NOTIFICATION_ID = 0x0D52
private const val MANUAL_RESULT_NOTIFICATION_ID = 0x0D53
private const val NOTIFICATION_INTERVAL_MILLIS = 2_000L

class OneDriveSyncWork(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result = withIOContext {
        if (!OneDriveClient.isSupported || !OneDriveSyncState.isSignedIn) return@withIOContext Result.success()
        OneDriveSync.sync(SyncTrigger.BACKGROUND).fold(
            onSuccess = { Result.success() },
            onFailure = {
                logcat(PERIODIC_WORK_NAME, it)
                // Signing in again needs the user, retrying cannot help
                if (it is OneDriveAuthException) Result.failure() else Result.retry()
            },
        )
    }
}

/**
 * A sync started from the sync icon or the settings, see [OneDriveSync.syncManually]. Runs as expedited work in the
 * foreground with a notification of its step, so it goes on after the app went to the background. Stopped by Android
 * anyway, WorkManager runs it again. Shows the result itself when no screen waits for it: a toast while the app is
 * shown, else a notification.
 */
class OneDriveManualSyncWork(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    @OptIn(FlowPreview::class)
    override suspend fun doWork(): Result = withIOContext {
        runCatching { setForeground(manualSyncForegroundInfo(applicationContext, null)) }.onFailure { logcat(MANUAL_WORK_NAME, it) }
        val (result, taken) = coroutineScope {
            val notification = launch {
                OneDriveSync.progress.filterNotNull().sample(NOTIFICATION_INTERVAL_MILLIS).collect { progress ->
                    runCatching { setForeground(manualSyncForegroundInfo(applicationContext, progress)) }
                }
            }
            try {
                OneDriveSync.runManualSync()
            } finally {
                notification.cancel()
            }
        }
        if (!taken) showManualSyncResult(applicationContext, OneDriveSync.summaryOf(result), result.isFailure)
        // A failed sync is shown, the user decides whether to sync again
        Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = manualSyncForegroundInfo(applicationContext, OneDriveSync.progress.value)
}

private fun createSyncChannel(context: Context) {
    NotificationManagerCompat.from(context).createNotificationChannel(
        NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.onedrive_sync_channel))
            .build(),
    )
}

private fun openAppIntent(context: Context): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
    PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
}

private fun dataSyncForegroundInfo(id: Int, notification: Notification): ForegroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
} else {
    ForegroundInfo(id, notification)
}

/** "Syncing 40% (2/5): Favorites · downloading". */
private fun manualSyncForegroundInfo(context: Context, progress: SyncRunProgress?): ForegroundInfo {
    createSyncChannel(context)
    val text = progress?.let {
        val title = context.getString(it.title)
        val name = it.phase?.let { phase -> context.getString(R.string.onedrive_sync_step_phase, title, context.getString(phase)) } ?: title
        context.getString(R.string.onedrive_sync_progress, it.percent, it.step, it.steps, name)
    } ?: context.getString(R.string.onedrive_sync_running)
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(context.getString(R.string.onedrive_sync_channel))
        .setContentText(text)
        .setProgress(100, progress?.percent ?: 0, progress == null)
        .setContentIntent(openAppIntent(context))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .build()
    return dataSyncForegroundInfo(MANUAL_NOTIFICATION_ID, notification)
}

private suspend fun showManualSyncResult(context: Context, message: String, failed: Boolean) {
    val shown = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED).also {
            if (it) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    if (shown) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    createSyncChannel(context)
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_notify_sync_noanim)
        .setContentTitle(context.getString(R.string.onedrive_sync_channel))
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setContentIntent(openAppIntent(context))
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(MANUAL_RESULT_NOTIFICATION_ID, notification)
}

/**
 * Reads the cloud favorites page by page and looks up their Japanese titles, see [FavoritesIndexSync.runWork]. With
 * many favorites this takes hours, so it runs in the foreground with a notification when Android allows it (started
 * while the app is open): leaving the app and the 10 minute limit of background work do not stop it. Stopped anyway,
 * it goes on from its checkpoint.
 */
class CloudFavoritesWork(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    @OptIn(FlowPreview::class)
    override suspend fun doWork(): Result = withIOContext {
        if (!OneDriveClient.isSupported || !OneDriveSyncState.isSignedIn || !OneDriveSyncState.exportFavoritesIndex.value) {
            return@withIOContext Result.success()
        }
        runCatching { setForeground(foregroundInfo(null)) }.onFailure { logcat(CLOUD_FAVORITES_WORK_NAME, it) }
        coroutineScope {
            val notification = launch {
                FavoritesIndexSync.workProgress.sample(NOTIFICATION_INTERVAL_MILLIS).collect { progress ->
                    runCatching { setForeground(foregroundInfo(progress)) }
                }
            }
            try {
                // The input only on the first attempt: a retry (the final upload failed) must not read every slot again
                val full = OneDriveSyncState.cloudFavoritesFullRequested.value || runAttemptCount == 0 && inputData.getBoolean(KEY_FULL, false)
                FavoritesIndexSync.runWork(full)
                OneDriveSyncState.withMutableSnapshot { it[OneDriveSyncState.cloudFavoritesWorkError] = null }
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // E-Hentai or the network failed: the checkpoint keeps what was read, the settings show why
                logcat(CLOUD_FAVORITES_WORK_NAME, e)
                OneDriveSyncState.withMutableSnapshot { it[OneDriveSyncState.cloudFavoritesWorkError] = e.message ?: e.toString() }
                if (runAttemptCount < MAX_CLOUD_FAVORITES_ATTEMPTS) Result.retry() else Result.failure()
            } finally {
                notification.cancel()
            }
        }
    }

    private fun foregroundInfo(progress: StepProgress?): ForegroundInfo {
        val context = applicationContext
        createSyncChannel(context)
        val text = progress?.let {
            val name = context.getString(it.title)
            val percent = it.percent
            if (percent != null) context.getString(R.string.onedrive_sync_progress_count, name, it.done, it.total, percent) else name
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.onedrive_sync_cloud_favorites_work))
            .setContentText(text)
            .setProgress(progress?.total ?: 0, progress?.done ?: 0, progress?.percent == null)
            .setContentIntent(openAppIntent(context))
            .addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.onedrive_sync_cloud_favorites_work_pause),
                WorkManager.getInstance(context).createCancelPendingIntent(id),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return dataSyncForegroundInfo(NOTIFICATION_ID, notification)
    }
}

private fun syncConstraints() = Constraints.Builder()
    .setRequiredNetworkType(if (OneDriveSyncState.wifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
    .build()

/** Schedules or cancels the periodic sync after a change of the account or the auto sync options, and the cloud favorites work. */
fun updateOneDriveSyncWork(context: Context) {
    val workManager = WorkManager.getInstance(context)
    workManager.cancelUniqueWork(CONTINUATION_WORK_NAME)
    // A work waiting for the network would otherwise stay queued, and show, until it gets it
    if (!OneDriveSyncState.isSignedIn || !OneDriveSyncState.exportFavoritesIndex.value) workManager.cancelUniqueWork(CLOUD_FAVORITES_WORK_NAME)
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

/** Fork: starts [OneDriveManualSyncWork] unless one is queued or runs. No network constraint: offline, it fails and says so. */
fun enqueueOneDriveManualSyncWork(context: Context) {
    val request = OneTimeWorkRequestBuilder<OneDriveManualSyncWork>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.KEEP, request)
}

/** Syncs pending local changes once the network allows it, also after the app went to the background. */
fun enqueueOneDriveCatchUpWork(context: Context) {
    val request = OneTimeWorkRequestBuilder<OneDriveSyncWork>()
        .setConstraints(syncConstraints())
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(CATCH_UP_WORK_NAME, ExistingWorkPolicy.KEEP, request)
}

/**
 * State of [CloudFavoritesWork]: [running], or [waiting] to run (again: after a failure, after Android stopped it,
 * or for the network), at [nextRunAt] when WorkManager knows it.
 */
data class CloudFavoritesWorkInfo(val running: Boolean = false, val waiting: Boolean = false, val nextRunAt: Long? = null) {
    /** Its own saves mark the favorites index as changed meanwhile, and it uploads them itself. */
    val active: Boolean
        get() = running || waiting
}

fun cloudFavoritesWorkInfo(context: Context): Flow<CloudFavoritesWorkInfo> = WorkManager.getInstance(context)
    .getWorkInfosForUniqueWorkFlow(CLOUD_FAVORITES_WORK_NAME)
    .map { infos ->
        val active = infos.filterNot { it.state.isFinished }
        val running = active.any { it.state == WorkInfo.State.RUNNING }
        val nextRunAt = active.filter { it.state == WorkInfo.State.ENQUEUED }
            .map { it.nextScheduleTimeMillis }
            .filter { it in 1 until Long.MAX_VALUE }
            .minOrNull()
        CloudFavoritesWorkInfo(running = running, waiting = !running && active.isNotEmpty(), nextRunAt = if (running) null else nextRunAt)
    }
    .distinctUntilChanged()

/**
 * Starts [CloudFavoritesWork] now unless it runs: one waiting to go on (after failures its retries come hours apart,
 * and started from the background it has no notification) is replaced, so it starts in the foreground.
 */
suspend fun resumeCloudFavoritesWork(context: Context) {
    if (!OneDriveClient.isSupported || !OneDriveSyncState.isSignedIn) return
    if (cloudFavoritesWorkInfo(context).first().running) return
    enqueueCloudFavoritesWork(context, policy = ExistingWorkPolicy.REPLACE)
}

/** The app came to the foreground: work waiting to go on starts now, see [resumeCloudFavoritesWork]. */
suspend fun resumeWaitingCloudFavoritesWork(context: Context) {
    if (cloudFavoritesWorkInfo(context).first().waiting) resumeCloudFavoritesWork(context)
}

/**
 * Starts [CloudFavoritesWork] unless it runs already, whatever the automatic sync options. [full] reads all slots
 * again, also when the work runs (it starts over from the checkpoint).
 */
fun enqueueCloudFavoritesWork(
    context: Context,
    full: Boolean = false,
    policy: ExistingWorkPolicy = if (full) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
) {
    // Kept until the work has widened its refresh, also through a replaced or retried work
    if (full) OneDriveSyncState.cloudFavoritesFullRequested.value = true
    val request = OneTimeWorkRequestBuilder<CloudFavoritesWork>()
        .setConstraints(syncConstraints())
        .setInputData(workDataOf(KEY_FULL to full))
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(CLOUD_FAVORITES_WORK_NAME, policy, request)
}
