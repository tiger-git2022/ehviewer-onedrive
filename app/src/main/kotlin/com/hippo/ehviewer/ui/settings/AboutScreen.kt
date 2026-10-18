package com.hippo.ehviewer.ui.settings

import android.content.Context
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ehviewer.core.files.delete
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.withUIContext
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.download.downloadLocation
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.LicenseScreenDestination
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.ui.tools.dialog
import com.hippo.ehviewer.updater.AppUpdater
import com.hippo.ehviewer.updater.EhRelease
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.ProgressDialog
import com.hippo.ehviewer.util.ReadableTime
import com.hippo.ehviewer.util.displayString
import com.hippo.ehviewer.util.installPackage
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import moe.tarsin.coroutines.runSuspendCatching
import moe.tarsin.navigate
import moe.tarsin.snackbar
import moe.tarsin.string
import okio.Path

const val REPO_URL = "https://github.com/${BuildConfig.REPO_NAME}"
const val RELEASE_URL = "$REPO_URL/releases"

@Composable
@Stable
private fun versionCode() = "${BuildConfig.VERSION_NAME} (${BuildConfig.COMMIT_SHA})\n" + stringResource(R.string.settings_about_commit_time, AppConfig.commitTime)

@Composable
@Stable
private fun author() = AnnotatedString.fromHtml(stringResource(R.string.settings_about_author_summary).replace('$', '@'))

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.AboutScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    fun launchSnackbar(message: String) = launch { snackbar(message) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_about)) },
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection).verticalScroll(rememberScrollState()).padding(paddingValues)) {
            Preference(
                title = stringResource(id = R.string.settings_about_declaration),
                summary = stringResource(id = R.string.settings_about_declaration_summary),
            )
            HtmlPreference(
                title = stringResource(id = R.string.settings_about_author),
                summary = author(),
            )
            UrlPreference(
                title = stringResource(id = R.string.settings_about_latest_release),
                url = RELEASE_URL,
            )
            UrlPreference(
                title = stringResource(id = R.string.settings_about_source),
                url = REPO_URL,
            )
            Preference(title = stringResource(id = R.string.license)) {
                navigate(LicenseScreenDestination)
            }
            Preference(
                title = stringResource(id = R.string.settings_about_version),
                summary = versionCode(),
            )
            SwitchPreference(
                title = stringResource(id = R.string.backup_before_update),
                state = Settings.backupBeforeUpdate.asMutableState(),
            )
            SimpleMenuPreferenceInt(
                title = stringResource(id = R.string.auto_updates),
                entry = com.hippo.ehviewer.R.array.update_frequency,
                entryValueRes = com.hippo.ehviewer.R.array.update_frequency_values,
                state = Settings.updateIntervalDays.asMutableState(),
            )
            // Fork: "please wait" only while checking. Shown for the whole work, it lay over the new version and the
            // download dialogs and took their taps: stuck until the app was closed
            var checking by remember { mutableStateOf(false) }
            if (checking) ProgressDialog()
            Preference(title = stringResource(id = R.string.settings_about_check_for_updates)) {
                if (checking) return@Preference
                checking = true
                launchIO {
                    runSuspendCatching {
                        val release = try {
                            AppUpdater.checkForUpdate(true)
                        } finally {
                            checking = false
                        }
                        release?.let { showNewVersion(it) } ?: launchSnackbar(string(R.string.already_latest_version))
                    }.onFailure {
                        launchSnackbar(string(R.string.update_failed, it.displayString()))
                    }
                }
            }
        }
    }
}

context(_: Context, _: DialogState)
suspend fun showNewVersion(release: EhRelease) {
    awaitConfirmationOrCancel(
        confirmText = R.string.download,
        title = R.string.new_version_available,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = release.version,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = release.changelog)
        }
    }
    if (Settings.backupBeforeUpdate.value) {
        val time = ReadableTime.getFilenamableTime()
        EhDB.exportDB(downloadLocation / "$time.db")
    }
    val path = AppConfig.tempDir / "update.apk"
    awaitUpdateDownload(release.downloadLink, path.apply { delete() })
    withUIContext { installPackage(path.toFile()) }
}

/**
 * Fork: downloads the update in a dialog showing the bytes done of the total and the speed. Cancel or back stops the
 * download, a tap outside does not.
 */
context(_: DialogState)
private suspend fun awaitUpdateDownload(url: String, path: Path) = dialog<Unit> { cont ->
    var done by remember { mutableLongStateOf(0L) }
    var total by remember { mutableStateOf<Long?>(null) }
    var speed by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        val result = runSuspendCatching {
            AppUpdater.downloadUpdate(url, path) { d, t ->
                done = d
                total = t
            }
        }
        cont.resumeWith(result)
    }
    LaunchedEffect(Unit) {
        // Bytes of the last second
        var last = done
        while (true) {
            delay(1.seconds)
            speed = done - last
            last = done
        }
    }
    AlertDialog(
        onDismissRequest = { cont.cancel() },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { cont.cancel() }, shapes = ButtonDefaults.shapes()) {
                Text(text = stringResource(id = android.R.string.cancel))
            }
        },
        title = { Text(text = stringResource(id = R.string.update_downloading)) },
        text = {
            Column {
                val fraction = total?.let { done.toFloat() / it }
                SyncLinearProgress(fraction = fraction, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    val doneText = FileUtils.humanReadableByteCount(done)
                    Text(
                        text = total?.let {
                            stringResource(R.string.update_download_progress, doneText, FileUtils.humanReadableByteCount(it), (done * 100 / it).toInt())
                        } ?: doneText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    speed?.let {
                        Text(
                            text = stringResource(R.string.update_download_speed, FileUtils.humanReadableByteCount(it)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}
