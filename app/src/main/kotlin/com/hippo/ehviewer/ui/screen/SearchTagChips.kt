package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fork: the search field when the tags of the search are chips (see SearchTags.kt), in the place of
 * SearchBarDefaults.InputField, which can only put them on the line of its text. The chips, then the text, fill the
 * line beside the icons; those that don't fit go on lines below, under the icons too, and the field grows. A chip moves
 * to its new place, and the field to its new height, with an animation, as when a tag picked no longer fits on the
 * line or a tag removed makes room on it.
 *
 * Open, a chip removes its tag with its ✕ and excludes the tag (or takes it back) when tapped, and the text always
 * keeps [OpenTextMinWidth] after the chips, going to a line of its own when there isn't, so there is always a place to
 * tap for the keyboard. Collapsed, the chips only show the search, smaller and without their namespace; a tap opens it.
 */
@Composable
fun SearchTagChipField(
    state: TextFieldState,
    onSearch: () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    tags: List<String>,
    onRemoveTag: (Int) -> Unit,
    onToggleExcluded: (Int) -> Unit,
    glass: Boolean,
    colors: TextFieldColors,
    placeholder: @Composable () -> Unit,
    leadingIcon: (@Composable () -> Unit)?,
    trailingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val translate by Settings.showTagTranslations.collectAsState()
    val textColor = if (focused) colors.focusedTextColor else colors.unfocusedTextColor
    BasicTextField(
        state = state,
        modifier = modifier
            .sizeIn(minWidth = SearchBarMinWidth, maxWidth = SearchBarMaxWidth, minHeight = SearchBarDefaults.InputFieldHeight)
            .onFocusChanged { if (it.isFocused) onExpandedChange(true) },
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = LocalTextStyle.current.merge(TextStyle(color = textColor)),
        cursorBrush = SolidColor(colors.cursorColor),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        onKeyboardAction = { onSearch() },
        interactionSource = interactionSource,
        decorator = { innerTextField ->
            ChipFieldLayout(
                tags = tags,
                expanded = expanded,
                showText = expanded || tags.isEmpty() || state.text.isNotEmpty(),
                leadingIcon = leadingIcon?.let { icon ->
                    {
                        val color = if (focused) colors.focusedLeadingIconColor else colors.unfocusedLeadingIconColor
                        CompositionLocalProvider(LocalContentColor provides color) { icon() }
                    }
                },
                trailingIcon = {
                    val color = if (focused) colors.focusedTrailingIconColor else colors.unfocusedTrailingIconColor
                    CompositionLocalProvider(LocalContentColor provides color) { trailingIcon() }
                },
                text = {
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (state.text.isEmpty() && tags.isEmpty()) {
                            val color = if (focused) colors.focusedPlaceholderColor else colors.unfocusedPlaceholderColor
                            CompositionLocalProvider(LocalContentColor provides color) { placeholder() }
                        }
                        innerTextField()
                    }
                },
                chip = { index, tag, chipModifier ->
                    SearchTagChip(
                        tag = tag,
                        translate = translate,
                        interactive = expanded,
                        compact = !expanded,
                        glass = glass,
                        onRemove = { onRemoveTag(index) },
                        onToggleExcluded = { onToggleExcluded(index) },
                        modifier = chipModifier,
                    )
                },
            )
        },
    )
    // As SearchBarDefaults.InputField: collapsing takes the focus, and the keyboard, away
    val shouldClearFocus = !expanded && focused
    LaunchedEffect(expanded) {
        if (shouldClearFocus) {
            delay(ClearFocusDelayMillis)
            focusManager.clearFocus()
        }
    }
}

/**
 * The icons on the first line, as tall as the Material search field, and the chips then the text flowing in lines
 * from beside the leading icon: the first line ends before the trailing icons, the next ones at the end of the field.
 */
@Composable
private fun ChipFieldLayout(
    tags: List<String>,
    expanded: Boolean,
    showText: Boolean,
    leadingIcon: (@Composable () -> Unit)?,
    trailingIcon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    chip: @Composable (Int, String, Modifier) -> Unit,
) {
    // The height the lines need, and the height shown, going to it
    var targetHeight by remember { mutableIntStateOf(-1) }
    val height = remember { Animatable(-1f) }
    LaunchedEffect(Unit) {
        snapshotFlow { targetHeight }.collectLatest {
            if (it < 0) return@collectLatest
            if (height.value < 0f) height.snapTo(it.toFloat()) else height.animateTo(it.toFloat(), FieldHeightSpring)
        }
    }
    Layout(
        contents = listOf<@Composable () -> Unit>(
            { leadingIcon?.invoke() },
            trailingIcon,
            text,
            { tags.forEachIndexed { index, tag -> key(tag) { chip(index, tag, Modifier.animatePlacement()) } } },
        ),
        modifier = Modifier.clipToBounds(),
    ) { (leadingMeasurables, trailingMeasurables, textMeasurables, chipMeasurables), constraints ->
        val lineHeight = SearchBarDefaults.InputFieldHeight.roundToPx()
        val iconOffset = IconOffset.roundToPx()
        val padding = TextPadding.roundToPx()
        val gap = ChipGap.roundToPx()
        val iconConstraints = Constraints(maxHeight = lineHeight)
        val leading = leadingMeasurables.firstOrNull()?.measure(iconConstraints)
        val trailing = trailingMeasurables.firstOrNull()?.measure(iconConstraints)
        val trailingWidth = trailing?.width ?: 0
        val start = if (leading != null && leading.width > 0) leading.width + iconOffset else padding
        val bounded = constraints.hasBoundedWidth
        // Asked how wide it would be (its intrinsic width, which the search bar asks of the field), all on one line
        val width = if (bounded) constraints.maxWidth else Int.MAX_VALUE / 2
        val firstLineEnd = width - if (trailingWidth > 0) trailingWidth + iconOffset else padding
        val lineEnd = width - padding
        fun endOf(line: Int) = if (line == 0) firstLineEnd else lineEnd

        val chips = chipMeasurables.map { it.measure(Constraints(maxWidth = (lineEnd - start).coerceAtLeast(0))) }
        val chipHeight = chips.maxOfOrNull { it.height } ?: 0
        var line = 0
        var x = start
        val chipPlaces = chips.map {
            // A chip too wide for the first line even alone goes below it
            if (x + it.width > endOf(line) && (x > start || line == 0)) {
                line++
                x = start
            }
            val place = IntOffset(x, line)
            x += it.width + gap
            place
        }
        val textMinWidth = when {
            !showText -> 0
            chips.isEmpty() -> 0
            expanded -> OpenTextMinWidth.roundToPx()
            else -> CollapsedTextMinWidth.roundToPx()
        }
        if (textMinWidth > 0 && endOf(line) - x < textMinWidth) {
            line++
            x = start
        }
        val textLine = line
        val textX = x
        val textPlaceable = textMeasurables.first().measure(
            when {
                !showText -> Constraints.fixedWidth(0)
                bounded -> (endOf(textLine) - textX).coerceAtLeast(0).let { Constraints.fixedWidth(it) }
                else -> Constraints()
            },
        )

        // The chips of the first line stand in the middle of it; the lines below follow, and the field ends as far
        // below the last line as the first line is from the top
        val lineSize = maxOf(chipHeight, if (showText) textPlaceable.height else 0)
        val top = (lineHeight - lineSize) / 2
        fun topOf(index: Int) = if (index == 0) top else top + index * (lineSize + LineGap.roundToPx())
        val lines = line + 1
        val needed = if (lines == 1) lineHeight else topOf(lines - 1) + lineSize + top
        // Not when asked for its intrinsic width, laid out on one line
        if (bounded) targetHeight = needed
        val shown = if (height.value < 0f) needed else height.value.roundToInt()
        val layoutWidth = if (bounded) width else textX + textPlaceable.width + if (trailingWidth > 0) trailingWidth + iconOffset else padding
        val layoutHeight = shown.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(layoutWidth.coerceIn(constraints.minWidth, constraints.maxWidth), layoutHeight) {
            leading?.place(iconOffset, (lineHeight - leading.height) / 2)
            trailing?.place(layoutWidth - trailingWidth - iconOffset, (lineHeight - trailing.height) / 2)
            chips.forEachIndexed { i, chip ->
                val place = chipPlaces[i]
                chip.place(place.x, topOf(place.y) + (lineSize - chip.height) / 2)
            }
            textPlaceable.place(textX, topOf(textLine) + (lineSize - textPlaceable.height) / 2)
        }
    }
}

/**
 * Moves to where its parent places it with an animation, instead of jumping there; where it was first placed it
 * appears without moving.
 */
private fun Modifier.animatePlacement(): Modifier = composed {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<IntOffset?>(null) }
    var offset by remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }
    onPlaced {
        val position = it.positionInParent().round()
        target = position
        if (offset == null) offset = Animatable(position, IntOffset.VectorConverter)
    }.offset {
        val goal = target ?: return@offset IntOffset.Zero
        val animatable = offset ?: return@offset IntOffset.Zero
        if (animatable.targetValue != goal) scope.launch { animatable.animateTo(goal, ChipMoveSpring) }
        animatable.value - goal
    }
}

@Composable
private fun SearchTagChip(
    tag: String,
    translate: Boolean,
    interactive: Boolean,
    compact: Boolean,
    glass: Boolean,
    onRemove: () -> Unit,
    onToggleExcluded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (namespace, name) = searchTagParts(tag)
    val context = LocalContext.current
    val full = (if (translate) with(context) { translateSearchTag(namespace, name) } else null) ?: "$namespace:$name"
    // Collapsed, the name without its namespace
    val label = if (compact) full.substringAfter(':') else full
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
    val sidePadding = if (compact) 9.dp else 12.dp
    Row(
        modifier = modifier
            .height(if (compact) CompactChipHeight else ChipHeight)
            .clip(CircleShape)
            .background(container)
            .then(if (glass) Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape) else Modifier)
            .then(if (interactive) Modifier.clickable(onClick = onToggleExcluded) else Modifier)
            .padding(start = sidePadding, end = if (interactive) 4.dp else sidePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = prefix + label,
            color = content,
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = if (compact) CompactChipMaxTextWidth else ChipMaxTextWidth),
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
private val CompactChipHeight = 26.dp
private val CompactChipMaxTextWidth = 120.dp
private val ChipGap = 6.dp
private val LineGap = 6.dp

// Open, the room the text keeps after the chips; collapsed, the room a text typed after the tags keeps
private val OpenTextMinWidth = 96.dp
private val CollapsedTextMinWidth = 64.dp

// As SearchBarDefaults.InputField (SearchBar.kt): its sizes, the icons moved 4dp inwards, and the text 16dp from the
// sides of the field without icons
private val SearchBarMinWidth = 360.dp
private val SearchBarMaxWidth = 720.dp
private val IconOffset = 4.dp
private val TextPadding = 16.dp
private const val ClearFocusDelayMillis = 100L

private val ChipMoveSpring = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))
private val FieldHeightSpring = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 1f)
