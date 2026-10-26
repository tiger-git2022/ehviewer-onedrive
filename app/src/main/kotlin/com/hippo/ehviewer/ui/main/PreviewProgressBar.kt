package com.hippo.ehviewer.ui.main

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.FloatingGlass
import com.ehviewer.core.ui.component.GlassStyle
import com.ehviewer.core.ui.component.floatingGlass
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Fork: a floating bar at the bottom of a gallery telling how its previews load: the progress while they load, a
 * moment of "all loaded" before it fades out, or, when some failed even after retrying, how many, why (one line for
 * each reason, with the previews and the H@H servers) and a button to refresh them. Loading more after some failed,
 * it shows the progress with how many failed below it, then the failures again. Swiped away while loading, it stays
 * away until previews fail; swiped away with failures, only those are not told again. "Refresh previews" (this button or
 * the menu) shows its progress here too, without a dialog. See PreviewRefetcher.
 */

private sealed interface BarState {
    /** [failed]: the previews that failed before, told below the progress. */
    data class Loading(val loaded: Int, val total: Int, val failed: Int) : BarState
    data class AllLoaded(val total: Int) : BarState
    data class Failed(val failures: Map<Int, PreviewLoad.Failed>) : BarState
    data class Refreshing(val refresh: PreviewRefresh) : BarState
    data object Nothing : BarState
    data object Idle : BarState
}

@Composable
fun PreviewProgressBar(refetcher: PreviewRefetcher, glass: FloatingGlass?, modifier: Modifier = Modifier) {
    var nothingShown by remember { mutableStateOf(false) }
    val state by remember(refetcher) {
        derivedStateOf {
            refetcher.refresh?.let { return@derivedStateOf BarState.Refreshing(it) }
            if (nothingShown) return@derivedStateOf BarState.Nothing
            val loads = refetcher.loads.toMap()
            val pending = loads.values.count { it == PreviewLoad.Loading || it is PreviewLoad.Failed && it.retrying }
            val loaded = loads.values.count { it == PreviewLoad.Loaded }
            val allFailures = loads.entries.mapNotNull { (position, load) -> (load as? PreviewLoad.Failed)?.takeIf { !it.retrying }?.let { position to it } }.toMap()
            // Failures swiped away are not told again, see PreviewRefetcher.dismissedFailures
            val failures = allFailures - refetcher.dismissedFailures
            when {
                pending > 0 -> BarState.Loading(loaded, loaded + pending, failures.size)
                failures.isNotEmpty() -> BarState.Failed(failures)
                allFailures.isEmpty() && loaded > 0 -> BarState.AllLoaded(loaded)
                else -> BarState.Idle
            }
        }
    }
    // "Refresh previews" with nothing that failed: says so for a moment
    LaunchedEffect(refetcher.nothingToRefresh) {
        if (refetcher.nothingToRefresh > 0) {
            nothingShown = true
            delay(1500)
            nothingShown = false
        }
    }
    // Loading shows only when it takes a moment (previews from the cache load at once), "all loaded" only after the
    // bar showed something, for half a second
    var shown by remember { mutableStateOf(false) }
    val kind = state::class
    LaunchedEffect(kind) {
        when (state) {
            is BarState.Loading -> {
                delay(400)
                shown = true
            }
            is BarState.AllLoaded -> {
                delay(500)
                shown = false
            }
            is BarState.Idle -> shown = false
            else -> shown = true
        }
    }
    // The last state shown, kept while the bar fades out
    val last = remember { arrayOfNulls<BarState>(1) }
    // Swiped away while loading, it comes back for failures, which is what you need to know
    LaunchedEffect(state is BarState.Failed) {
        if (state is BarState.Failed) refetcher.progressDismissed = false
    }
    val hidden = refetcher.progressDismissed && state !is BarState.Failed
    val visible = shown && state != BarState.Idle && !hidden
    if (visible) last[0] = state
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut(),
    ) {
        val shape = RoundedCornerShape(22.dp)
        val color = MaterialTheme.colorScheme.surfaceContainerHigh
        // Swiped to either side, it goes away
        val offset = remember { Animatable(0f) }
        var barSize by remember { mutableStateOf(IntSize.Zero) }
        val scope = rememberCoroutineScope()
        Box(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .graphicsLayer {
                    translationX = offset.value
                    if (barSize.width > 0) alpha = 1f - (abs(offset.value) / barSize.width).coerceIn(0f, 1f)
                }
                .draggable(
                    state = rememberDraggableState { delta -> scope.launch { offset.snapTo(offset.value + delta) } },
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val width = barSize.width.toFloat()
                        if (abs(offset.value) > width / 3 || abs(velocity) > 1500f) {
                            val target = if (offset.value + velocity / 4 >= 0) width else -width
                            offset.animateTo(target)
                            when (val swiped = last[0]) {
                                // Only these failures: what loads later still shows, and new failures too
                                is BarState.Failed -> refetcher.dismissedFailures += swiped.failures.keys
                                else -> refetcher.progressDismissed = true
                            }
                        } else {
                            offset.animateTo(0f)
                        }
                    },
                )
                .onSizeChanged { barSize = it }
                .then(
                    if (glass != null) {
                        Modifier.floatingGlass(glass, shape, color.copy(alpha = GLASS_TINT), GlassStyle.Panel)
                    } else {
                        Modifier.shadow(6.dp, shape).background(color, shape)
                    },
                )
                .animateContentSize()
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            BarContent(last[0] ?: state, onRefresh = { refetcher.refreshFailed(it) })
        }
    }
}

@Composable
private fun BarContent(state: BarState, onRefresh: (Context) -> Unit) {
    val context = LocalContext.current
    when (state) {
        is BarState.Loading -> ProgressRow(
            title = stringResource(R.string.preview_loading, state.loaded, state.total),
            subtitle = if (state.failed > 0) stringResource(R.string.preview_loading_failed_too, state.failed) else null,
            subtitleColor = MaterialTheme.colorScheme.error,
            progress = if (state.total > 0) state.loaded.toFloat() / state.total else 0f,
        )
        is BarState.Refreshing -> with(state.refresh) {
            ProgressRow(
                title = stringResource(R.string.preview_refreshing, page + 1, pages),
                subtitle = stringResource(if (downloading) R.string.preview_refresh_images else R.string.preview_refresh_links),
                // Half a page for the links, half for the images
                progress = (page + if (downloading) 0.5f else 0f) / pages,
            )
        }
        is BarState.AllLoaded -> IconRow(Icons.Default.Check, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary) {
            Text(text = stringResource(R.string.preview_all_loaded, state.total), style = MaterialTheme.typography.bodyMedium)
        }
        BarState.Nothing -> IconRow(Icons.Default.Check, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary) {
            Text(text = stringResource(R.string.preview_refresh_nothing), style = MaterialTheme.typography.bodyMedium)
        }
        is BarState.Failed -> Column {
            IconRow(Icons.Default.PriorityHigh, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) {
                Text(
                    text = stringResource(R.string.preview_failure_count, state.failures.size),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = { onRefresh(context) }, shapes = ButtonDefaults.shapes()) {
                    Text(text = stringResource(R.string.preview_refresh_again))
                }
            }
            // One line for each reason, written out in full (wrapping rather than cut off), the servers below it
            Column(
                modifier = Modifier.padding(start = IconSpace, top = 4.dp).heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.failures.entries.groupBy({ it.value.reason }, { it }).entries.sortedByDescending { it.value.size }.forEach { (reason, list) ->
                    Column {
                        val positions = positionRanges(list.map { it.key })
                        Text(
                            text = stringResource(R.string.preview_failure_group, reason, list.size, positions),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val hosts = list.mapNotNull { it.value.host }.distinct()
                        if (hosts.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.preview_failure_servers, hosts.joinToString()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        BarState.Idle -> Unit
    }
}

@Composable
private fun ProgressRow(title: String, progress: Float, subtitle: String? = null, subtitleColor: Color = Color.Unspecified) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
        Spacer(modifier = Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant },
                )
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 4.dp),
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun IconRow(icon: ImageVector, container: Color, content: Color, rest: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(20.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        }
        Spacer(modifier = Modifier.size(14.dp))
        rest()
    }
}

// The previews by their numbers, runs written as ranges: "1–20、41–60"
private fun positionRanges(positions: List<Int>): String {
    val sorted = positions.map { it + 1 }.sorted()
    val ranges = mutableListOf<IntRange>()
    sorted.forEach { n ->
        val lastRange = ranges.lastOrNull()
        if (lastRange != null && n == lastRange.last + 1) ranges[ranges.lastIndex] = lastRange.first..n else ranges += n..n
    }
    return ranges.joinToString("、") { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" }
}

// The icon and the space after it, where the lines below line up
private val IconSpace = 34.dp

// Readable over busy previews: more than the floating buttons
private const val GLASS_TINT = 0.5f
