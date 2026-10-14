package com.hippo.ehviewer.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.sync.StepProgress
import com.hippo.ehviewer.sync.SyncRunProgress

/** "Syncing 37% (3/9): Local favorites", with the items of the step when it reports them. */
@Composable
fun syncProgressText(progress: SyncRunProgress): String = stringResource(
    id = R.string.onedrive_sync_progress,
    progress.percent,
    progress.step,
    progress.steps,
    stepText(progress.title, progress.done, progress.total),
)

/** "Reading the cloud favorites 1200/3400 (35%)". */
@Composable
fun stepProgressText(progress: StepProgress): String = stepText(progress.title, progress.done, progress.total)

@Composable
private fun stepText(@StringRes title: Int, done: Int, total: Int): String {
    val name = stringResource(id = title)
    if (total <= 0) return name
    val percent = (done.toLong() * 100 / total).toInt().coerceIn(0, 100)
    return stringResource(id = R.string.onedrive_sync_progress_count, name, done, total, percent)
}

/** A bar below a preference, shown while [fraction] is not null. */
@Composable
fun SyncProgressBar(fraction: Float?) {
    if (fraction == null) return
    SyncLinearProgress(
        fraction = fraction,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * A bar for a running sync, endless without [fraction] or at 0: a filled part of no length shows nothing moving, a
 * sync waiting on the network should still read as running.
 */
@Composable
fun SyncLinearProgress(fraction: Float?, modifier: Modifier = Modifier) {
    if (fraction != null && fraction > 0f) {
        val animated by animateFloatAsState(targetValue = fraction, animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec, label = "syncProgress")
        LinearProgressIndicator(progress = { animated }, modifier = modifier)
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}

/** A small ring for a running sync, filled with [fraction], turning without it or at 0. */
@Composable
fun SyncRingProgress(fraction: Float?, color: Color, modifier: Modifier = Modifier) {
    if (fraction != null && fraction > 0f) {
        val animated by animateFloatAsState(targetValue = fraction, animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec, label = "syncRing")
        CircularProgressIndicator(progress = { animated }, modifier = modifier, color = color, strokeWidth = 2.5.dp)
    } else {
        CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = 2.5.dp)
    }
}
