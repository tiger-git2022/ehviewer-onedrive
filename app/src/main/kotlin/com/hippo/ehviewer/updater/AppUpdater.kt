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
import tachiyomi.data.release.CommitComparison
import tachiyomi.data.release.Release

// Fork: updates come from the GitHub releases of this repository instead of the official Codeberg
// repository, so the official build never replaces the fork (they share the same signing key).
// The CI channel follows the `nightly` pre-release that the Build APK workflow republishes on every push to main.
const val API_URL = "https://api.github.com/repos/${BuildConfig.REPO_NAME}"
const val LATEST_RELEASE_URL = "$API_URL/releases/latest"
const val NIGHTLY_RELEASE_URL = "$API_URL/releases/tags/nightly"

object AppUpdater {
    suspend fun checkForUpdate(forceCheck: Boolean = false): EhRelease? {
        val now = Clock.System.now()
        val last = Instant.fromEpochSeconds(Settings.lastUpdateTime)
        val interval = Settings.updateIntervalDays.value
        if (forceCheck || interval != 0 && now > last + interval.days) {
            Settings.lastUpdateTime = now.epochSeconds
            if (Settings.useCIUpdateChannel.value) {
                val curSha = BuildConfig.COMMIT_SHA
                val release = githubStatement(NIGHTLY_RELEASE_URL).executeAndParseAs<Release>()
                val shortSha = release.targetCommitish.take(7)
                if (shortSha.isNotEmpty() && shortSha != curSha) {
                    val changelog = runSuspendCatching {
                        val commitComparisonUrl = "$API_URL/compare/$curSha...$shortSha"
                        val result = githubStatement(commitComparisonUrl).executeAndParseAs<CommitComparison>()
                        // TODO: Prettier format, Markdown?
                        result.commits.joinToString("\n") { commit ->
                            "${commit.commit.message.takeWhile { it != '\n' }} (@${commit.commit.author.name})"
                        }
                    }.getOrDefault(release.info)
                    return EhRelease(shortSha, changelog, release.getDownloadLink())
                }
            } else {
                val curVersion = BuildConfig.RAW_VERSION_NAME
                val release = githubStatement(LATEST_RELEASE_URL).executeAndParseAs<Release>()
                val latestVersion = release.version.removePrefix("v")
                val description = release.info
                val downloadUrl = release.getDownloadLink()
                if (latestVersion != curVersion) {
                    return EhRelease(latestVersion, description, downloadUrl)
                }
            }
        }
        return null
    }

    suspend fun downloadUpdate(url: String, path: Path) {
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
            { _, _, _ -> },
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
