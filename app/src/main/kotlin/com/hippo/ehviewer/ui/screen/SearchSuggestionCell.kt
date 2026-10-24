package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ehviewer.core.ui.component.GLASS_TILE_TINT
import com.ehviewer.core.ui.component.GlassStyle
import com.ehviewer.core.ui.component.LocalTileGlass
import com.ehviewer.core.ui.component.floatingGlass
import com.ehviewer.core.ui.component.leavingWithSearch

/**
 * One search suggestion as a card of the two staggered columns of the search bar (fork, like the search history of
 * the Mihon fork): a history entry with its delete button, a tag with its translation, or a gallery link.
 */
@Composable
fun SearchSuggestionCell(suggestion: Suggestion, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    // Fork: on the glass of the expanded search, a card of glass refracting it; its text and icons in the color of the
    // text, white at night and black by day, the paler hint got lost on the glass
    val glass = LocalTileGlass.current
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Surface(
        onClick = { suggestion.onClick() },
        shape = shape,
        // The expanded search bar is surfaceContainerHigh itself: a brighter card stands out from it
        color = if (glass != null) Color.Transparent else scheme.surfaceBright,
        contentColor = scheme.onSurface,
        // Closing the search, the card leaves with it, see LocalSearchExit
        modifier = modifier.leavingWithSearch().floatingGlass(glass, shape, scheme.surfaceBright.copy(alpha = GLASS_TILE_TINT), GlassStyle.Tile),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (suggestion.canOpenDirectly) {
                Icon(
                    imageVector = Icons.AutoMirrored.Default.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 12.dp, top = 10.dp).size(20.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = if (suggestion.canDelete) 0.dp else 12.dp),
            ) {
                Text(
                    text = suggestion.keyword,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                )
                suggestion.hint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (glass != null) scheme.onSurface else scheme.onSurfaceVariant,
                        maxLines = 3,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (suggestion.canDelete) {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
