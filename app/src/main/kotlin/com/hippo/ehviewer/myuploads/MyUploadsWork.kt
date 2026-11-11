package com.hippo.ehviewer.myuploads

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ehviewer.core.network.EhCookieStore
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import java.util.concurrent.TimeUnit
import splitties.init.appCtx

private const val WORK_NAME = "MyUploadsWork"

/**
 * Fork: reads 「我的上传」 again every hour while online, quietly (no notification): the galleries due only, see
 * [MyUploads.isDue]. Only once the page was opened, and while signed in.
 */
class MyUploadsWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withIOContext {
        if (!MyUploads.used || !EhCookieStore.hasSignedIn()) return@withIOContext Result.success()
        runCatching { MyUploads.refresh() }.onFailure { logcat(WORK_NAME, it) }
        Result.success()
    }

    companion object {
        /** Starts the hourly work, once the page was opened; kept as it is when there already. */
        fun schedule(context: Context = appCtx) {
            if (!MyUploads.used) return
            val request = PeriodicWorkRequestBuilder<MyUploadsWork>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }.onFailure { logcat(WORK_NAME, it) }
        }
    }
}
