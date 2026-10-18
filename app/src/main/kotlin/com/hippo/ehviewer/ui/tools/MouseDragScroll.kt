package com.hippo.ehviewer.ui.tools

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Velocity
import kotlin.math.absoluteValue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Fork: Compose lists leave mouse drags alone (only the wheel scrolls them), so a mouse drag scrolled nothing, could not
 * pull to refresh, and the drawer behind took it as a swipe. This scrolls [state] by a vertical mouse drag through
 * nested scrolling, as a finger does: the top bar and pull to refresh above follow it. Touch is left to the list.
 */
fun Modifier.mouseDragScroll(state: ScrollableState): Modifier = composed {
    val dispatcher = remember { NestedScrollDispatcher() }
    val connection = remember { object : NestedScrollConnection {} }
    nestedScroll(connection, dispatcher).pointerInput(state) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.type != PointerType.Mouse) return@awaitEachGesture
                var overSlop = 0f
                val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                    // Going more sideways than up or down: left to the drawers (not taken, it waits on)
                    val move = change.position - down.position
                    if (move.x.absoluteValue <= move.y.absoluteValue) {
                        change.consume()
                        overSlop = over
                    }
                } ?: return@awaitEachGesture
                // As a scroll of the list, like a finger's: it stops a fling and shows the fast scroller
                val deltas = Channel<Float>(Channel.UNLIMITED)
                val scrolling = launch {
                    state.scroll(MutatePriority.UserInput) {
                        for (delta in deltas) {
                            val pre = dispatcher.dispatchPreScroll(Offset(0f, delta), NestedScrollSource.UserInput).y
                            val left = delta - pre
                            // A drag down shows what is above: the list scrolls backward
                            val consumed = -scrollBy(-left)
                            dispatcher.dispatchPostScroll(Offset(0f, consumed), Offset(0f, left - consumed), NestedScrollSource.UserInput)
                        }
                    }
                }
                deltas.trySend(overSlop)
                verticalDrag(start.id) { change ->
                    deltas.trySend(change.positionChange().y)
                    change.consume()
                }
                deltas.close()
                // Released: pull to refresh refreshes (or springs back), the top bar settles
                launch {
                    scrolling.join()
                    val preFling = dispatcher.dispatchPreFling(Velocity.Zero)
                    dispatcher.dispatchPostFling(Velocity.Zero - preFling, Velocity.Zero)
                }
            }
        }
    }
}
