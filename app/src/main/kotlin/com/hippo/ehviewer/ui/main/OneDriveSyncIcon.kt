package com.hippo.ehviewer.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.sync.OneDriveClient
import com.hippo.ehviewer.sync.OneDriveSync
import com.hippo.ehviewer.sync.OneDriveSyncState
import com.hippo.ehviewer.sync.SyncCategory
import com.hippo.ehviewer.sync.SyncTrigger
import com.hippo.ehviewer.ui.settings.syncProgressText
import kotlinx.coroutines.CoroutineScope
import moe.tarsin.snackbar

/** The download list of this device changed and is uploaded by the next sync. */
@Composable
private fun downloadsChanged(): Boolean {
    val dirty by OneDriveSyncState.downloadsDirty.collectAsState()
    val upload by OneDriveSyncState.uploadDownloads.collectAsState()
    return dirty && upload
}

/** What [OneDriveSyncIcon] shows, the first that applies. */
private enum class SyncIconState { RUNNING, PENDING, FAILED, SUCCEEDED, NOT_SYNCED }

/**
 * State of the OneDrive sync in a toolbar (fork-only feature): not synced yet, changes waiting for a sync, syncing
 * (a ring filling up with the progress), synced, or failed. A tap syncs now, or shows the step while syncing.
 * Nothing is shown without a OneDrive account.
 */
@Composable
context(_: CoroutineScope, _: SnackbarHostState)
fun OneDriveSyncIcon(onSynced: () -> Unit = {}) {
    if (!OneDriveClient.isSupported) return
    val account by OneDriveSyncState.accountId.collectAsState()
    if (account == null) return
    val running by OneDriveSync.running.collectAsState()
    val progress by OneDriveSync.progress.collectAsState()
    val changesPending by OneDriveSync.changesPending.collectAsState()
    val lastSyncTime by OneDriveSyncState.lastSyncTime.collectAsState()
    val lastSyncFailed by OneDriveSyncState.lastSyncFailed.collectAsState()
    // Local changes not synced yet, also when no sync is scheduled for them (sync on change turned off)
    val dirty = SyncCategory.entries.map { category ->
        val changed by OneDriveSyncState.dirty(category).collectAsState()
        changed && OneDriveSyncState.isEnabled(category)
    }.any { it } || downloadsChanged()
    val state = when {
        running -> SyncIconState.RUNNING
        changesPending -> SyncIconState.PENDING
        lastSyncFailed && lastSyncTime > 0 -> SyncIconState.FAILED
        // Before the first sync everything is to upload: "not synced" says it better
        dirty && lastSyncTime > 0 -> SyncIconState.PENDING
        lastSyncTime > 0 -> SyncIconState.SUCCEEDED
        else -> SyncIconState.NOT_SYNCED
    }
    val running0 = progress?.takeIf { running }
    val description = when (state) {
        SyncIconState.RUNNING -> running0?.let { syncProgressText(it) } ?: stringResource(id = R.string.onedrive_sync_running)
        SyncIconState.PENDING -> stringResource(id = R.string.onedrive_sync_status_pending)
        SyncIconState.FAILED -> stringResource(id = R.string.onedrive_sync_status_failed)
        SyncIconState.SUCCEEDED -> stringResource(id = R.string.onedrive_sync_status_succeeded)
        SyncIconState.NOT_SYNCED -> stringResource(id = R.string.onedrive_sync_never)
    }
    IconButton(
        onClick = {
            launch {
                if (running) {
                    snackbar(description)
                } else {
                    val result = OneDriveSync.sync(SyncTrigger.MANUAL)
                    onSynced()
                    snackbar(OneDriveSync.summaryOf(result))
                }
            }
        },
        modifier = Modifier.semantics { contentDescription = description },
        shapes = IconButtonDefaults.shapes(),
    ) {
        when (state) {
            SyncIconState.RUNNING -> {
                val fraction = running0?.fraction
                val modifier = Modifier.size(22.dp)
                if (fraction != null) {
                    val animated by animateFloatAsState(targetValue = fraction, label = "syncProgress")
                    CircularProgressIndicator(progress = { animated }, modifier = modifier, color = LocalContentColor.current, strokeWidth = 2.5.dp)
                } else {
                    CircularProgressIndicator(modifier = modifier, color = LocalContentColor.current, strokeWidth = 2.5.dp)
                }
            }
            SyncIconState.PENDING -> Icon(imageVector = Icons.Default.Schedule, contentDescription = null)
            SyncIconState.FAILED -> Icon(imageVector = Icons.Default.SyncProblem, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            SyncIconState.SUCCEEDED -> Icon(imageVector = Icons.Default.DoneAll, contentDescription = null)
            SyncIconState.NOT_SYNCED -> Icon(imageVector = Icons.Default.Sync, contentDescription = null)
        }
    }
}
