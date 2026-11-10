package com.hippo.ehviewer.updater

import com.ehviewer.core.files.write
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.executeAndParseAs
import com.hippo.ehviewer.client.executeSafely
import com.hippo.ehviewer.ktor.configureClient
import com.hippo.ehviewer.ktor.reset
import com.hippo.ehviewer.util.ensureSuccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.onDownload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlinx.io.asSource
import moe.tarsin.coroutines.runSuspendCatching
import okhttp3.ConnectionPool
import okhttp3.Protocol
import okio.Path
import tachiyomi.data.release.Release

// Fork: updates come from the GitHub releases of this repository instead of the official Codeberg
// repository, so the official build never replaces the fork (they share the same signing key).
// The Build APK workflow publishes a release ("v3.18.0", before "v1.15.0-t3") for every push to main, see ForkVersion.
const val API_URL = "https://api.github.com/repos/${BuildConfig.REPO_NAME}"
const val LATEST_RELEASE_URL = "$API_URL/releases/latest"

// The workflow attaches update.json, the API's release object for that release, to every release. It is served like
// any release download, while api.github.com allows 60 requests an hour per IP without login, which shared
// mobile-network IPs often use up (HTTP 403). Releases from before the manifest existed fall back to the API.
const val RELEASES_URL = "https://github.com/${BuildConfig.REPO_NAME}/releases"
const val LATEST_MANIFEST_URL = "$RELEASES_URL/latest/download/update.json"

// Fork: the update interval that checks once on every app start, the default
const val UPDATE_ON_LAUNCH = -1

// Fork: GitHub's download servers, or a proxy on the way, now and then answer 502/503/504, stall, or slow down for a
// while. The update download has no lower speed limit like the gallery downloads (Settings.timeoutSpeed, which failed
// a slow update download as a timeout within seconds). It gives up on an attempt only after STALL_TIMEOUT
// without a byte, and the next attempt goes on from the bytes already saved. It fails after DOWNLOAD_ATTEMPTS
// attempts in a row that got no byte further.
private const val DOWNLOAD_ATTEMPTS = 3
private val STALL_TIMEOUT = 30.seconds

// Fork: a connection to GitHub's download servers can drop from megabytes to some ten kilobytes a second in the middle
// of the file and stay there, while a new connection is fast again. When the speed over SPEED_WINDOW seconds falls
// below 1/SLOW_FRACTION of the best speed of this download (and under FAST_ENOUGH), the attempt ends and the next one
// goes on from the saved bytes on a new connection. An attempt runs at least MIN_ATTEMPT_SECONDS first, doubled after
// every such cut that did not get the speed back, up to MAX_ATTEMPT_SECONDS.
private const val SPEED_WINDOW = 5
private const val SLOW_FRACTION = 4
private const val FAST_ENOUGH = 1024L * 1024
private const val MIN_ATTEMPT_SECONDS = 8
private const val MAX_ATTEMPT_SECONDS = 64

private class SlowDownloadException(speed: Long) : IOException("Download slowed down to $speed B/s")

// The best speed of a download over SPEED_WINDOW, and the cuts in a row since the speed was last at least half of it
private class SpeedRecord {
    var best = 0L
    var slowCuts = 0
}

// Fork: the update download gets its own client. It never reuses a connection (HTTP/1.1, no idle connections kept),
// so an attempt after a slow one really opens a new connection, where the shared client (Cronet, HTTP/2) would go on
// on the same slow one.
private val updateClient by lazy {
    HttpClient(OkHttp) {
        engine {
            configureClient()
            config {
                protocols(listOf(Protocol.HTTP_1_1))
                connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            }
        }
        install(HttpTimeout) { reset() }
    }
}

object AppUpdater {
    // A new activity (a rotation, a return from the background) is not a new start of the app. Set once a check
    // finished: a rotation cancels a running one, the new activity then checks again.
    private var checkedThisLaunch = false

    suspend fun checkForUpdate(forceCheck: Boolean = false): EhRelease? {
        val now = Clock.System.now()
        val last = Instant.fromEpochSeconds(Settings.lastUpdateTime)
        val interval = Settings.updateIntervalDays.value
        val due = when (interval) {
            0 -> false
            UPDATE_ON_LAUNCH -> !checkedThisLaunch
            else -> now > last + interval.days
        }
        if (forceCheck || due) {
            Settings.lastUpdateTime = now.epochSeconds
            val release = fetchRelease(LATEST_MANIFEST_URL, LATEST_RELEASE_URL)
            checkedThisLaunch = true
            val latestVersion = release.version.removePrefix("v")
            if (ForkVersion.isNewer(latestVersion, BuildConfig.RAW_VERSION_NAME)) {
                // The release notes list the changes since the previous release
                return EhRelease(latestVersion, release.info, release.getDownloadLink(), release.releaseLink)
            }
        }
        return null
    }

    private suspend fun fetchRelease(manifestUrl: String, apiUrl: String): Release = runSuspendCatching { statement(manifestUrl).executeAndParseAs<Release>() }
        .getOrElse { githubStatement(apiUrl).executeAndParseAs<Release>() }

    /**
     * Downloads to [path], telling [onProgress] the bytes done and the total, null while the total is unknown. A failed
     * attempt goes on where it stopped, see [DOWNLOAD_ATTEMPTS].
     */
    suspend fun downloadUpdate(url: String, path: Path, onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> }) {
        // The fork's releases are APKs, a zip (upstream's CI artifacts) is unpacked while it downloads and starts over
        val resumable = !url.endsWith("zip")
        val file = path.toFile()
        val record = SpeedRecord()
        var failures = 0
        while (true) {
            val saved = if (resumable) file.length() else 0L
            try {
                downloadOnce(url, path, resumable, saved, record, onProgress)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (resumable && file.length() > saved) failures = 0 else failures++
                if (failures >= DOWNLOAD_ATTEMPTS) throw e
                // A slow connection is left for a new one right away
                if (e !is SlowDownloadException) delay((failures + 1).seconds * 2)
            }
        }
    }

    private suspend fun downloadOnce(
        url: String,
        path: Path,
        resumable: Boolean,
        saved: Long,
        record: SpeedRecord,
        onProgress: (done: Long, total: Long?) -> Unit,
    ) = coroutineScope {
        // Bytes of this response so far, the watchdog waits again on every change
        val received = MutableStateFlow(-1L)
        val stallWatchdog = launch {
            received.collectLatest {
                delay(STALL_TIMEOUT)
                throw SocketTimeoutException("No data for $STALL_TIMEOUT [url=$url]")
            }
        }
        val slowWatchdog = launch {
            if (!resumable) return@launch
            // Bytes received at the end of each of the last seconds
            val samples = ArrayDeque<Long>()
            var seconds = 0
            while (true) {
                delay(1.seconds)
                seconds++
                samples.addLast(received.value.coerceAtLeast(0))
                if (samples.size > SPEED_WINDOW + 1) samples.removeFirst()
                if (samples.size <= SPEED_WINDOW) continue
                val speed = (samples.last() - samples.first()) / SPEED_WINDOW
                record.best = maxOf(record.best, speed)
                if (speed >= record.best / 2) record.slowCuts = 0
                val minSeconds = (MIN_ATTEMPT_SECONDS shl record.slowCuts).coerceAtMost(MAX_ATTEMPT_SECONDS)
                if (seconds >= minSeconds && speed < record.best / SLOW_FRACTION && speed < FAST_ENOUGH) {
                    record.slowCuts = (record.slowCuts + 1).coerceAtMost(4)
                    throw SlowDownloadException(speed)
                }
            }
        }
        // Where this response starts in the file: saved when the server sends the rest (206), 0 when it sends it all
        var start = 0L
        try {
            updateClient.prepareGet(url) {
                // https://docs.github.com/en/rest/releases/assets?apiVersion=2022-11-28#get-a-release-asset
                if (resumable) accept(ContentType.Application.OctetStream)
                if (saved > 0) header(HttpHeaders.Range, "bytes=$saved-")
                // No time limit for the whole request, the default is 10 seconds
                timeout { reset() }
                onDownload { done, total ->
                    received.value = done
                    onProgress(start + done, total?.let { start + it })
                }
            }.executeSafely { response ->
                received.value = 0
                if (response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                    // The saved part does not fit the file on the server, start over
                    path.toFile().delete()
                }
                response.status.ensureSuccess()
                if (!resumable) {
                    response.bodyAsChannel().toInputStream().use { stream ->
                        ZipInputStream(stream).use { zip ->
                            zip.nextEntry
                            path.write { transferFrom(zip.asSource()) }
                        }
                    }
                } else {
                    val append = saved > 0 && response.status == HttpStatusCode.PartialContent
                    start = if (append) saved else 0
                    withIOContext {
                        FileOutputStream(path.toFile(), append).use { response.bodyAsChannel().copyTo(it) }
                    }
                }
            }
        } finally {
            stallWatchdog.cancel()
            slowWatchdog.cancel()
        }
    }
}

suspend inline fun githubStatement(url: String) = statement(url) {
    accept(ContentType.Application.Json)
    header("X-GitHub-Api-Version", "2022-11-28")
}

suspend inline fun statement(
    url: String,
    builder: HttpRequestBuilder.() -> Unit = {},
) = ktorClient.prepareGet(url) {
    apply(builder)
}

data class EhRelease(
    val version: String,
    val changelog: String,
    val downloadLink: String,
    // Fork: the release page, to download in the browser when the download in the app fails
    val releaseLink: String,
)
