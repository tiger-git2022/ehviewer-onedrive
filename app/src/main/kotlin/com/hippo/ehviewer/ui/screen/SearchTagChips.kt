package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState

/**
 * Fork: the tags of the search as chips before the text of the search field, see SearchTags.kt. A chip of an expanded
 * search removes its tag with its ✕ and excludes the tag (or takes it back) when tapped; collapsed, the chips only show
 * the search, a tap opens it. They scroll sideways when there are more than fit, leaving [minTextWidth] to the text,
 * and lie over the line of the text without making the field taller.
 */
@Composable
fun SearchTagChips(
    tags: List<String>,
    interactive: Boolean,
    onRemove: (Int) -> Unit,
    onToggleExcluded: (Int) -> Unit,
    minTextWidth: () -> Dp,
    glass: Boolean,
) {
    val translate by Settings.showTagTranslations.collectAsState()
    val scrollState = rememberScrollState()
    // A tag picked comes in at the end: show it
    LaunchedEffect(tags.size, scrollState.maxValue) { scrollState.animateScrollTo(scrollState.maxValue) }
    Row(
        modifier = Modifier.chipRowLayout(minTextWidth).horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tags.forEachIndexed { index, tag ->
            SearchTagChip(
                tag = tag,
                translate = translate,
                interactive = interactive,
                glass = glass,
                onRemove = { onRemove(index) },
                onToggleExcluded = { onToggleExcluded(index) },
            )
        }
    }
}

/**
 * The row of chips leaves [minTextWidth] of the field to the text, and is as tall as the line of the text, the chips
 * standing out over and under it. Unbounded when the search bar asks the field how wide it would be (its intrinsic
 * width, on every layout): taking the text away from an unbounded width gave a width Constraints cannot hold, a crash.
 */
internal fun Modifier.chipRowLayout(minTextWidth: () -> Dp) = layout { measurable, constraints ->
    val maxWidth = if (constraints.hasBoundedWidth) {
        (constraints.maxWidth - minTextWidth().roundToPx()).coerceAtLeast(0)
    } else {
        Constraints.Infinity
    }
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxWidth, minHeight = 0))
    val height = minOf(placeable.height, TextLineHeight.roundToPx())
    layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
}

@Composable
private fun SearchTagChip(
    tag: String,
    translate: Boolean,
    interactive: Boolean,
    glass: Boolean,
    onRemove: () -> Unit,
    onToggleExcluded: () -> Unit,
) {
    val (namespace, name) = searchTagParts(tag)
    val context = LocalContext.current
    val label = (if (translate) with(context) { translateSearchTag(namespace, name) } else null) ?: "$namespace:$name"
    val excluded = isExcludedTag(tag)
    val prefix = when (tag.first()) {
        '-' -> "−"
        '~' -> "~"
        else -> ""
    }
    val colors = MaterialTheme.colorScheme
    val container = when {
        excluded -> colors.errorContainer.copy(alpha = if (glass) 0.55f else 1f)
        glass -> colors.primary.copy(alpha = 0.22f)
        else -> colors.secondaryContainer
    }
    val content = when {
        excluded -> colors.onErrorContainer
        glass -> colors.onSurface
        else -> colors.onSecondaryContainer
    }
    Row(
        modifier = Modifier
            .height(ChipHeight)
            .clip(CircleShape)
            .background(container)
            .then(if (glass) Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape) else Modifier)
            .then(if (interactive) Modifier.clickable(onClick = onToggleExcluded) else Modifier)
            .padding(start = 12.dp, end = if (interactive) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = prefix + label,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = ChipMaxTextWidth),
        )
        if (interactive) {
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.delete),
                    tint = content,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private val ChipHeight = 32.dp
private val ChipMaxTextWidth = 180.dp

// The line of the text in the Material text field
private val TextLineHeight = 24.dp
