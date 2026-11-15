package com.hippo.ehviewer.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Debug
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import com.ehviewer.core.util.isAtLeastR
import com.ehviewer.core.util.logcat
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import logcat.LogPriority
import splitties.init.appCtx

/**
 * Fork: what the memory of the app looked like, to find out why it slowed down and closed after many galleries.
 * A line in the log at each gallery opened ([onGalleryOpened]), so the log saved with "Dump logcat" shows how it grew;
 * and in that zip the memory now and why the app closed the last times ([write]), as Android tells: a crash, a native
 * crash, or killed for memory (the system kills the app for memory without any crash log of the app).
 */
object MemoryReport {
    private const val TAG = "MemoryReport"
    private const val EXITS = 10
    private const val MAX_TRACE_CHARS = 64 * 1024
    private const val MB = 1024 * 1024

    @Volatile
    private var opened = 0

    /** A line of the memory in use, at the [gid]th gallery opened this run. */
    fun onGalleryOpened(gid: Long) {
        opened++
        logcat(TAG, LogPriority.INFO) { "gallery $gid (#$opened this run): ${heapLine()}" }
    }

    private fun heapLine(): String {
        val runtime = Runtime.getRuntime()
        val java = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val max = runtime.maxMemory() / MB
        val native = Debug.getNativeHeapAllocatedSize() / MB
        return "java ${java}MB of ${max}MB, native ${native}MB"
    }

    /** The memory now and the last exits of the app, for the zip of "Dump logcat". */
    fun write(writer: Writer) {
        writer.write("======== Memory ========\n")
        writer.write("Galleries opened this run: $opened\n")
        writer.write(heapLine())
        writer.write("\n")
        runCatching {
            val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            info.memoryStats.toSortedMap().forEach { (key, kb) -> writer.write("$key=${kb.toLong() / 1024}MB\n") }
        }.onFailure { writer.write("Memory stats: $it\n") }
        runCatching {
            val am = appCtx.getSystemService<ActivityManager>()!!
            val memory = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            writer.write("Device: available ${memory.availMem / MB}MB of ${memory.totalMem / MB}MB, low ${memory.lowMemory}, memory class ${am.memoryClass}MB, large ${am.largeMemoryClass}MB\n")
        }
        writer.write("\n")
        if (isAtLeastR) {
            runCatching { writeExits(writer) }.onFailure { writer.write("Exits: $it\n") }
        }
        writer.flush()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun writeExits(writer: Writer) {
        val am = appCtx.getSystemService<ActivityManager>()!!
        val exits = am.getHistoricalProcessExitReasons(appCtx.packageName, 0, EXITS)
        writer.write("======== Last exits (newest first) ========\n")
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        exits.forEach { exit ->
            writer.write("${format.format(Date(exit.timestamp))} ${reasonName(exit.reason)} (status ${exit.status}, importance ${exit.importance})")
            writer.write(" pss ${exit.pss / 1024}MB rss ${exit.rss / 1024}MB ${exit.processName}")
            exit.description?.let { writer.write(" - $it") }
            writer.write("\n")
            // The stack of a native crash, the threads of an ANR
            if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || exit.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching {
                    exit.traceInputStream?.bufferedReader()?.use { reader ->
                        val trace = CharArray(MAX_TRACE_CHARS)
                        val read = reader.read(trace)
                        if (read > 0) {
                            writer.write(trace, 0, read)
                            writer.write("\n")
                        }
                    }
                }
            }
        }
        writer.write("\n")
    }

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "REASON_$reason"
    }
}
