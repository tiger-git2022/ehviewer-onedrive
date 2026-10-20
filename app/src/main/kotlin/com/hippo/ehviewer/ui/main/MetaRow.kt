package com.hippo.ehviewer.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fork: the rating, uploader and details line of a gallery list item. [end] (favorite, language, pages) takes what it
 * needs but always leaves the uploader up to [uploaderMin], so the uploader never shrinks to a lone "…"; [end] gives way
 * first and should let its least important part (the favorite folder name) shrink.
 */
@Composable
fun MetaRow(
    start: @Composable () -> Unit,
    uploader: @Composable () -> Unit,
    end: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 4.dp,
    uploaderMin: Dp = 56.dp,
) = Layout(
    contents = listOf(start, uploader, end),
    modifier = modifier,
) { (startMeasurables, uploaderMeasurables, endMeasurables), constraints ->
    val gap = spacing.roundToPx()
    val maxWidth = constraints.maxWidth
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val startPlaceables = startMeasurables.map { it.measure(loose) }
    val startWidth = startPlaceables.sumOf { it.width }
    val uploaderMeasurable = uploaderMeasurables.firstOrNull()
    // Keep room for the whole uploader name when it is short, else for uploaderMin of it
    val uploaderNeed = uploaderMeasurable?.maxIntrinsicWidth(constraints.maxHeight)?.coerceAtMost(uploaderMin.roundToPx()) ?: 0
    val endMax = if (maxWidth == Constraints.Infinity) {
        Constraints.Infinity
    } else {
        (maxWidth - startWidth - uploaderNeed - 2 * gap).coerceAtLeast(0)
    }
    val endPlaceables = endMeasurables.map { it.measure(loose.copy(maxWidth = endMax)) }
    val endWidth = endPlaceables.sumOf { it.width }
    val uploaderWidth = if (maxWidth == Constraints.Infinity) {
        uploaderMeasurable?.maxIntrinsicWidth(constraints.maxHeight) ?: 0
    } else {
        (maxWidth - startWidth - endWidth - 2 * gap).coerceAtLeast(0)
    }
    val uploaderPlaceables = uploaderMeasurables.map { it.measure(loose.copy(minWidth = uploaderWidth, maxWidth = uploaderWidth)) }
    val all = startPlaceables + uploaderPlaceables + endPlaceables
    val height = (all.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
    val width = if (maxWidth == Constraints.Infinity) startWidth + uploaderWidth + endWidth + 2 * gap else maxWidth
    layout(width, height) {
        var x = 0
        fun place(placeables: List<Placeable>) {
            placeables.forEach {
                it.placeRelative(x, Alignment.CenterVertically.align(it.height, height))
                x += it.width
            }
        }
        place(startPlaceables)
        x += gap
        place(uploaderPlaceables)
        x = width - endWidth
        place(endPlaceables)
    }
}
