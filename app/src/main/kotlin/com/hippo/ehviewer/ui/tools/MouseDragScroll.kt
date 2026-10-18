package com.hippo.ehviewer.ui.tools

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
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type != PointerType.Mouse) return@awaitEachGesture
            var overSlop = 0f
            val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                change.consume()
                overSlop = over
            } ?: return@awaitEachGesture
            fun scroll(delta: Float) {
                val pre = dispatcher.dispatchPreScroll(Offset(0f, delta), NestedScrollSource.UserInput).y
                val left = delta - pre
                // A drag down shows what is above: the list scrolls backward
                val consumed = -state.dispatchRawDelta(-left)
                dispatcher.dispatchPostScroll(Offset(0f, consumed), Offset(0f, left - consumed), NestedScrollSource.UserInput)
            }
            scroll(overSlop)
            verticalDrag(start.id) { change ->
                scroll(change.positionChange().y)
                change.consume()
            }
            // Released: pull to refresh refreshes (or springs back), the top bar settles
            dispatcher.coroutineScope.launch {
                val preFling = dispatcher.dispatchPreFling(Velocity.Zero)
                dispatcher.dispatchPostFling(Velocity.Zero - preFling, Velocity.Zero)
            }
        }
    }
}
