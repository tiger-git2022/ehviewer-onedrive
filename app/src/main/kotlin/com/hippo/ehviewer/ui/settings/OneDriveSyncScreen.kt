package com.hippo.ehviewer.ui.settings

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.sync.FavoritesIndexSync
import com.hippo.ehviewer.sync.OneDriveClient
import com.hippo.ehviewer.sync.OneDriveSync
import com.hippo.ehviewer.sync.OneDriveSyncState
import com.hippo.ehviewer.sync.SyncTrigger
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.OneDriveDevicesScreenDestination
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.ui.tools.awaitInputText
import com.hippo.ehviewer.util.ReadableTime
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.zhanghai.compose.preference.DropdownListPreference
import moe.tarsin.navigate
import moe.tarsin.snackbar
import moe.tarsin.string

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
                    val running by OneDriveSync.running.collectAsState()
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
                                val result = OneDriveSync.sync(SyncTrigger.MANUAL)
                                snackbar(OneDriveSync.summaryOf(result))
                            }
                        }
                    }
                    SyncProgressBar(fraction = progress?.fraction?.takeIf { running })

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
                        summary = stringResource(id = R.string.onedrive_sync_category_settings_summary),
                        state = OneDriveSyncState.syncSettings.asMutableState(),
                    )
                    SwitchPreference(
                        title = stringResource(id = R.string.onedrive_sync_category_favorites),
                        state = OneDriveSyncState.syncFavorites.asMutableState(),
                    )
                    SwitchPreference(
                        title = stringResource(id = R.string.onedrive_sync_category_history_progress),
                        state = OneDriveSyncState.syncHistory.asMutableState(),
                    )
                    SwitchPreference(
                        title = stringResource(id = R.string.onedrive_sync_category_quick_search_filters),
                        state = OneDriveSyncState.syncQuickSearch.asMutableState(),
                    )
                    SwitchPreference(
                        title = stringResource(id = R.string.onedrive_sync_category_suggestions),
                        state = OneDriveSyncState.syncSuggestions.asMutableState(),
                    )
                    SwitchPreference(
                        title = stringResource(id = R.string.onedrive_sync_upload_downloads),
                        summary = stringResource(id = R.string.onedrive_sync_upload_downloads_summary),
                        state = OneDriveSyncState.uploadDownloads.asMutableState(),
                    )
                    val exportIndex = OneDriveSyncState.exportFavoritesIndex.asMutableState()
                    SwitchPreference(
                        title = stringResource(id = R.string.onedrive_sync_favorites_index),
                        summary = stringResource(id = R.string.onedrive_sync_favorites_index_summary),
                        state = exportIndex,
                    )
                    AnimatedVisibility(visible = exportIndex.value) {
                        val refreshing by FavoritesIndexSync.refreshing.collectAsState()
                        val refreshProgress by FavoritesIndexSync.progress.collectAsState()
                        Column {
                            Preference(
                                title = stringResource(id = R.string.onedrive_sync_favorites_index_refresh),
                                summary = when {
                                    !refreshing -> stringResource(id = R.string.onedrive_sync_favorites_index_refresh_summary)
                                    else -> refreshProgress?.let { stepProgressText(it) } ?: stringResource(id = R.string.onedrive_sync_favorites_index_refreshing)
                                },
                            ) {
                                if (!refreshing) {
                                    launch {
                                        if (!Settings.hasSignedIn.value) {
                                            snackbar(string(R.string.onedrive_sync_favorites_index_not_signed_in))
                                            return@launch
                                        }
                                        val message = runCatching {
                                            FavoritesIndexSync.refreshCloudFavorites()
                                            FavoritesIndexSync.lookUpAllJapaneseTitles()
                                        }.fold(
                                            onSuccess = { OneDriveSync.summaryOf(OneDriveSync.sync(SyncTrigger.MANUAL)) },
                                            onFailure = { e -> e.message ?: e.toString() },
                                        )
                                        snackbar(message)
                                    }
                                }
                            }
                            SyncProgressBar(fraction = refreshProgress?.fraction?.takeIf { refreshing })
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
