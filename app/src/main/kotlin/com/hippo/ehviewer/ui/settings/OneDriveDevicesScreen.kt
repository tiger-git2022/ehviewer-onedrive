package com.hippo.ehviewer.ui.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.sync.DeviceDownloads
import com.hippo.ehviewer.sync.OneDriveSync
import com.hippo.ehviewer.sync.OneDriveSyncState
import com.hippo.ehviewer.sync.RemoteDevice
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.OneDriveDeviceDownloadsScreenDestination
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.util.ReadableTime
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import moe.tarsin.navigate
import moe.tarsin.snackbar
import moe.tarsin.string

/** Download lists other devices uploaded to OneDrive. */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.OneDriveDevicesScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var devices by remember { mutableStateOf<List<RemoteDevice>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        devices = null
        error = null
        if (!OneDriveSyncState.isSignedIn) {
            error = string(R.string.onedrive_sync_not_signed_in)
            return@LaunchedEffect
        }
        runCatching { OneDriveSync.listDevices() }
            .onSuccess { devices = it }
            .onFailure { error = it.message ?: it.toString() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.onedrive_sync_other_devices)) },
                navigationIcon = { NavigationIcon() },
                actions = {
                    IconButton(onClick = { reload++ }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = stringResource(id = R.string.refresh))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        val list = devices
        when {
            error != null -> CenteredText(text = error.orEmpty(), modifier = Modifier.padding(paddingValues))
            list == null -> Box(modifier = Modifier.padding(paddingValues).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> CenteredText(text = stringResource(id = R.string.onedrive_sync_no_devices), modifier = Modifier.padding(paddingValues))
            else -> LazyColumn(
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = paddingValues,
            ) {
                items(list, key = { it.id }) { device ->
                    ListItem(
                        headlineContent = { Text(text = device.name) },
                        supportingContent = {
                            Text(
                                text = stringResource(
                                    id = R.string.onedrive_sync_device_summary,
                                    device.downloadCount,
                                    ReadableTime.getTimeAgo(device.updatedAt),
                                ),
                            )
                        },
                        trailingContent = if (device.subscribed) {
                            { Text(text = stringResource(id = R.string.onedrive_sync_subscribed)) }
                        } else {
                            null
                        },
                        modifier = Modifier.clickable { navigate(OneDriveDeviceDownloadsScreenDestination(device.id)) },
                    )
                }
            }
        }
    }
}

private sealed interface DeviceRow {
    data class Header(val label: String?, val gids: List<Long>) : DeviceRow
    data class Item(val gid: Long, val title: String, val finished: Boolean, val local: Boolean) : DeviceRow
}

/** One device's download list: pick downloads to import to this device, subscribe, or remove it from OneDrive. */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.OneDriveDeviceDownloadsScreen(deviceId: String, navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var device by remember { mutableStateOf<DeviceDownloads?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var localGids by remember { mutableStateOf(emptySet<Long>()) }
    var subscribed by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val selected: SnapshotStateList<Long> = remember { emptyList<Long>().toMutableStateList() }
    LaunchedEffect(deviceId) {
        runCatching { OneDriveSync.loadDevice(deviceId) }
            .onSuccess { remote ->
                if (remote == null) {
                    error = string(R.string.onedrive_sync_device_gone)
                } else {
                    localGids = remote.downloads.mapNotNullTo(HashSet()) { d -> d.gid.takeIf { DownloadManager.containDownloadInfo(it) } }
                    subscribed = deviceId in OneDriveSyncState.readSubscriptions()
                    device = remote
                }
            }
            .onFailure { error = it.message ?: it.toString() }
    }
    val remote = device
    val defaultLabel = stringResource(id = R.string.default_download_label_name)
    val rows = remember(remote, localGids) {
        if (remote == null) return@remember emptyList<DeviceRow>()
        val titles = remote.galleries.associate { it.gid to (it.title ?: it.titleJpn ?: it.gid.toString()) }
        val order = remote.labels.sortedBy { it.position }.map { it.label }
        remote.downloads.groupBy { it.label }
            .toList()
            .sortedBy { (label, _) -> if (label == null) -1 else order.indexOf(label).let { if (it < 0) Int.MAX_VALUE else it } }
            .flatMap { (label, downloads) ->
                val sorted = downloads.sortedByDescending { it.time }
                listOf<DeviceRow>(DeviceRow.Header(label, sorted.map { it.gid }.filterNot { it in localGids })) +
                    sorted.map { DeviceRow.Item(it.gid, titles[it.gid] ?: it.gid.toString(), it.finished, it.gid in localGids) }
            }
    }
    val importable = remember(remote, localGids) { remote?.downloads?.map { it.gid }?.filterNot { it in localGids }.orEmpty() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = remote?.deviceName?.ifBlank { null } ?: stringResource(id = R.string.onedrive_sync_other_devices)) },
                navigationIcon = { NavigationIcon() },
                actions = {
                    if (remote != null) {
                        IconButton(onClick = {
                            if (selected.size == importable.size) {
                                selected.clear()
                            } else {
                                selected.clear()
                                selected.addAll(importable)
                            }
                        }) {
                            Icon(imageVector = Icons.Default.DoneAll, contentDescription = stringResource(id = R.string.onedrive_sync_select_all))
                        }
                        IconButton(onClick = {
                            launch {
                                awaitConfirmationOrCancel(confirmText = R.string.delete) {
                                    Text(text = stringResource(id = R.string.onedrive_sync_remove_device_confirm, remote.deviceName))
                                }
                                runCatching { OneDriveSync.removeDevice(deviceId) }
                                    .onSuccess {
                                        snackbar(string(R.string.onedrive_sync_device_removed))
                                        navigator.popBackStack()
                                    }
                                    .onFailure { snackbar(it.message ?: it.toString()) }
                            }
                        }) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(id = R.string.onedrive_sync_remove_device))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            if (remote != null) {
                Button(
                    onClick = {
                        importing = true
                        launch {
                            runCatching { OneDriveSync.importDownloads(remote, selected.toList()) }
                                .onSuccess { result ->
                                    selected.clear()
                                    localGids = withIOContext { remote.downloads.mapNotNullTo(HashSet()) { d -> d.gid.takeIf { DownloadManager.containDownloadInfo(it) } } }
                                    snackbar(string(R.string.onedrive_sync_import_result, result.imported, result.skipped))
                                }
                                .onFailure { snackbar(it.message ?: it.toString()) }
                            importing = false
                        }
                    },
                    enabled = selected.isNotEmpty() && !importing,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(text = stringResource(id = R.string.onedrive_sync_import, selected.size))
                }
            }
        },
    ) { paddingValues ->
        when {
            error != null -> CenteredText(text = error.orEmpty(), modifier = Modifier.padding(paddingValues))
            remote == null -> Box(modifier = Modifier.padding(paddingValues).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> LazyColumn(
                modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = paddingValues,
            ) {
                item(key = "subscribe") {
                    ListItem(
                        headlineContent = { Text(text = stringResource(id = R.string.onedrive_sync_subscribe)) },
                        supportingContent = { Text(text = stringResource(id = R.string.onedrive_sync_subscribe_summary)) },
                        trailingContent = {
                            Switch(
                                checked = subscribed,
                                onCheckedChange = { checked ->
                                    subscribed = checked
                                    launch { OneDriveSync.setSubscribed(remote, checked) }
                                },
                            )
                        },
                    )
                }
                items(
                    rows,
                    key = { row ->
                        when (row) {
                            is DeviceRow.Header -> "label:${row.label}"
                            is DeviceRow.Item -> row.gid
                        }
                    },
                ) { row ->
                    when (row) {
                        is DeviceRow.Header -> ListItem(
                            headlineContent = {
                                Text(
                                    text = row.label ?: defaultLabel,
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            },
                            trailingContent = if (row.gids.isNotEmpty()) {
                                {
                                    TextButton(onClick = {
                                        if (selected.containsAll(row.gids)) {
                                            selected.removeAll(row.gids)
                                        } else {
                                            selected.addAll(row.gids.filterNot { it in selected })
                                        }
                                    }) {
                                        Text(text = stringResource(id = R.string.onedrive_sync_select_label))
                                    }
                                }
                            } else {
                                null
                            },
                        )
                        is DeviceRow.Item -> ListItem(
                            headlineContent = { Text(text = row.title, maxLines = 2) },
                            supportingContent = when {
                                row.local -> {
                                    { Text(text = stringResource(id = R.string.onedrive_sync_already_local)) }
                                }
                                !row.finished -> {
                                    { Text(text = stringResource(id = R.string.onedrive_sync_unfinished)) }
                                }
                                else -> null
                            },
                            leadingContent = {
                                Checkbox(
                                    checked = row.local || row.gid in selected,
                                    onCheckedChange = null,
                                    enabled = !row.local,
                                )
                            },
                            modifier = Modifier.clickable(enabled = !row.local) {
                                if (row.gid in selected) selected.remove(row.gid) else selected.add(row.gid)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredText(text: String, modifier: Modifier = Modifier) = Box(modifier = modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
}
