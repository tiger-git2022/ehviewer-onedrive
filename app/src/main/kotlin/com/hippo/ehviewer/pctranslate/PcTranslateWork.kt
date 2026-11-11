package com.hippo.ehviewer.pctranslate

import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import splitties.init.appCtx

private const val WORK_NAME = "PcTranslateWork"
private const val CHANNEL_ID = "pc_translate"
private const val NOTIFICATION_ID = 0x0D61
private const val RESULT_NOTIFICATION_ID = 0x0D62

// Asked every half minute while the PC answers, less often while it does not
private const val POLL_MILLIS = 30_000L
private const val UNREACHABLE_POLL_MILLIS = 120_000L

// A run stops after a day: the next start of the app goes on
private const val MAX_RUN_MILLIS = 24L * 60 * 60 * 1000

/**
 * Fork: sends the downloads queued by [PcTranslate] to the PC, then asks it every half minute about those it has and
 * takes back the packed ones, while any is left. Runs in the foreground with a quiet notification, so it goes on with
 * the screen off; started again each time the app comes to the front.
 */
class PcTranslateWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withIOContext {
        val start = System.currentTimeMillis()
        var status: String? = null
        suspend fun foreground() = runCatching { setForeground(foregroundInfo(applicationContext, status)) }
            .onFailure { logcat(WORK_NAME, it) }
        foreground()
        val doneBefore = PcTranslate.items.value.values.count { it.state == PcState.DONE }
        while (PcTranslate.hasWork() && System.currentTimeMillis() - start < MAX_RUN_MILLIS) {
            val reached = try {
                PcTranslate.runOnce { title ->
                    status = title
                    foreground()
                }
            } catch (e: PcAuthException) {
                logcat(WORK_NAME, e)
                PcTranslate.setServer(null)
                notifyResult(applicationContext, applicationContext.getString(R.string.pc_translate_unpaired))
                break
            } catch (e: Exception) {
                logcat(WORK_NAME, e)
                false
            }
            status = null
            foreground()
            if (!PcTranslate.hasWork()) break
            // Something sent meanwhile wakes it up before the time
            withTimeoutOrNull(if (reached) POLL_MILLIS else UNREACHABLE_POLL_MILLIS) { PcTranslate.kicks.first() }
        }
        val taken = PcTranslate.items.value.values.count { it.state == PcState.DONE } - doneBefore
        if (taken > 0) notifyResult(applicationContext, applicationContext.getString(R.string.pc_translate_taken_back, taken))
        Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext, null)

    companion object {
        /** Starts the work, or wakes up the running one: it sees what was just queued. */
        fun start(context: Context) {
            if (PcTranslate.server == null || !PcTranslate.hasWork()) return
            PcTranslate.kicks.tryEmit(Unit)
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PcTranslateWork>().build(),
            )
        }

        /** Goes on each time the app comes to the front, as after a reboot or a run that ended. */
        fun init() {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) runCatching { start(appCtx) }.onFailure { logcat(WORK_NAME, it) }
                },
            )
        }
    }
}

private fun createChannel(context: Context) {
    NotificationManagerCompat.from(context).createNotificationChannel(
        NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.pc_translate))
            .build(),
    )
}

private fun openAppIntent(context: Context): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
    PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
}

private fun foregroundInfo(context: Context, moving: String?): ForegroundInfo {
    createChannel(context)
    val items = PcTranslate.items.value.values
    val queued = items.count { it.state == PcState.QUEUED }
    val onPc = items.count { it.state == PcState.ON_PC }
    val text = moving?.let { context.getString(R.string.pc_translate_moving, it) }
        ?: context.getString(R.string.pc_translate_notification, queued, onPc)
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(context.getString(R.string.pc_translate))
        .setContentText(text)
        .setContentIntent(openAppIntent(context))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
        ForegroundInfo(NOTIFICATION_ID, notification)
    }
}

@Suppress("MissingPermission")
private fun notifyResult(context: Context, message: String) {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
    createChannel(context)
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
        .setContentTitle(context.getString(R.string.pc_translate))
        .setContentText(message)
        .setContentIntent(openAppIntent(context))
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(RESULT_NOTIFICATION_ID, notification)
}
