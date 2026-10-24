package com.ehviewer.core.ui.component

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Fork: closing the expanded search with liquid glass, its cards and chips leave one after another from the bottom of
 * the screen up, fading and rising, and then the blurred glass behind them fades out. The value goes from 0 (open)
 * to 1 (gone) over the closing, evenly in time; null outside the expanded search.
 */
val LocalSearchExit = compositionLocalOf<(() -> Float)?> { null }

/** A card or a chip of the expanded search leaving with it, see [LocalSearchExit]; nothing outside the search. */
@Composable
fun Modifier.leavingWithSearch(): Modifier {
    val exit = LocalSearchExit.current ?: return this
    val rise = with(LocalDensity.current) { 16.dp.toPx() }
    // The middle of the part and the height of the window, kept from its last placement, read only when drawing
    val place = remember { FloatArray(2) }
    return onPlaced { coordinates ->
        place[0] = coordinates.positionInRoot().y + coordinates.size.height / 2f
        var root = coordinates
        while (true) root = root.parentCoordinates ?: break
        place[1] = root.size.height.toFloat()
    }.graphicsLayer {
        val height = place[1]
        val fromBottom = if (height > 0f) 1f - place[0] / height else 0f
        val gone = FastOutLinearInEasing.transform(searchExitFraction(exit(), fromBottom))
        alpha = 1f - gone
        translationY = -rise * gone
    }
}

/**
 * How far a part [fromBottom] of the screen up (0 at the bottom, 1 at the top) has left when the closing is at
 * [exit]: the bottom one starts at once, the top one [EXIT_STAGGER] into the closing, each takes [EXIT_ITEM].
 */
fun searchExitFraction(exit: Float, fromBottom: Float): Float = ((exit - fromBottom.coerceIn(0f, 1f) * EXIT_STAGGER) / EXIT_ITEM).coerceIn(0f, 1f)

/** The opacity of the blurred glass behind the search when the closing is at [exit]: it fades once most cards left. */
fun searchExitGlassAlpha(exit: Float): Float = 1f - FastOutSlowInEasing.transform(((exit - EXIT_GLASS_START) / (1f - EXIT_GLASS_START)).coerceIn(0f, 1f))

private const val EXIT_STAGGER = 0.4f
private const val EXIT_ITEM = 0.4f
private const val EXIT_GLASS_START = 0.5f
