package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
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
    // Closing the search, the alpha of the title and the buttons fading in, and how far the field has come from the
    // open search (0) into the collapsed field (1): its chips, made in the open search's place, shrink into it
    contentAlpha: () -> Float = { 1f },
    closing: () -> Float = { 1f },
    // Whether the search is opening or closing: the field's width is then between the two, and the chips break into
    // lines as they will at its width once opened or closed, see ChipFieldMemory
    transitioning: () -> Boolean = { false },
    memory: ChipFieldMemory = remember { ChipFieldMemory() },
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val translate by Settings.showTagTranslations.collectAsState()
    val textColor = if (focused) colors.focusedTextColor else colors.unfocusedTextColor
    val textStyle = LocalTextStyle.current.merge(TextStyle(color = textColor))
    // Collapsed, a text searched flows on to the line below when it doesn't fit beside the buttons
    val flowingText = if (!expanded && state.text.isNotEmpty()) state.text.toString() else null
    BasicTextField(
        state = state,
        modifier = modifier
            .sizeIn(minWidth = SearchBarMinWidth, maxWidth = SearchBarMaxWidth, minHeight = SearchBarDefaults.InputFieldHeight)
            .onFocusChanged { if (it.isFocused) onExpandedChange(true) },
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = textStyle,
        cursorBrush = SolidColor(colors.cursorColor),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        onKeyboardAction = { onSearch() },
        interactionSource = interactionSource,
        decorator = { innerTextField ->
            ChipFieldLayout(
                tags = tags,
                expanded = expanded,
                closing = closing,
                transitioning = transitioning,
                memory = memory,
                showText = expanded || tags.isEmpty() || state.text.isNotEmpty(),
                flowingText = flowingText,
                textStyle = textStyle,
                textAlpha = contentAlpha,
                leadingIcon = leadingIcon?.let { icon ->
                    {
                        val color = if (focused) colors.focusedLeadingIconColor else colors.unfocusedLeadingIconColor
                        Box(Modifier.graphicsLayer { alpha = contentAlpha() }) {
                            CompositionLocalProvider(LocalContentColor provides color) { icon() }
                        }
                    }
                },
                trailingIcon = {
                    val color = if (focused) colors.focusedTrailingIconColor else colors.unfocusedTrailingIconColor
                    Box(Modifier.graphicsLayer { alpha = contentAlpha() }) {
                        CompositionLocalProvider(LocalContentColor provides color) { trailingIcon() }
                    }
                },
                text = {
                    // The text flowing in lines is drawn by the layout in its place
                    Box(Modifier.graphicsLayer { alpha = if (flowingText != null) 0f else contentAlpha() }, contentAlignment = Alignment.CenterStart) {
                        if (state.text.isEmpty() && tags.isEmpty()) {
                            val color = if (focused) colors.focusedPlaceholderColor else colors.unfocusedPlaceholderColor
                            CompositionLocalProvider(LocalContentColor provides color) { placeholder() }
                        }
                        innerTextField()
                    }
                },
                chip = { index, tag, role, chipModifier ->
                    SearchTagChip(
                        tag = tag,
                        translate = translate,
                        interactive = expanded,
                        compact = !expanded,
                        // Made as the search closes: as it was in the open search
                        startOpen = { closing() < 1f },
                        settled = role == ChipRole.Measure,
                        glass = glass,
                        // Not for a chip leaving, whose tag is gone
                        onRemove = { if (role == ChipRole.Shown) onRemoveTag(index) },
                        onToggleExcluded = { if (role == ChipRole.Shown) onToggleExcluded(index) },
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
 * What a tag chip field keeps past itself, as the search bar makes a new one each time the search closes: how wide
 * the field was, open and collapsed, once settled. While the search opens or closes, the field's width is between the
 * two, and the chips break into lines at the width the field is going to, going straight to their line.
 */
class ChipFieldMemory {
    internal var openWidth = 0
    internal var collapsedWidth = 0
}

/** A chip of a tag in the search, of one removed going away, or one at the size it is going to, only measured. */
internal enum class ChipRole { Shown, Leaving, Measure }

// The same chip when its tag is excluded or taken back, which only changes the sign before it
private fun chipKey(tag: String) = tag.trimStart('-', '~')

/**
 * The icons on the first line, as tall as the Material search field, and the chips then the text flowing in lines
 * from beside the leading icon: the first line ends before the trailing icons, the next ones at the end of the field.
 *
 * Lines break by the size the chips are going to and the width the field is going to, so while chips grow or shrink
 * and the search opens or closes, a chip goes straight to its line. A tag added grows in from where it goes; one
 * removed shrinks away where it was, the others moving into its room.
 */
@Composable
private fun ChipFieldLayout(
    tags: List<String>,
    expanded: Boolean,
    closing: () -> Float,
    transitioning: () -> Boolean,
    memory: ChipFieldMemory,
    showText: Boolean,
    flowingText: String?,
    textStyle: TextStyle,
    textAlpha: () -> Float,
    leadingIcon: (@Composable () -> Unit)?,
    trailingIcon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    chip: @Composable (Int, String, ChipRole, Modifier) -> Unit,
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
    // The chips of the field as it is made show at once; those of tags added later grow in
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ready = true }
    // Tags removed, by key, with where they were in the list and the tag, while their chips go away
    val leaving = remember { mutableStateMapOf<String, Pair<Int, String>>() }
    val previous = remember { arrayOf(tags) }
    if (previous[0] != tags) {
        val keys = tags.mapTo(HashSet(), ::chipKey)
        previous[0].forEachIndexed { i, tag ->
            val id = chipKey(tag)
            if (id !in keys) leaving[id] = i to tag
        }
        leaving.keys.removeAll(keys)
        previous[0] = tags
    }
    // The chips shown: those of the tags, and those going away where they were
    val entries = buildList {
        tags.forEachIndexed { i, tag -> add(Triple(i, tag, chipKey(tag))) }
        leaving.entries.sortedBy { it.value.first }.forEach { (id, value) ->
            add(value.first.coerceAtMost(size), Triple(-1, value.second, id))
        }
    }
    // Where each chip was last placed, for one going away to stay there
    val places = remember { HashMap<String, IntOffset>() }
    // Collapsed, the text searched: the part beside the buttons and the rest on the next line, where they're drawn
    val textMeasurer = rememberTextMeasurer()
    var flowing by remember { mutableStateOf<List<Pair<TextLayoutResult, IntOffset>>>(emptyList()) }
    Layout(
        contents = listOf<@Composable () -> Unit>(
            { leadingIcon?.invoke() },
            trailingIcon,
            text,
            {
                entries.forEach { (index, tag, id) ->
                    key(id) {
                        val shown = index >= 0
                        val presence = remember { Animatable(if (ready) 0f else 1f) }
                        LaunchedEffect(shown) {
                            presence.animateTo(if (shown) 1f else 0f, ChipPresenceSpec)
                            if (!shown) leaving.remove(id)
                        }
                        chip(
                            index,
                            tag,
                            if (shown) ChipRole.Shown else ChipRole.Leaving,
                            Modifier.animatePlacement().graphicsLayer {
                                val value = presence.value
                                alpha = value
                                scaleX = ChipHiddenScale + (1f - ChipHiddenScale) * value
                                scaleY = scaleX
                            },
                        )
                    }
                }
            },
            // The chips at the size they are going to: measured for the lines, never shown
            { tags.forEachIndexed { index, tag -> key(chipKey(tag)) { chip(index, tag, ChipRole.Measure, Modifier) } } },
        ),
        modifier = Modifier.clipToBounds().drawWithContent {
            drawContent()
            flowing.forEach { (text, offset) -> drawText(text, topLeft = Offset(offset.x.toFloat(), offset.y.toFloat()), alpha = textAlpha()) }
        },
    ) { (leadingMeasurables, trailingMeasurables, textMeasurables, chipMeasurables, measureMeasurables), constraints ->
        val lineHeight = SearchBarDefaults.InputFieldHeight.roundToPx()
        val iconOffset = IconOffset.roundToPx()
        val padding = TextPadding.roundToPx()
        val gap = ChipGap.roundToPx()
        val iconConstraints = Constraints(maxHeight = lineHeight)
        val leading = leadingMeasurables.firstOrNull()?.measure(iconConstraints)
        val trailing = trailingMeasurables.firstOrNull()?.measure(iconConstraints)
        val trailingWidth = trailing?.width ?: 0
        val lineStart = if (leading != null && leading.width > 0) leading.width + iconOffset else padding
        // Closing, the lines start where they did beside the back button of the open search, coming to the side
        val closingInset = ((1f - closing()) * ClosingStartInset.toPx()).roundToInt()
        val start = lineStart + closingInset
        val bounded = constraints.hasBoundedWidth
        // Asked how wide it would be (its intrinsic width, which the search bar asks of the field), all on one line
        val width = if (bounded) constraints.maxWidth else Int.MAX_VALUE / 2
        val trailingRoom = if (trailingWidth > 0) trailingWidth + iconOffset else padding
        val firstLineEnd = width - trailingRoom
        val lineEnd = width - padding
        fun endOf(line: Int) = if (line == 0) firstLineEnd else lineEnd

        // The width the field is going to, opening or closing, where the lines break
        val moving = bounded && transitioning()
        val settledWidth = if (expanded) memory.openWidth else memory.collapsedWidth
        val breakWidth = if (moving && settledWidth > 0) settledWidth else width
        fun breakEndOf(line: Int) = breakWidth - if (line == 0) trailingRoom else padding

        // Unbounded when asked for the intrinsic width: Constraints cannot hold a width as large as the one above
        val chipConstraints = Constraints(maxWidth = if (bounded) (lineEnd - start).coerceAtLeast(0) else Constraints.Infinity)
        val chips = chipMeasurables.map { it.measure(chipConstraints) }
        val breakConstraints = Constraints(maxWidth = if (bounded) (breakWidth - padding - lineStart).coerceAtLeast(0) else Constraints.Infinity)
        val goingTo = measureMeasurables.map { it.measure(breakConstraints) }
        val chipHeight = maxOf(chips.maxOfOrNull { it.height } ?: 0, goingTo.maxOfOrNull { it.height } ?: 0)

        // Lines by the chips' sizes to come (breakX), places by their sizes now (x)
        var line = 0
        var x = start
        var breakX = lineStart
        val chipPlaces = arrayOfNulls<IntOffset>(chips.size)
        entries.forEachIndexed { i, (index, _, _) ->
            if (index < 0) return@forEachIndexed
            val size = goingTo.getOrNull(index)?.width ?: chips[i].width
            // A chip too wide for the first line even alone goes below it
            if (breakX + size > breakEndOf(line) && (breakX > lineStart || line == 0)) {
                line++
                x = start
                breakX = lineStart
            }
            chipPlaces[i] = IntOffset(x, line)
            x += chips[i].width + gap
            breakX += size + gap
        }
        val textMinWidth = when {
            !showText -> 0
            tags.isEmpty() -> 0
            expanded -> OpenTextMinWidth.roundToPx()
            else -> CollapsedTextMinWidth.roundToPx()
        }
        if (textMinWidth > 0 && breakEndOf(line) - breakX < textMinWidth) {
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
        // What fits of the text on its line, and what doesn't on the next one, ending with an ellipsis
        val textParts = if (bounded && flowingText != null) {
            val firstWidth = (endOf(textLine) - textX).coerceAtLeast(0)
            val first = textMeasurer.measure(flowingText, textStyle, maxLines = 1, constraints = Constraints(maxWidth = firstWidth))
            val end = first.getLineEnd(0, visibleEnd = true)
            val rest = flowingText.substring(end.coerceIn(0, flowingText.length)).trimStart()
            if (rest.isEmpty()) {
                listOf(first to textLine)
            } else {
                val restWidth = (lineEnd - start).coerceAtLeast(0)
                val second = textMeasurer.measure(rest, textStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = restWidth))
                listOf(first to textLine, second to textLine + 1)
            }
        } else {
            emptyList()
        }
        if (textParts.size > 1) line = textLine + 1
        val lines = line + 1
        val needed = if (lines == 1) lineHeight else topOf(lines - 1) + lineSize + top
        // Not when asked for its intrinsic width, laid out on one line
        if (bounded) targetHeight = needed
        val shown = if (height.value < 0f) needed else height.value.roundToInt()
        val layoutWidth = if (bounded) width else textX + textPlaceable.width + trailingRoom
        val layoutHeight = shown.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(layoutWidth.coerceIn(constraints.minWidth, constraints.maxWidth), layoutHeight) {
            if (bounded && !moving) {
                if (expanded) memory.openWidth = width else memory.collapsedWidth = width
            }
            leading?.place(iconOffset, (lineHeight - leading.height) / 2)
            trailing?.place(layoutWidth - trailingWidth - iconOffset, (lineHeight - trailing.height) / 2)
            chips.forEachIndexed { i, chip ->
                val id = entries[i].third
                val place = chipPlaces[i]
                if (place != null) {
                    val position = IntOffset(place.x, topOf(place.y) + (lineSize - chip.height) / 2)
                    if (bounded) places[id] = position
                    chip.place(position)
                } else {
                    // Going away where it was
                    chip.place(places[id] ?: IntOffset(start, top))
                }
            }
            textPlaceable.place(textX, topOf(textLine) + (lineSize - textPlaceable.height) / 2)
            flowing = textParts.map { (text, onLine) ->
                text to IntOffset(if (onLine == textLine) textX else start, topOf(onLine) + (lineSize - text.size.height) / 2)
            }
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
    // Made as the search closes, starting as the chip of the open search
    startOpen: () -> Boolean = { false },
    // At the size it is going to, without animations: only measured, see ChipRole.Measure
    settled: Boolean = false,
) {
    val (namespace, name) = searchTagParts(tag)
    val context = LocalContext.current
    val full = (if (translate) with(context) { translateSearchTag(namespace, name) } else null) ?: "$namespace:$name"
    // Collapsed, the name without its namespace: the namespace narrows and fades away
    val namespaceLabel = full.substringBefore(':', "").let { if (it.isEmpty()) "" else "$it:" }
    val nameLabel = full.substringAfter(':')
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
    // From the open chip (0) to the collapsed, smaller one (1), going there with an animation
    val openAtStart = remember { startOpen() }
    val resize = remember { Animatable(if (openAtStart || !compact) 0f else 1f) }
    LaunchedEffect(compact) { resize.animateTo(if (compact) 1f else 0f, ChipResizeSpec) }
    val fraction = if (settled) (if (compact) 1f else 0f) else resize.value
    // The namespace and the ✕ shown by the open chip, leaving or coming with the change of size
    val openParts = remember { MutableTransitionState(openAtStart || !compact) }
    openParts.targetState = !compact
    val removable = remember { MutableTransitionState(openAtStart || interactive) }
    removable.targetState = interactive
    val typography = MaterialTheme.typography
    val style = typography.labelLarge.copy(fontSize = lerp(typography.labelLarge.fontSize, typography.labelMedium.fontSize, fraction))
    val sidePadding = lerp(12.dp, 9.dp, fraction)
    Row(
        modifier = modifier
            .height(lerp(ChipHeight, CompactChipHeight, fraction))
            .clip(CircleShape)
            .background(container)
            .then(if (glass) Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape) else Modifier)
            .then(if (interactive) Modifier.clickable(onClick = onToggleExcluded) else Modifier)
            // The ✕ brings its own room at the end
            .padding(start = sidePadding, end = lerp(4.dp, sidePadding, fraction)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prefix.isNotEmpty()) Text(text = prefix, color = content, style = style, maxLines = 1)
        if (namespaceLabel.isNotEmpty()) {
            val namespaceText = @Composable { Text(text = namespaceLabel, color = content, style = style, maxLines = 1, softWrap = false) }
            if (settled) {
                if (!compact) namespaceText()
            } else {
                AnimatedVisibility(visibleState = openParts) { namespaceText() }
            }
        }
        Text(
            text = nameLabel,
            color = content,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = lerp(ChipMaxTextWidth, CompactChipMaxTextWidth, fraction)),
        )
        val removeButton = @Composable {
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
        if (settled) {
            if (interactive) removeButton()
        } else {
            AnimatedVisibility(visibleState = removable) { removeButton() }
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

// Closing, how much further the chips start in the open search, beside its back button, than in the collapsed field
private val ClosingStartInset = 36.dp

// As SearchBarDefaults.InputField (SearchBar.kt): its sizes, the icons moved 4dp inwards, and the text 16dp from the
// sides of the field without icons
private val SearchBarMinWidth = 360.dp
private val SearchBarMaxWidth = 720.dp
private val IconOffset = 4.dp
private val TextPadding = 16.dp
private const val ClearFocusDelayMillis = 100L

private val ChipResizeSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)
private val ChipPresenceSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.01f)

// A tag picked comes from this scale, and one removed goes to it
private const val ChipHiddenScale = 0.6f
private val ChipMoveSpring = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))
private val FieldHeightSpring = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 1f)
