package com.ehviewer.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
) {
    val glass = LocalTileGlass.current
    if (glass == null) {
        FilterChip(selected = selected, onClick = onClick, label = label, modifier = modifier, trailingIcon = trailingIcon)
        return
    }
    val scheme = MaterialTheme.colorScheme
    val onGlass = scheme.onSurface
    val shape = FilterChipDefaults.shape
    // The chip is 32 dp tall in a 48 dp touch target: the glass goes on the chip, not on the target around it, or it
    // stood out above and below the chip and its selected light. The box keeps the room of the target, a touch near
    // the chip still reaches it. Closing the search, the chip leaves with it (leavingWithSearch)
    Box(modifier.leavingWithSearch().minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
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

/** Fork: how much of the card color lies over the glass of a card or a chip of the expanded search. */
const val GLASS_TILE_TINT = 0.3f
