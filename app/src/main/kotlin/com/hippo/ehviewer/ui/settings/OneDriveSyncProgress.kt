package com.hippo.ehviewer.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.sync.StepProgress
import com.hippo.ehviewer.sync.SyncRunProgress
import java.util.Locale

/** "Syncing 37% (3/9): Local favorites", with the items of the step when it reports them. */
@Composable
fun syncProgressText(progress: SyncRunProgress): String = stringResource(
    id = R.string.onedrive_sync_progress,
    progress.percent,
    progress.step,
    progress.steps,
    if (progress.phase != null) phaseText(progress) else stepText(progress.title, progress.done, progress.total),
)

/** Fork: "Favorites · downloading 1.2/3.4 MB (35%)". */
@Composable
private fun phaseText(progress: SyncRunProgress): String {
    val name = stringResource(id = R.string.onedrive_sync_step_phase, stringResource(id = progress.title), stringResource(id = progress.phase!!))
    val (done, total) = progress.done to progress.total
    return when {
        progress.bytes && total > 0 -> stringResource(
            id = R.string.onedrive_sync_progress_size,
            name,
            megabytes(done),
            megabytes(total),
            (done.toLong() * 100 / total).toInt().coerceIn(0, 100),
        )
        progress.bytes && done > 0 -> stringResource(id = R.string.onedrive_sync_progress_size_unknown, name, megabytes(done))
        else -> name
    }
}

private fun megabytes(bytes: Int) = String.format(Locale.ROOT, "%.1f", bytes / (1024f * 1024f))

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

/**
 * Fork: a progress line, [full] where it fits (a tablet), else in two parts: [label] cut short, [numbers] always shown
 * in full.
 */
data class SyncProgressLine(val label: String, val numbers: String = "", val full: String = label)

/** "5/5 Upload the favorites list · uploading" and "0.8/1.0 MB  98%". */
@Composable
fun syncProgressLine(progress: SyncRunProgress): SyncProgressLine {
    val title = stringResource(id = progress.title)
    val name = progress.phase?.let { stringResource(id = R.string.onedrive_sync_step_phase, title, stringResource(id = it)) } ?: title
    val (done, total) = progress.done to progress.total
    val count = when {
        progress.bytes && total > 0 -> "${megabytes(done)}/${megabytes(total)} MB"
        progress.bytes && done > 0 -> "${megabytes(done)} MB"
        !progress.bytes && total > 0 -> "$done/$total"
        else -> null
    }
    return SyncProgressLine(
        label = "${progress.step}/${progress.steps} $name",
        numbers = listOfNotNull(count, "${progress.percent}%").joinToString("  "),
        full = syncProgressText(progress),
    )
}

/** "Reading the cloud favorites" and "1200/3400  35%". */
@Composable
fun stepProgressLine(progress: StepProgress): SyncProgressLine = SyncProgressLine(
    label = stringResource(id = progress.title),
    numbers = if (progress.total > 0) "${progress.done}/${progress.total}  ${progress.percent}%" else "",
    full = stepProgressText(progress),
)

/**
 * One line of [SyncProgressLine]: the whole text where it fits, else the label on the left ending in "…" and the
 * numbers on the right.
 */
@Composable
fun SyncProgressLineText(line: SyncProgressLine, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val measurer = rememberTextMeasurer()
        val maxWidth = constraints.maxWidth
        val fits = remember(line.full, style, maxWidth) {
            measurer.measure(text = line.full, style = style, maxLines = 1, softWrap = false).size.width <= maxWidth
        }
        // Digits of one width: the numbers do not jump while they count
        val numberStyle = style.copy(fontFeatureSettings = "tnum")
        // At a large font scale the count may not fit beside the step: the percent alone then
        val numbers = remember(line.numbers, numberStyle, maxWidth) {
            val width = measurer.measure(text = line.numbers, style = numberStyle, maxLines = 1, softWrap = false).size.width
            if (width > maxWidth * 0.6f) line.numbers.substringAfterLast("  ") else line.numbers
        }
        if (fits) {
            Text(text = line.full, style = style, color = color, maxLines = 1)
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = line.label, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (numbers.isNotEmpty()) {
                    Text(text = numbers, style = numberStyle, color = color, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
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
        // Fork: a light sweeps over the done part, see ShimmerLinearProgress
        ShimmerLinearProgress(progress = { animated }, modifier = modifier)
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}

/** A small ring for a running sync, filled with [fraction], turning without it or at 0. */
@Composable
fun SyncRingProgress(fraction: Float?, color: Color, modifier: Modifier = Modifier) {
    if (fraction != null && fraction > 0f) {
        val animated by animateFloatAsState(targetValue = fraction, animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec, label = "syncRing")
        // Fork: the done arc is redrawn faintly now and then, see SoftRedrawRingProgress
        SoftRedrawRingProgress(progress = { animated }, color = color, modifier = modifier, strokeWidth = 2.5.dp)
    } else {
        CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = 2.5.dp)
    }
}
