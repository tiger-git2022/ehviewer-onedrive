package com.hippo.ehviewer.ui.settings

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.LabeledCheckbox
import com.ehviewer.core.util.launch
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.sync.AutoDownload
import com.hippo.ehviewer.sync.CLOUD_SLOT_COUNT
import com.hippo.ehviewer.sync.CloudFavoritesWorkInfo
import com.hippo.ehviewer.sync.FavoritesIndexSync
import com.hippo.ehviewer.sync.OneDriveClient
import com.hippo.ehviewer.sync.OneDriveSync
import com.hippo.ehviewer.sync.OneDriveSyncState
import com.hippo.ehviewer.sync.cloudFavoritesWorkInfo
import com.hippo.ehviewer.sync.resumeCloudFavoritesWork
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.OneDriveDevicesScreenDestination
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.ui.tools.awaitInputText
import com.hippo.ehviewer.ui.tools.awaitResult
import com.hippo.ehviewer.util.ReadableTime
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.util.Date
import me.zhanghai.compose.preference.DropdownListPreference
import moe.tarsin.navigate
import moe.tarsin.snackbar
import moe.tarsin.string
import splitties.init.appCtx

private val syncIntervals = intArrayOf(15, 30, 60, 180, 360, 720, 1440)

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.OneDriveSyncScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_onedrive_sync)) },
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        Column(modifier = Modifier.padding(it).nestedScroll(scrollBehavior.nestedScrollConnection).verticalScroll(rememberScrollState())) {
            if (!OneDriveClient.isSupported) {
                Preference(
                    title = stringResource(id = R.string.onedrive_sync_unsupported),
                    summary = stringResource(id = R.string.onedrive_sync_unsupported_summary),
                )
            } else {
                val accountName by OneDriveSyncState.accountName.collectAsState()
                val accountId by OneDriveSyncState.accountId.collectAsState()
                if (accountId == null) {
                    Preference(
                        title = stringResource(id = R.string.onedrive_sync_sign_in),
                        summary = stringResource(id = R.string.onedrive_sync_sign_in_summary),
                    ) {
                        launch {
                            val message = runCatching { OneDriveSync.signIn(contextOf<MainActivity>()) }.fold(
                                onSuccess = { account -> string(R.string.onedrive_sync_signed_in_as, account.name) },
                                onFailure = { e -> string(R.string.onedrive_sync_sign_in_failed, e.message ?: e.toString()) },
                            )
                            snackbar(message)
                        }
                    }
                } else {
                    Preference(
                        title = stringResource(id = R.string.onedrive_sync_signed_in_as, accountName.orEmpty()),
                        summary = stringResource(id = R.string.onedrive_sync_sign_out),
                    ) {
                        launch {
                            awaitConfirmationOrCancel(confirmText = R.string.onedrive_sync_sign_out) {
                                Text(text = stringResource(id = R.string.onedrive_sync_sign_out_confirm))
                            }
                            OneDriveSync.signOut()
                        }
                    }
                    val syncing by OneDriveSync.running.collectAsState()
                    // Fork: also while the manual sync work is queued, not started yet
                    val manualQueued by OneDriveSync.manualSyncQueued.collectAsState()
                    val running = syncing || manualQueued
                    val progress by OneDriveSync.progress.collectAsState()
                    val lastSyncTime by OneDriveSyncState.lastSyncTime.collectAsState()
                    val lastSyncSummary by OneDriveSyncState.lastSyncSummary.collectAsState()
                    val lastSyncFailed by OneDriveSyncState.lastSyncFailed.collectAsState()
                    val syncSummary = when {
                        running -> progress?.let { syncProgressText(it) } ?: stringResource(id = R.string.onedrive_sync_running)
                        lastSyncTime == 0L -> stringResource(id = R.string.onedrive_sync_never)
                        else -> stringResource(
                            id = if (lastSyncFailed) R.string.onedrive_sync_last_failed else R.string.onedrive_sync_last_success,
                            ReadableTime.getTimeAgo(lastSyncTime),
                            lastSyncSummary.orEmpty(),
                        )
                    }
                    Preference(title = stringResource(id = R.string.onedrive_sync_now), summary = syncSummary) {
                        if (!running) {
                            launch {
                                val result = OneDriveSync.syncManually()
                                snackbar(OneDriveSync.summaryOf(result))
                            }
                        }
                    }
                    SyncProgressBar(fraction = progress?.fraction?.takeIf { running })
                    SwitchPreference(
                        title = stringResource(id = R.string.onedrive_sync_show_progress_bar),
                        summary = stringResource(id = R.string.onedrive_sync_show_progress_bar_summary),
                        state = OneDriveSyncState.showProgressBar.asMutableState(),
                    )

                    val autoSync = OneDriveSyncState.autoSync.asMutableState()
                    SwitchPreference(
                        title = stringResource(id = R.string.onedrive_sync_auto),
                        summary = stringResource(id = R.string.onedrive_sync_auto_summary),
                        state = autoSync,
                    )
                    AnimatedVisibility(visible = autoSync.value) {
                        Column {
                            val interval = OneDriveSyncState.autoSyncIntervalMinutes.asMutableState()
                            val intervalNames = syncIntervals.associateWith { minutes ->
                                when {
                                    minutes < 60 -> stringResource(id = R.string.onedrive_sync_interval_minutes, minutes)
                                    minutes == 60 -> stringResource(id = R.string.onedrive_sync_interval_hour)
                                    else -> stringResource(id = R.string.onedrive_sync_interval_hours, minutes / 60)
                                }
                            }
                            DropdownListPreference(
                                state = interval,
                                items = intervalNames,
                                title = { Text(text = stringResource(id = R.string.onedrive_sync_interval)) },
                                summary = { Text(text = intervalNames[interval.value].orEmpty()) },
                            )
                            SwitchPreference(
                                title = stringResource(id = R.string.onedrive_sync_on_change),
                                summary = stringResource(id = R.string.onedrive_sync_on_change_summary),
                                state = OneDriveSyncState.syncOnChange.asMutableState(),
                            )
                            SwitchPreference(
                                title = stringResource(id = R.string.onedrive_sync_wifi_only),
                                state = OneDriveSyncState.wifiOnly.asMutableState(),
                            )
                        }
                    }

                    OneDriveSyncSubtitle(R.string.onedrive_sync_content)
                    SwitchPreference(
                        title = stringResource(id = R.string.onedrive_sync_category_settings),
                        summary = stringResource(id = R.string.onedrive_sync_category_settings_summary) + "\n" + stringResource(id = R.string.onedrive_sync_manual_only),
                        state = OneDriveSyncState.syncSettings.asMutableState(),
                    )
                    SwitchPreference(
                        title = stringResource(id = R.string.onedrive_sync_category_favorites),
                        state = OneDriveSyncState.syncFavorites.asMutableState(),
                    )
                    SwitchPreference(
                        title = stringResource(id = R.string.onedrive_sync_category_progress),
                        state = OneDriveSyncState.syncProgress.asMutableState(),
                    )
                    SwitchPreference(
                        title = stringResource(id = R.string.onedrive_sync_category_history),
                        summary = stringResource(id = R.string.onedrive_sync_category_history_summary),
                        state = OneDriveSyncState.syncHistory.asMutableState(),
                    )
                    SwitchPreference(
                        title = stringResource(id = R.string.onedrive_sync_category_quick_search_filters),
                        summary = stringResource(id = R.string.onedrive_sync_manual_only),
                        state = OneDriveSyncState.syncQuickSearch.asMutableState(),
                    )
                    SwitchPreference(
                        title = stringResource(id = R.string.onedrive_sync_category_suggestions),
                        state = OneDriveSyncState.syncSuggestions.asMutableState(),
                    )
                    SwitchPreference(
                        title = stringResource(id = R.string.onedrive_sync_upload_downloads),
                        summary = stringResource(id = R.string.onedrive_sync_upload_downloads_summary) + "\n" + stringResource(id = R.string.onedrive_sync_manual_only),
                        state = OneDriveSyncState.uploadDownloads.asMutableState(),
                    )
                    val exportIndex = OneDriveSyncState.exportFavoritesIndex.asMutableState()
                    SwitchPreference(
                        title = stringResource(id = R.string.onedrive_sync_favorites_index),
                        summary = stringResource(id = R.string.onedrive_sync_favorites_index_summary),
                        state = exportIndex,
                    )
                    AnimatedVisibility(visible = exportIndex.value) {
                        val working by FavoritesIndexSync.working.collectAsState()
                        val workProgress by FavoritesIndexSync.workProgress.collectAsState()
                        val workText = workProgress?.let { stepProgressText(it) } ?: stringResource(id = R.string.onedrive_sync_favorites_index_refreshing)
                        val workInfo by remember { cloudFavoritesWorkInfo(appCtx) }.collectAsState(initial = CloudFavoritesWorkInfo())
                        val workError by OneDriveSyncState.cloudFavoritesWorkError.collectAsState()
                        val fullRequested by OneDriveSyncState.cloudFavoritesFullRequested.collectAsState()
                        // Read again when the work starts or stops
                        val status by produceState<FavoritesIndexSync.Status?>(null, working, workInfo) { value = FavoritesIndexSync.status() }
                        // Also stopped before its first page was saved: no checkpoint yet, but the reason or the asked read is kept
                        val unfinished = status?.let { it.unfinished || it.missingTitles > 0 } == true || workInfo.waiting || workError != null || fullRequested
                        val statusText = status.takeIf { unfinished }?.let { cloudFavoritesStatusText(it, workInfo, workError) }
                        Column {
                            Preference(
                                title = stringResource(id = R.string.onedrive_sync_favorites_index_refresh),
                                summary = when {
                                    working -> workText
                                    statusText != null -> statusText
                                    // Fork: read completely, and when
                                    (status?.fullReadAt ?: 0L) > 0L -> stringResource(
                                        id = R.string.onedrive_sync_cloud_favorites_last_full,
                                        dateTime(status!!.fullReadAt),
                                        status!!.fullReadCount,
                                    )
                                    else -> stringResource(id = R.string.onedrive_sync_favorites_index_refresh_summary)
                                },
                            ) {
                                launch {
                                    if (!Settings.hasSignedIn.value) {
                                        snackbar(string(R.string.onedrive_sync_favorites_index_not_signed_in))
                                        return@launch
                                    }
                                    when {
                                        working -> snackbar(workText)
                                        // Goes on from where it stopped, now and in the foreground
                                        unfinished -> {
                                            resumeCloudFavoritesWork(appCtx)
                                            snackbar(string(R.string.onedrive_sync_cloud_favorites_resumed))
                                        }
                                        else -> {
                                            // Fork: read before: reading every page again takes long, asked first. Asked now,
                                            // the status shown may not be loaded yet
                                            val done = FavoritesIndexSync.status().takeIf { it.readBefore }
                                            if (done != null) {
                                                awaitConfirmationOrCancel(
                                                    confirmText = R.string.onedrive_sync_cloud_favorites_reread,
                                                    title = R.string.onedrive_sync_favorites_index_refresh,
                                                ) {
                                                    val count = done.fullReadCount
                                                    val last = if (done.fullReadAt > 0L) {
                                                        stringResource(id = R.string.onedrive_sync_cloud_favorites_reread_last, dateTime(done.fullReadAt), count)
                                                    } else {
                                                        null
                                                    }
                                                    // About a second a page, 25 to 50 favorites a page as the account shows them
                                                    val minutes = if (count > 0) {
                                                        stringResource(id = R.string.onedrive_sync_cloud_favorites_reread_minutes, (count / 50 + 59) / 60 + 1, (count / 25 + 59) / 60 + 1)
                                                    } else {
                                                        null
                                                    }
                                                    val about = stringResource(id = R.string.onedrive_sync_cloud_favorites_reread_confirm)
                                                    Text(text = listOfNotNull(last, about, minutes).joinToString(PARAGRAPH))
                                                }
                                            }
                                            FavoritesIndexSync.refreshInBackground()
                                            snackbar(string(R.string.onedrive_sync_favorites_index_refresh_started))
                                        }
                                    }
                                }
                            }
                            SyncProgressBar(fraction = workProgress?.fraction?.takeIf { working })
                            // Fork: new galleries of the chosen slots go to the downloads
                            var autoDownloadSlots by OneDriveSyncState.autoDownloadSlots.asMutableState()
                            val chosenSlots = OneDriveSyncState.parseSlots(autoDownloadSlots)
                            LaunchedEffect(autoDownloadSlots) { AutoDownload.onOptionChanged() }
                            val slotFormat = stringResource(id = R.string.onedrive_sync_auto_download_slot)
                            val separator = stringResource(id = R.string.onedrive_sync_auto_download_separator)
                            Preference(
                                title = stringResource(id = R.string.onedrive_sync_auto_download),
                                summary = if (chosenSlots.isEmpty()) {
                                    stringResource(id = R.string.onedrive_sync_auto_download_off)
                                } else {
                                    stringResource(
                                        id = R.string.onedrive_sync_auto_download_summary,
                                        chosenSlots.joinToString(separator) { slotFormat.format(Settings.favCat[it]) },
                                    )
                                },
                            ) {
                                launch {
                                    val chosen = awaitResult(chosenSlots, title = R.string.onedrive_sync_auto_download) {
                                        Column {
                                            (0 until CLOUD_SLOT_COUNT).forEach { slot ->
                                                LabeledCheckbox(
                                                    checked = slot in expectedValue,
                                                    onCheckedChange = { checked ->
                                                        expectedValue = if (checked) expectedValue + slot else expectedValue - slot
                                                    },
                                                    label = Settings.favCat[slot],
                                                )
                                            }
                                        }
                                    }
                                    autoDownloadSlots = chosen.sorted().joinToString(",")
                                }
                            }
                        }
                    }

                    OneDriveSyncSubtitle(R.string.onedrive_sync_devices)
                    val deviceName by OneDriveSyncState.deviceName.collectAsState()
                    val deviceNameTitle = stringResource(id = R.string.onedrive_sync_device_name)
                    Preference(title = deviceNameTitle, summary = deviceName ?: OneDriveSync.defaultDeviceName()) {
                        launch {
                            val name = awaitInputText(initial = OneDriveSync.deviceName, title = deviceNameTitle)
                            OneDriveSync.setDeviceName(name)
                        }
                    }
                    Preference(
                        title = stringResource(id = R.string.onedrive_sync_other_devices),
                        summary = stringResource(id = R.string.onedrive_sync_other_devices_summary),
                    ) {
                        navigate(OneDriveDevicesScreenDestination)
                    }
                    Preference(
                        title = stringResource(id = R.string.onedrive_sync_clear_cloud),
                        summary = stringResource(id = R.string.onedrive_sync_clear_cloud_summary),
                    ) {
                        launch {
                            awaitConfirmationOrCancel(confirmText = R.string.onedrive_sync_clear_cloud) {
                                Text(text = stringResource(id = R.string.onedrive_sync_clear_cloud_confirm))
                            }
                            val message = runCatching { OneDriveSync.clearCloudData() }.fold(
                                onSuccess = { string(R.string.onedrive_sync_cloud_cleared) },
                                onFailure = { e -> e.message ?: e.toString() },
                            )
                            snackbar(message)
                        }
                    }
                }
            }
            OneDriveSyncNote()
        }
    }
}

private const val PARAGRAPH = "\n\n"

/** Fork: a date and time in the format of the system settings. */
@Composable
private fun dateTime(time: Long): String {
    val context = LocalContext.current
    val date = Date(time)
    return "${DateFormat.getDateFormat(context).format(date)} ${DateFormat.getTimeFormat(context).format(date)}"
}

/** Where an unfinished read of the cloud favorites stands while the work does not run, and why it stopped. */
@Composable
private fun cloudFavoritesStatusText(status: FavoritesIndexSync.Status, workInfo: CloudFavoritesWorkInfo, error: String?): String {
    val context = LocalContext.current
    return buildList {
        if (status.unfinished) add(stringResource(id = R.string.onedrive_sync_cloud_favorites_status_read, status.read, status.total))
        if (status.missingTitles > 0) add(stringResource(id = R.string.onedrive_sync_cloud_favorites_status_titles, status.missingTitles))
        add(
            when {
                workInfo.nextRunAt != null -> stringResource(
                    id = R.string.onedrive_sync_cloud_favorites_status_waiting_at,
                    DateFormat.getTimeFormat(context).format(Date(workInfo.nextRunAt)),
                )
                workInfo.waiting -> stringResource(id = R.string.onedrive_sync_cloud_favorites_status_waiting)
                else -> stringResource(id = R.string.onedrive_sync_cloud_favorites_status_stopped)
            },
        )
        error?.let { add(stringResource(id = R.string.onedrive_sync_cloud_favorites_status_error, it)) }
        add(stringResource(id = R.string.onedrive_sync_cloud_favorites_status_tap))
    }.joinToString("\n")
}

@Composable
private fun OneDriveSyncSubtitle(title: Int) {
    Text(
        text = stringResource(id = title),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun OneDriveSyncNote() {
    Text(
        text = stringResource(id = R.string.onedrive_sync_note),
        modifier = Modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}
