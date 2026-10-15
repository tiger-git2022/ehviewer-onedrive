package com.hippo.ehviewer.updater

import com.ehviewer.core.files.write
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.executeAndParseAs
import com.hippo.ehviewer.spider.timeoutBySpeed
import com.hippo.ehviewer.util.copyTo
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.util.zip.ZipInputStream
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.io.asSource
import moe.tarsin.coroutines.runSuspendCatching
import okio.Path
import tachiyomi.data.release.Release

// Fork: updates come from the GitHub releases of this repository instead of the official Codeberg
// repository, so the official build never replaces the fork (they share the same signing key).
// The Build APK workflow publishes a numbered release ("v1.15.0-t3") for every push to main, see ForkVersion.
const val API_URL = "https://api.github.com/repos/${BuildConfig.REPO_NAME}"
const val LATEST_RELEASE_URL = "$API_URL/releases/latest"

// The workflow attaches update.json, the API's release object for that release, to every release. It is served like
// any release download, while api.github.com allows 60 requests an hour per IP without login, which shared
// mobile-network IPs often use up (HTTP 403). Releases from before the manifest existed fall back to the API.
const val RELEASES_URL = "https://github.com/${BuildConfig.REPO_NAME}/releases"
const val LATEST_MANIFEST_URL = "$RELEASES_URL/latest/download/update.json"

// Fork: the update interval that checks once on every app start, the default
const val UPDATE_ON_LAUNCH = -1

object AppUpdater {
    // A new activity (a rotation, a return from the background) is not a new start of the app
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
        checkedThisLaunch = true
        if (forceCheck || due) {
            Settings.lastUpdateTime = now.epochSeconds
            val release = fetchRelease(LATEST_MANIFEST_URL, LATEST_RELEASE_URL)
            val latestVersion = release.version.removePrefix("v")
            if (ForkVersion.isNewer(latestVersion, BuildConfig.RAW_VERSION_NAME)) {
                // The release notes list the changes since the previous release
                return EhRelease(latestVersion, release.info, release.getDownloadLink())
            }
        }
        return null
    }

    private suspend fun fetchRelease(manifestUrl: String, apiUrl: String): Release = runSuspendCatching { statement(manifestUrl).executeAndParseAs<Release>() }
        .getOrElse { githubStatement(apiUrl).executeAndParseAs<Release>() }

    /** Downloads to [path], telling [onProgress] the bytes done and the total, null while the total is unknown. */
    suspend fun downloadUpdate(url: String, path: Path, onProgress: (done: Long, total: Long?) -> Unit = { _, _ -> }) {
        val isZip = url.endsWith("zip")
        timeoutBySpeed(
            url,
            {
                statement(url) {
                    // https://docs.github.com/en/rest/releases/assets?apiVersion=2022-11-28#get-a-release-asset
                    if (!isZip) accept(ContentType.Application.OctetStream)
                    it()
                }
            },
            // timeoutBySpeed passes Long.MAX_VALUE without a Content-Length
            { total, done, _ -> onProgress(done, total.takeIf { it in 1..<Long.MAX_VALUE }) },
            { response ->
                if (isZip) {
                    response.bodyAsChannel().toInputStream().use { stream ->
                        ZipInputStream(stream).use { zip ->
                            zip.nextEntry
                            path.write { transferFrom(zip.asSource()) }
                        }
                    }
                } else {
                    response.bodyAsChannel().copyTo(path)
                }
            },
        )
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
)
