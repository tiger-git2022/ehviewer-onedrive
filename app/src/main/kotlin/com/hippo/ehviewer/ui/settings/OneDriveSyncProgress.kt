package com.hippo.ehviewer.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
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
 * A wavy bar for a running sync, endless without [fraction]. Its waves move for as long as it shows, also while the
 * progress stands still (a large step, a slow network): the Material defaults flatten them near the start and the end.
 * At 0 a filled part has no length to wave, so the endless bar shows until the first step is done.
 */
@Composable
fun SyncLinearProgress(fraction: Float?, modifier: Modifier = Modifier) {
    if (fraction != null && fraction > 0f) {
        val animated by animateFloatAsState(targetValue = fraction, animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec, label = "syncProgress")
        LinearWavyProgressIndicator(progress = { animated }, modifier = modifier, amplitude = { 1f })
    } else {
        LinearWavyProgressIndicator(modifier = modifier)
    }
}

/** A small waving ring for a running sync, filled with [fraction], turning without it or at 0. */
@Composable
fun SyncRingProgress(fraction: Float?, color: Color, modifier: Modifier = Modifier) {
    val stroke = Stroke(width = with(LocalDensity.current) { 2.5.dp.toPx() }, cap = StrokeCap.Round)
    if (fraction != null && fraction > 0f) {
        val animated by animateFloatAsState(targetValue = fraction, animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec, label = "syncRing")
        CircularWavyProgressIndicator(
            progress = { animated },
            modifier = modifier,
            color = color,
            trackColor = color.copy(alpha = 0.25f),
            stroke = stroke,
            trackStroke = stroke,
            amplitude = { RING_AMPLITUDE },
            wavelength = RingWavelength,
            waveSpeed = RingWavelength,
        )
    } else {
        CircularWavyProgressIndicator(
            modifier = modifier,
            color = color,
            trackColor = Color.Transparent,
            stroke = stroke,
            trackStroke = stroke,
            amplitude = RING_AMPLITUDE,
            wavelength = RingWavelength,
            waveSpeed = RingWavelength,
        )
    }
}

private const val RING_AMPLITUDE = 0.6f
private val RingWavelength = 7.dp
