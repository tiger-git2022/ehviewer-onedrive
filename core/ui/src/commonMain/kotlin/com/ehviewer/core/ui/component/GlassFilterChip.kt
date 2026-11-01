package com.ehviewer.core.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Fork: a [FilterChip] of the expanded search, a small glass while [LocalTileGlass] provides one. Its text and icons
 * take the color of the text, white at night and black by day: the paler Material colors got lost on the glass. A
 * selected chip keeps the light in the glass the expanded search gives the selected container.
 */
@Composable
fun GlassFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: @Composable (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val glass = LocalTileGlass.current
    if (glass == null) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = label,
            modifier = modifier.longPressFirst(onLongClick),
            trailingIcon = trailingIcon,
        )
        return
    }
    val scheme = MaterialTheme.colorScheme
    val onGlass = scheme.onSurface
    val shape = FilterChipDefaults.shape
    // The chip is 32 dp tall in a 48 dp touch target: the glass goes on the chip, not on the target around it, or it
    // stood out above and below the chip and its selected light. The box keeps the room of the target, a touch near
    // the chip still reaches it. Closing the search, the chip leaves with it (leavingWithSearch)
    Box(
        modifier.leavingWithSearch().minimumInteractiveComponentSize().longPressFirst(onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            FilterChip(
                selected = selected,
                onClick = onClick,
                label = label,
                modifier = Modifier.floatingGlass(glass, shape, scheme.surfaceBright.copy(alpha = GLASS_TILE_TINT), GlassStyle.Tile),
                trailingIcon = trailingIcon,
                shape = shape,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = onGlass,
                    iconColor = onGlass,
                    selectedLabelColor = onGlass,
                    selectedLeadingIconColor = onGlass,
                    selectedTrailingIconColor = onGlass,
                ),
                border = null,
            )
        }
    }
}

/**
 * Fork: a long press on the chip, which [FilterChip] has none of. It is seen before the chip's own click: once the
 * press is long enough, the rest of it is eaten, so the chip doesn't take it for a tap too.
 */
@Composable
private fun Modifier.longPressFirst(onLongClick: (() -> Unit)?): Modifier {
    if (onLongClick == null) return this
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(onLongClick)
    return semantics { onLongClick { current(); true } }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var ended = false
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                // An up, a move past the touch slop (the row scrolls), a move out of the chip or a touch taken by
                // another before the time: a tap or nothing
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed || change.isConsumed) break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    val final = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id }
                    if (final == null || final.isConsumed) break
                }
                ended = true
            }
            if (ended) return@awaitEachGesture
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            current()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}

/** Fork: how much of the card color lies over the glass of a card or a chip of the expanded search. */
const val GLASS_TILE_TINT = 0.3f
