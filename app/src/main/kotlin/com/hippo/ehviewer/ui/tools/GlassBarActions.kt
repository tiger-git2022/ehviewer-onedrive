package com.hippo.ehviewer.ui.tools

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AppBarRow
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import com.ehviewer.core.ui.component.GLASS_SHEET_TINT
import com.ehviewer.core.ui.component.GlassStyle
import com.ehviewer.core.ui.component.floatingGlass
import com.hippo.ehviewer.ui.LocalPageBackdrop
import com.hippo.ehviewer.ui.rememberLiquidGlass
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Fork: an action of a top bar, an icon in the bar or a row in its overflow menu. */
class BarAction(val label: String, val icon: ImageVector? = null, val onClick: () -> Unit)

/**
 * Fork: the actions of a top bar, the first ones as icons and the rest in the overflow menu, like [AppBarRow]
 * with the same [maxItemCount] (the overflow button counts as one). With [glass], the menu is liquid glass drawn in
 * the page like the dialogs (a popup window lies above the page and cannot refract it); else it is [AppBarRow].
 * [leading] stays in the bar, before the actions.
 */
context(_: DialogState)
@Composable
fun BarActions(
    actions: List<BarAction>,
    maxItemCount: Int,
    glass: Boolean,
    leading: (@Composable () -> Unit)? = null,
) {
    if (!glass) {
        AppBarRow(
            overflowIndicator = {
                IconButton(onClick = { it.show() }, shapes = IconButtonDefaults.shapes()) {
                    Icon(imageVector = Icons.Default.MoreVert, contentDescription = null)
                }
            },
            maxItemCount = maxItemCount,
        ) {
            if (leading != null) customItem(appbarContent = leading, menuContent = {})
            actions.forEach { action ->
                clickableItem(
                    onClick = action.onClick,
                    icon = { action.icon?.let { Icon(imageVector = it, contentDescription = null) } },
                    label = action.label,
                )
            }
        }
        return
    }
    val leadingCount = if (leading != null) 1 else 0
    val inBar = if (actions.size + leadingCount <= maxItemCount) actions.size else (maxItemCount - 1 - leadingCount).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        actions.take(inBar).forEach { action ->
            IconButton(onClick = action.onClick, shapes = IconButtonDefaults.shapes()) {
                action.icon?.let { Icon(imageVector = it, contentDescription = action.label) }
            }
        }
        val inMenu = actions.drop(inBar)
        if (inMenu.isNotEmpty()) {
            var anchor by remember { mutableStateOf(Rect.Zero) }
            val scope = rememberCoroutineScope()
            IconButton(
                onClick = { scope.launch { showGlassMenu(anchor, inMenu)?.onClick?.invoke() } },
                modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(imageVector = Icons.Default.MoreVert, contentDescription = null)
            }
        }
    }
}

/** Fork: a menu below [anchor] (in window coordinates) as liquid glass, the chosen action or null. */
context(_: DialogState)
suspend fun showGlassMenu(anchor: Rect, actions: List<BarAction>): BarAction? = dialog { cont ->
    val close = { action: BarAction? -> if (cont.isActive) cont.resume(action) }
    GlassMenu(anchor, actions, close)
}

@Composable
private fun BoxScope.GlassMenu(anchor: Rect, actions: List<BarAction>, close: (BarAction?) -> Unit) {
    BackHandler { close(null) }
    // A tap outside closes it, as with a menu window
    Box(Modifier.matchParentSize().clickable(interactionSource = null, indication = null) { close(null) })
    var origin by remember { mutableStateOf(Offset.Zero) }
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    val glass = rememberLiquidGlass(LocalPageBackdrop.current)
    val shape = RoundedCornerShape(16.dp)
    val color = MaterialTheme.colorScheme.surfaceContainer
    Layout(
        content = {
            AnimatedVisibility(
                visibleState = shown,
                enter = fadeIn() + scaleIn(initialScale = 0.8f, transformOrigin = TransformOrigin(1f, 0f)),
            ) {
                Column(
                    modifier = Modifier.widthIn(min = 112.dp, max = 280.dp).width(IntrinsicSize.Max)
                        .then(if (glass != null) Modifier.floatingGlass(glass, shape, color.copy(alpha = GLASS_SHEET_TINT), GlassStyle.Sheet) else Modifier.background(color, shape))
                        .clip(shape).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                ) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(text = action.label) },
                            onClick = { close(action) },
                            leadingIcon = action.icon?.let { icon -> { Icon(imageVector = icon, contentDescription = null) } },
                        )
                    }
                }
            }
        },
        modifier = Modifier.matchParentSize().onGloballyPositioned { origin = it.positionInWindow() },
    ) { measurables, constraints ->
        val margin = 8.dp.roundToPx()
        val top = (anchor.bottom - origin.y).roundToInt().coerceIn(margin, max(margin, constraints.maxHeight - margin))
        val menu = measurables.first().measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = max(0, constraints.maxHeight - top - margin)),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = (anchor.right - origin.x).roundToInt() - menu.width
            menu.place(x.coerceIn(margin, max(margin, constraints.maxWidth - menu.width - margin)), top)
        }
    }
}

