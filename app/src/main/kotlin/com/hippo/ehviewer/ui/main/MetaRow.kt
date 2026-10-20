package com.hippo.ehviewer.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fork: the rating, uploader and details line of a gallery list item, laid out as
 * [start] [uploader] … [favorite] [details].
 *
 * [start] and [details] (download mark, language, pages) always keep their full width. The uploader then keeps up to
 * [uploaderMin] (its whole name when shorter), so it never shrinks to a lone "…"; [favorite] (heart and folder name)
 * gets what is left and gives way first, and the uploader takes the rest.
 */
@Composable
fun MetaRow(
    start: @Composable () -> Unit,
    uploader: @Composable () -> Unit,
    favorite: @Composable () -> Unit,
    details: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 4.dp,
    uploaderMin: Dp = 56.dp,
) = Layout(
    contents = listOf(start, uploader, favorite, details),
    modifier = modifier,
) { (startMeasurables, uploaderMeasurables, favoriteMeasurables, detailsMeasurables), constraints ->
    val gap = spacing.roundToPx()
    val maxWidth = constraints.maxWidth
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    fun List<Placeable>.width() = sumOf { it.width }

    val startPlaceables = startMeasurables.map { it.measure(loose) }
    var left = (maxWidth - startPlaceables.width() - gap).coerceAtLeast(0)
    val detailsPlaceables = detailsMeasurables.map { it.measure(loose.copy(maxWidth = left)) }
    val detailsWidth = detailsPlaceables.width()
    if (detailsWidth > 0) left = (left - detailsWidth - gap).coerceAtLeast(0)
    // Keep room for the whole uploader name when it is short, else for uploaderMin of it
    val uploaderNeed = (uploaderMeasurables.firstOrNull()?.maxIntrinsicWidth(constraints.maxHeight) ?: 0)
        .coerceAtMost(uploaderMin.roundToPx())
        .coerceAtMost(left)
    val favoriteMax = (left - uploaderNeed - gap).coerceAtLeast(0)
    val favoritePlaceables = favoriteMeasurables.map { it.measure(loose.copy(maxWidth = favoriteMax)) }
    val favoriteWidth = favoritePlaceables.width()
    if (favoriteWidth > 0) left = (left - favoriteWidth - gap).coerceAtLeast(0)
    val uploaderPlaceables = uploaderMeasurables.map { it.measure(loose.copy(minWidth = left, maxWidth = left)) }

    val all = startPlaceables + uploaderPlaceables + favoritePlaceables + detailsPlaceables
    val height = (all.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
    val width = if (constraints.hasBoundedWidth) {
        maxWidth
    } else {
        all.width() + 3 * gap
    }
    layout(width, height) {
        var x = 0
        fun place(placeables: List<Placeable>) = placeables.forEach {
            it.placeRelative(x, Alignment.CenterVertically.align(it.height, height))
            x += it.width
        }
        place(startPlaceables)
        x += gap
        place(uploaderPlaceables)
        x = width - detailsWidth - if (favoriteWidth > 0) favoriteWidth + gap else 0
        place(favoritePlaceables)
        x = width - detailsWidth
        place(detailsPlaceables)
    }
}
