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
import com.hippo.ehviewer.util.FileUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx

private const val WORK_NAME = "PcTranslateWork"
private const val CHANNEL_ID = "pc_translate"
private const val NOTIFICATION_ID = 0x0D61
private const val RESULT_NOTIFICATION_ID = 0x0D62

// Rounds in one run at most, while more is queued during them
private const val MAX_ROUNDS = 20

/**
 * Fork: sends the downloads queued by [PcTranslate] to the PC, asks it about those it has and takes back the packed
 * ones, in one round. Not asked again in the background while the translation is made (that takes hours): each time
 * the app comes to the front or 「电脑翻译」 opens, it asks again. In the foreground with a notification only while a
 * file moves, so a transfer goes on with the screen off; no notification stays while it waits.
 */
class PcTranslateWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withIOContext {
        var status: String? = null
        var shown: PcTransfer? = null
        var inForeground = false

        // The notification from the first file moved on, not for asking the PC only
        suspend fun foreground() {
            if (status == null && !inForeground) return
            inForeground = true
            runCatching {
                shown = PcTranslate.transfer.value
                setForeground(foregroundInfo(applicationContext, status, shown))
            }.onFailure { logcat(WORK_NAME, it) }
        }

        // The bytes moved, in the notification once a second
        val progress = launch {
            while (isActive) {
                delay(1000)
                if (inForeground && PcTranslate.transfer.value != shown) foreground()
            }
        }
        val doneBefore = PcTranslate.items.value.values.count { it.state == PcState.DONE }
        var reached = false
        var rounds = 0
        while (rounds++ < MAX_ROUNDS) {
            val roundStart = System.currentTimeMillis()
            reached = try {
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
            if (inForeground) foreground()
            // Sent while the round asked the PC: another round at once, not a minute later
            val queuedMeanwhile = PcTranslate.items.value.values.any { it.state == PcState.QUEUED && it.message == null && it.time >= roundStart }
            if (!reached || !queuedMeanwhile) break
        }
        progress.cancel()
        val taken = PcTranslate.items.value.values.count { it.state == PcState.DONE } - doneBefore
        if (taken > 0) notifyResult(applicationContext, applicationContext.getString(R.string.pc_translate_taken_back, taken))
        Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext, null, null)

    companion object {
        /** Starts the work; a running one sends what was just queued too, in another round. */
        fun start(context: Context) {
            if (PcTranslate.server == null || !PcTranslate.hasWork()) return
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

private fun foregroundInfo(context: Context, moving: String?, transfer: PcTransfer?): ForegroundInfo {
    createChannel(context)
    val items = PcTranslate.items.value.values
    val queued = items.count { it.state == PcState.QUEUED }
    val onPc = items.count { it.state == PcState.ON_PC }
    val text = moving?.let {
        when (transfer?.sending) {
            true -> context.getString(R.string.pc_translate_sending, it)
            false -> context.getString(R.string.pc_translate_taking, it)
            null -> context.getString(R.string.pc_translate_moving, it)
        }
    } ?: context.getString(R.string.pc_translate_notification, queued, onPc)
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(context.getString(R.string.pc_translate))
        .setContentText(text)
        .apply {
            if (moving != null && transfer != null) {
                setProgress(100, transfer.percent.coerceAtLeast(0), transfer.percent < 0)
                setSubText(transferText(context, transfer))
            }
        }
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

/** "12.3 MB / 45.6 MB · 27%", or only the bytes moved while the size is unknown. */
fun transferText(context: Context, transfer: PcTransfer): String {
    val done = FileUtils.humanReadableByteCount(transfer.done)
    return if (transfer.total > 0) {
        context.getString(R.string.pc_translate_progress, done, FileUtils.humanReadableByteCount(transfer.total), transfer.percent)
    } else {
        done
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
