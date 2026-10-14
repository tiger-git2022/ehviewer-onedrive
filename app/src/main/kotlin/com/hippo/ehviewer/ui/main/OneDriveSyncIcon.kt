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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.sync.CloudFavoritesWorkInfo
import com.hippo.ehviewer.sync.FavoritesIndexSync
import com.hippo.ehviewer.sync.OneDriveClient
import com.hippo.ehviewer.sync.OneDriveSync
import com.hippo.ehviewer.sync.OneDriveSyncState
import com.hippo.ehviewer.sync.SyncCategory
import com.hippo.ehviewer.sync.SyncTrigger
import com.hippo.ehviewer.sync.cloudFavoritesWorkInfo
import com.hippo.ehviewer.sync.resumeCloudFavoritesWork
import com.hippo.ehviewer.ui.settings.stepProgressText
import com.hippo.ehviewer.ui.settings.syncProgressText
import kotlinx.coroutines.CoroutineScope
import moe.tarsin.snackbar
import moe.tarsin.string
import splitties.init.appCtx

/** The shared favorites changed, the next sync uploads (or deletes) them. */
@Composable
private fun favoritesIndexChanged(): Boolean {
    val indexDirty by OneDriveSyncState.favoritesIndexDirty.collectAsState()
    val export by OneDriveSyncState.exportFavoritesIndex.collectAsState()
    // Sharing turned on after the index was deleted, or off while it is still on OneDrive
    val indexRemoved by OneDriveSyncState.favoritesIndexRemoved.collectAsState()
    return indexDirty && export || export == indexRemoved
}

/** What [OneDriveSyncIcon] shows, the first that applies. */
private enum class SyncIconState { RUNNING, PENDING, FAILED, SUCCEEDED, NOT_SYNCED }

/**
 * State of the OneDrive sync in a toolbar (fork-only feature): not synced yet, changes waiting for a sync, syncing
 * (a ring filling up with the progress, also while the cloud favorites are read in the background), synced, or
 * failed. A tap syncs now, or shows the step while syncing. Changes only a manual sync takes (settings, quick
 * searches, download lists) do not count as waiting. Nothing is shown without a OneDrive account.
 */
@Composable
context(_: CoroutineScope, _: SnackbarHostState)
fun OneDriveSyncIcon(onSynced: () -> Unit = {}) {
    if (!OneDriveClient.isSupported) return
    val account by OneDriveSyncState.accountId.collectAsState()
    if (account == null) return
    val syncing by OneDriveSync.running.collectAsState()
    val syncProgress by OneDriveSync.progress.collectAsState()
    val working by FavoritesIndexSync.working.collectAsState()
    val workProgress by FavoritesIndexSync.workProgress.collectAsState()
    // Queued to go on later: the shared favorites it changed are not "waiting" for the user, it uploads them itself
    val workInfo by remember { cloudFavoritesWorkInfo(appCtx) }.collectAsState(initial = CloudFavoritesWorkInfo())
    val export by OneDriveSyncState.exportFavoritesIndex.collectAsState()
    val workQueued = export && workInfo.active
    val running = syncing || working || workQueued
    val changesPending by OneDriveSync.changesPending.collectAsState()
    val lastSyncTime by OneDriveSyncState.lastSyncTime.collectAsState()
    val lastSyncFailed by OneDriveSyncState.lastSyncFailed.collectAsState()
    // Local changes not synced yet, also when no sync is scheduled for them (sync on change turned off)
    val dirty = SyncCategory.entries.filter { it.automatic }.map { category ->
        val changed by OneDriveSyncState.dirty(category).collectAsState()
        changed && OneDriveSyncState.isEnabled(category)
    }.any { it } || favoritesIndexChanged()
    val state = when {
        running -> SyncIconState.RUNNING
        changesPending -> SyncIconState.PENDING
        lastSyncFailed && lastSyncTime > 0 -> SyncIconState.FAILED
        // Before the first sync everything is to upload: "not synced" says it better
        dirty && lastSyncTime > 0 -> SyncIconState.PENDING
        lastSyncTime > 0 -> SyncIconState.SUCCEEDED
        else -> SyncIconState.NOT_SYNCED
    }
    val runningText = when {
        syncing -> syncProgress?.let { syncProgressText(it) }
        working -> workProgress?.let { stepProgressText(it) }
        else -> stringResource(id = R.string.onedrive_sync_cloud_favorites_work_queued)
    }
    val runningFraction = when {
        syncing -> syncProgress?.fraction
        working -> workProgress?.fraction
        else -> null
    }
    val description = when (state) {
        SyncIconState.RUNNING -> runningText ?: stringResource(id = R.string.onedrive_sync_running)
        SyncIconState.PENDING -> stringResource(id = R.string.onedrive_sync_status_pending)
        SyncIconState.FAILED -> stringResource(id = R.string.onedrive_sync_status_failed)
        SyncIconState.SUCCEEDED -> stringResource(id = R.string.onedrive_sync_status_succeeded)
        SyncIconState.NOT_SYNCED -> stringResource(id = R.string.onedrive_sync_never)
    }
    IconButton(
        onClick = {
            launch {
                when {
                    syncing || working -> snackbar(description)
                    // Waiting to go on: it goes on now, in the foreground
                    workQueued -> {
                        resumeCloudFavoritesWork(appCtx)
                        snackbar(string(R.string.onedrive_sync_cloud_favorites_resumed))
                    }
                    else -> {
                        val result = OneDriveSync.sync(SyncTrigger.MANUAL)
                        onSynced()
                        snackbar(OneDriveSync.summaryOf(result))
                    }
                }
            }
        },
        modifier = Modifier.semantics { contentDescription = description },
        shapes = IconButtonDefaults.shapes(),
    ) {
        when (state) {
            SyncIconState.RUNNING -> {
                val fraction = runningFraction
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
