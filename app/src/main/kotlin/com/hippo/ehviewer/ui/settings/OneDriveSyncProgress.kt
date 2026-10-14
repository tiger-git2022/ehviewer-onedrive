package com.hippo.ehviewer.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
    val animated by animateFloatAsState(targetValue = fraction, label = "syncProgress")
    LinearProgressIndicator(
        progress = { animated },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
