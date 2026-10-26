package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconToggleButtonShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.ui.LocalPageBackdrop
import com.hippo.ehviewer.ui.checkedGlass
import com.hippo.ehviewer.ui.glassOnSelectedColor

/**
 * Fork: a round toggle on a bar ("汉", hiding the favorited, the comment translation). Checked over liquid glass, a lens
 * of glass sits behind it ([checkedGlass]) with its text or icon in the primary color; without liquid glass, the solid
 * Material container.
 */
@Composable
fun GlassToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val glass = LocalPageBackdrop.current != null
    // The lens behind the button, the size of the button: on the button itself it took the larger touch target
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).checkedGlass(checked && enabled))
        IconToggleButton(
            checked = checked && enabled,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            shapes = if (glass) IconToggleButtonShapes(CircleShape, CircleShape, CircleShape) else IconButtonDefaults.toggleableShapes(),
            colors = IconButtonDefaults.iconToggleButtonColors(
                checkedContainerColor = if (glass) Color.Transparent else MaterialTheme.colorScheme.primaryContainer,
                checkedContentColor = if (glass) glassOnSelectedColor() else MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            content = content,
        )
    }
}
