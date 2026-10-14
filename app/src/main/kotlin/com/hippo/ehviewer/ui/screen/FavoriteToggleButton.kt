package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Fork: the favorite button of the gallery detail, like FilledTertiaryIconToggleButton but with a long press: a tap
 * uses the default collection, a long press asks for one.
 */
@Composable
fun FavoriteToggleButton(
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onLongClickLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val containerColor = if (checked) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val shape = if (checked) RoundedCornerShape(12.dp) else CircleShape
    Surface(shape = shape, color = containerColor, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = onLongClickLabel,
                    onLongClick = onLongClick,
                    onClick = onClick,
                ),
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColorFor(containerColor), content = content)
        }
    }
}
