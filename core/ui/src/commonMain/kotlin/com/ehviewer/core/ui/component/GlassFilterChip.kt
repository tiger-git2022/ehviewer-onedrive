package com.ehviewer.core.ui.component

import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

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
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier.floatingGlass(glass, shape, scheme.surfaceBright.copy(alpha = GLASS_TILE_TINT), GlassStyle.Tile),
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

/** Fork: how much of the card color lies over the glass of a card or a chip of the expanded search. */
const val GLASS_TILE_TINT = 0.3f
