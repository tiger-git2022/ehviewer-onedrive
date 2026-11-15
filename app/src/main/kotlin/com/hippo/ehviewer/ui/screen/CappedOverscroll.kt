package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.sign

/**
 * Fork: the overscroll of the platform (the stretch), which takes no more than [maxPull] of the finger pulling past
 * the end of the list: the rest is let go, so a long pull no longer stretches the list off the screen.
 */
@Composable
fun rememberCappedOverscrollEffect(maxPull: Dp = DefaultMaxPull): OverscrollEffect? {
    val inner = rememberOverscrollEffect() ?: return null
    val maxPullPx = with(LocalDensity.current) { maxPull.toPx() }
    return remember(inner, maxPullPx) { CappedOverscrollEffect(inner, maxPullPx) }
}

private class CappedOverscrollEffect(
    private val inner: OverscrollEffect,
    private val maxPull: Float,
) : OverscrollEffect by inner {
    // How far the finger has pulled past the end, down (> 0) or up (< 0), as given to the stretch
    private var pull = 0f

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        if (!inner.isInProgress) pull = 0f
        return inner.applyToScroll(delta, source) { remaining ->
            // What the stretch let go of before the list scrolled
            addPull(delta.y - remaining.y)
            val consumed = performScroll(remaining)
            val leftover = remaining.y - consumed.y
            if (source != NestedScrollSource.UserInput || leftover == 0f) {
                consumed
            } else {
                // Past the end: only what still fits under the cap goes to the stretch, the list takes the rest
                val room = if (leftover > 0f) max(0f, maxPull - max(pull, 0f)) else max(0f, maxPull - max(-pull, 0f))
                val toStretch = leftover.coerceIn(-room, room)
                addPull(toStretch)
                consumed.copy(y = consumed.y + leftover - toStretch)
            }
        }
    }

    private fun addPull(amount: Float) {
        val next = pull + amount
        // Let go past where it started: nothing pulled the other way yet
        pull = if (pull != 0f && sign(next) != sign(pull)) 0f else next
    }
}

private val DefaultMaxPull = 120.dp
