package com.hippo.ehviewer.ui.tools

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewLabel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerColors
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.right
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.FastScrollLazyColumn
import com.ehviewer.core.ui.component.FloatingGlass
import com.ehviewer.core.ui.component.GLASS_SHEET_CORNER
import com.ehviewer.core.ui.component.GLASS_SHEET_TINT
import com.ehviewer.core.ui.component.GlassEdge
import com.ehviewer.core.ui.component.GlassStyle
import com.ehviewer.core.ui.component.LabeledCheckbox
import com.ehviewer.core.ui.component.edgeGlass
import com.ehviewer.core.ui.component.floatingGlass
import com.ehviewer.core.ui.util.ifNotNullThen
import com.ehviewer.core.ui.util.ifTrueThen
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhTagDatabase.suggestion
import com.hippo.ehviewer.ui.LocalPageBackdrop
import com.hippo.ehviewer.ui.rememberLiquidGlass
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

fun interface ActionScope {
    fun onSelect(action: String, that: suspend () -> Unit)
}

interface DialogScope<R> {
    var expectedValue: R
}

typealias MutableComposable = MutableState<(@Composable BoxScope.() -> Unit)?>

class DialogState(val mutex: MutatorMutex = MutatorMutex()) : MutableComposable by mutableStateOf(null) {
    /** Fork: the pages the dialogs of the app-wide state refract as liquid glass, set by MainActivity. */
    var backdrop by mutableStateOf<LayerBackdrop?>(null)

    @Composable
    fun rememberLocal() = remember { DialogState(mutex) }

    @Composable
    context(scope: BoxScope)
    fun Place() = value?.let { it(scope) }

    fun dismiss() {
        value = null
    }
}

context(state: DialogState)
suspend inline fun <R> dialog(crossinline block: @Composable BoxScope.(CancellableContinuation<R>) -> Unit) = state.mutex.mutate {
    try {
        suspendCancellableCoroutine { cont -> state.value = { block(cont) } }
    } finally {
        state.dismiss()
    }
}

context(_: DialogState)
suspend fun <R> awaitResult(
    initial: R,
    @StringRes title: Int? = null,
    invalidator: (suspend Raise<String>.(R) -> Unit)? = null,
    block: @Composable DialogScope<R>.(String?) -> Unit,
): R = dialog { cont ->
    val state = remember(cont) { mutableStateOf(initial) }
    var errorMsg by remember(cont) { mutableStateOf<String?>(null) }
    val impl = remember(cont) {
        object : DialogScope<R> {
            override var expectedValue by state
        }
    }
    if (invalidator != null) {
        LaunchedEffect(state) {
            snapshotFlow { state.value }.collectLatest {
                errorMsg = either { invalidator(it) }.leftOrNull()
            }
        }
    }
    EhAlertDialog(
        onDismissRequest = { cont.cancel() },
        confirmButton = {
            TextButton(
                onClick = {
                    if (invalidator == null || errorMsg == null) {
                        cont.resume(state.value)
                    }
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(text = stringResource(id = android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { cont.cancel() }, shapes = ButtonDefaults.shapes()) {
                Text(text = stringResource(id = android.R.string.cancel))
            }
        },
        title = title.ifNotNullThen { Text(text = stringResource(id = title!!)) },
        text = { block(impl, errorMsg) },
    )
}

context(_: Context, _: DialogState)
suspend fun awaitSelectTags(): List<String> = dialog { cont ->
    val selected = remember { mutableStateListOf<String>() }
    val state = rememberTextFieldState()
    var suggestionTranslate by rememberMutableStateInDataStore("SuggestionTranslate") { false }
    PausableAlertDialog(
        confirmButton = {
            TextButton(
                onClick = { cont.resume(selected.toList()) },
                shapes = ButtonDefaults.shapes(),
                content = { Text(text = stringResource(id = android.R.string.ok)) },
            )
        },
        dismissButton = {
            TextButton(
                onClick = { cont.cancel() },
                shapes = ButtonDefaults.shapes(),
                content = { Text(text = stringResource(id = android.R.string.cancel)) },
            )
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = stringResource(id = R.string.action_add_tag))
                if (EhTagDatabase.translatable) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(id = R.string.translate_tag_for_tagger),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Checkbox(
                        checked = suggestionTranslate,
                        onCheckedChange = { suggestionTranslate = !suggestionTranslate },
                    )
                }
            }
        },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    selected.forEach { text ->
                        InputChip(
                            selected = true,
                            onClick = { },
                            label = { Text(text = text, overflow = TextOverflow.Ellipsis, maxLines = 1) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.clickable { selected -= text },
                                )
                            },
                        )
                    }
                }
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { if (!it) expanded = false },
                ) {
                    OutlinedTextField(
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                        state = state,
                        label = { Text(text = stringResource(id = R.string.action_add_tag_tip)) },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    val text = state.text.toString().trim()
                                    if (text.isNotEmpty()) {
                                        selected += text
                                        state.clearText()
                                    }
                                },
                                shapes = IconButtonDefaults.shapes(),
                                content = {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                    )
                                },
                            )
                        },
                    )
                    val query = state.text.toString().trim().takeIf { s -> s.isNotEmpty() }
                    var items by remember { mutableStateOf(emptyList<EhTagDatabase.Tag>()) }
                    LaunchedEffect(suggestionTranslate, query) {
                        items = query?.let { suggestion(query, suggestionTranslate).take(15) }.orEmpty()
                        expanded = items.isNotEmpty()
                    }
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = {},
                        modifier = Modifier.heightIn(max = 192.dp),
                    ) {
                        items.forEach { (tag, hint) ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(text = tag, overflow = TextOverflow.Ellipsis, maxLines = 2)
                                        ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                                            if (hint != null) {
                                                Text(
                                                    text = hint,
                                                    overflow = TextOverflow.Ellipsis,
                                                    maxLines = 1,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                },
                                onClick = {
                                    if (tag.endsWith(':')) {
                                        state.setTextAndPlaceCursorAtEnd(tag)
                                    } else {
                                        selected += tag
                                        state.clearText()
                                    }
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }
            }
        },
        idleIcon = Icons.Default.NewLabel,
    )
}

context(_: DialogState)
suspend fun awaitInputText(
    initial: String = "",
    title: String? = null,
    hint: String? = null,
    isNumber: Boolean = false,
    @StringRes confirmText: Int = android.R.string.ok,
    onUserDismiss: (() -> Unit)? = null,
    invalidator: (suspend Raise<String>.(String) -> Unit)? = null,
) = dialog { cont ->
    val coroutineScope = rememberCoroutineScope()
    val state = rememberTextFieldState(initial)
    var error by remember(cont) { mutableStateOf<String?>(null) }
    EhAlertDialog(
        onDismissRequest = {
            cont.cancel()
            onUserDismiss?.invoke()
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val text = state.text.toString()
                    if (invalidator == null) {
                        cont.resume(text)
                    } else {
                        coroutineScope.launch {
                            error = either { invalidator(text) }.leftOrNull()
                            error ?: cont.resume(text)
                        }
                    }
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(text = stringResource(id = confirmText))
            }
        },
        title = title.ifNotNullThen { Text(text = title!!) },
        text = {
            OutlinedTextField(
                state = state,
                label = hint?.let { { Text(text = it) } },
                trailingIcon = error.ifNotNullThen {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                    )
                },
                supportingText = error.ifNotNullThen {
                    Text(text = error!!)
                },
                isError = error != null,
                keyboardOptions = if (isNumber) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            )
        },
    )
}

context(_: DialogState)
suspend fun awaitInputTextWithCheckBox(
    initial: String = "",
    @StringRes title: Int? = null,
    @StringRes hint: Int? = null,
    checked: Boolean,
    @StringRes checkBoxText: Int,
    isNumber: Boolean = false,
    invalidator: (suspend Raise<String>.(String, Boolean) -> Unit)? = null,
): Pair<String, Boolean> = dialog { cont ->
    val coroutineScope = rememberCoroutineScope()
    val state = rememberTextFieldState(initial)
    var error by remember(cont) { mutableStateOf<String?>(null) }
    var checkedState by remember { mutableStateOf(checked) }
    EhAlertDialog(
        onDismissRequest = { cont.cancel() },
        confirmButton = {
            TextButton(
                onClick = {
                    val text = state.text.toString()
                    if (invalidator == null) {
                        cont.resume(text to checkedState)
                    } else {
                        coroutineScope.launch {
                            error = either { invalidator(text, checkedState) }.leftOrNull()
                            error ?: cont.resume(text to checkedState)
                        }
                    }
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(text = stringResource(id = android.R.string.ok))
            }
        },
        title = title.ifNotNullThen { Text(text = stringResource(id = title!!)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    state = state,
                    label = hint?.let {
                        { Text(text = stringResource(id = it)) }
                    },
                    trailingIcon = error.ifNotNullThen {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                        )
                    },
                    supportingText = error.ifNotNullThen {
                        Text(text = error!!)
                    },
                    isError = error != null,
                    keyboardOptions = if (isNumber) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                )
                LabeledCheckbox(
                    modifier = Modifier.fillMaxWidth(),
                    checked = checkedState,
                    onCheckedChange = { checkedState = !checkedState },
                    label = stringResource(checkBoxText),
                )
            }
        },
    )
}

context(_: DialogState)
suspend fun awaitConfirmationOrCancel(
    @StringRes confirmText: Int = android.R.string.ok,
    @StringRes dismissText: Int = android.R.string.cancel,
    @StringRes title: Int? = null,
    showConfirmButton: Boolean = true,
    showCancelButton: Boolean = true,
    onCancelButtonClick: () -> Unit = {},
    secure: Boolean = false,
    text: @Composable (() -> Unit)? = null,
) = dialog { cont ->
    EhAlertDialog(
        onDismissRequest = { cont.cancel() },
        confirmButton = {
            if (showConfirmButton) {
                TextButton(onClick = { cont.resume(Unit) }, shapes = ButtonDefaults.shapes()) {
                    Text(text = stringResource(id = confirmText))
                }
            }
        },
        dismissButton = showCancelButton.ifTrueThen {
            TextButton(
                onClick = {
                    onCancelButtonClick()
                    cont.cancel()
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(text = stringResource(id = dismissText))
            }
        },
        title = title.ifNotNullThen { Text(text = stringResource(id = title!!)) },
        text = text,
        properties = if (secure) {
            DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)
        } else {
            DialogProperties()
        },
    )
}

context(_: DialogState)
suspend fun awaitSelectDate(
    @StringRes title: Int,
    initialSelectedDateMillis: Long? = null,
    initialDisplayedMonthMillis: Long? = initialSelectedDateMillis,
    yearRange: IntRange = DatePickerDefaults.YearRange,
    initialDisplayMode: DisplayMode = DisplayMode.Picker,
    selectableDates: SelectableDates = DatePickerDefaults.AllDates,
    showModeToggle: Boolean = true,
): Long = dialog { cont ->
    val state = rememberDatePickerState(
        initialSelectedDateMillis,
        initialDisplayedMonthMillis,
        yearRange,
        initialDisplayMode,
        selectableDates,
    )
    val confirmButton = @Composable {
        TextButton(onClick = { state.selectedDateMillis?.let { cont.resume(it) } ?: cont.cancel() }, shapes = ButtonDefaults.shapes()) {
            Text(text = stringResource(id = android.R.string.ok))
        }
    }
    val dismissButton = @Composable {
        TextButton(onClick = { cont.cancel() }, shapes = ButtonDefaults.shapes()) {
            Text(text = stringResource(id = android.R.string.cancel))
        }
    }
    val picker: @Composable (Modifier, DatePickerColors) -> Unit = { modifier, colors ->
        DatePicker(
            state = state,
            modifier = modifier,
            title = {
                Text(
                    text = stringResource(id = title),
                    modifier = Modifier.padding(DatePickerTitlePadding),
                )
            },
            showModeToggle = showModeToggle,
            colors = colors,
        )
    }
    // Fork: as liquid glass, like the other dialogs
    val glass = rememberLiquidGlass(LocalPageBackdrop.current)
    if (glass != null) {
        GlassDialog(glass, onDismissRequest = { cont.cancel() }, modifier = Modifier.requiredWidth(360.dp).heightIn(max = 568.dp)) {
            Column {
                picker(Modifier.weight(1f, fill = false), DatePickerDefaults.colors(containerColor = Color.Transparent))
                Row(
                    modifier = Modifier.align(Alignment.End).padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    dismissButton()
                    confirmButton()
                }
            }
        }
    } else {
        DatePickerDialog(
            onDismissRequest = { cont.cancel() },
            confirmButton = confirmButton,
            dismissButton = dismissButton,
        ) {
            picker(Modifier, DatePickerDefaults.colors())
        }
    }
}

context(_: DialogState)
suspend fun <R> showNoButton(respectDefaultWidth: Boolean = true, block: @Composable Continuation<R>.() -> Unit): R = dialog { cont ->
    val size = with(Modifier) { if (!respectDefaultWidth) defaultMinSize(280.dp) else width(280.dp) }
    // Fork: as liquid glass, drawn in the page: a dialog window lies above the page and cannot refract it
    val glass = rememberLiquidGlass(LocalPageBackdrop.current)
    if (glass != null) {
        GlassDialog(glass, onDismissRequest = { cont.cancel() }, modifier = size) { block(cont) }
    } else {
        BasicAlertDialog(
            onDismissRequest = { cont.cancel() },
            properties = DialogProperties(usePlatformDefaultWidth = respectDefaultWidth),
            content = {
                Surface(
                    modifier = size,
                    shape = AlertDialogDefaults.shape,
                    color = AlertDialogDefaults.containerColor,
                    tonalElevation = AlertDialogDefaults.TonalElevation,
                    content = { block(cont) },
                )
            },
        )
    }
}

/** Fork: a dialog over the page as liquid glass, like the drawers, see [showNoButton]. */
@Composable
internal fun BoxScope.GlassDialog(
    glass: FloatingGlass,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    content: @Composable () -> Unit,
) {
    // Takes back even when it does not dismiss, like a dialog window
    BackHandler { if (dismissOnBackPress) onDismissRequest() }
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(visibleState = shown, modifier = Modifier.matchParentSize(), enter = fadeIn()) {
        // Takes the taps even when they do not dismiss, so the page under it does not get them
        Box(
            Modifier.fillMaxSize().background(GlassDialogScrim).clickable(interactionSource = null, indication = null) {
                if (dismissOnClickOutside) onDismissRequest()
            },
        )
    }
    Box(Modifier.matchParentSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visibleState = shown, enter = fadeIn() + scaleIn(initialScale = 0.9f)) {
            // The list items keep the glass visible instead of covering it
            val colors = MaterialTheme.colorScheme
            MaterialTheme(colorScheme = colors.copy(surface = Color.Transparent)) {
                Surface(
                    modifier = modifier.floatingGlass(glass, AlertDialogDefaults.shape, AlertDialogDefaults.containerColor.copy(alpha = GLASS_SHEET_TINT), GlassStyle.Sheet),
                    shape = AlertDialogDefaults.shape,
                    color = Color.Transparent,
                    contentColor = colors.onSurface,
                    content = content,
                )
            }
        }
    }
}

/**
 * Fork: a [ModalBottomSheet] as liquid glass over the page, like [GlassDialog]: it slides up from the bottom, a tap
 * outside or back closes it. Without liquid glass it is the Material sheet.
 */
@Composable
fun BoxScope.EhModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = rememberLiquidGlass(LocalPageBackdrop.current)
    if (glass == null) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            contentWindowInsets = { WindowInsets() },
            content = content,
        )
        return
    }
    BackHandler(onBack = onDismissRequest)
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(visibleState = shown, modifier = Modifier.matchParentSize(), enter = fadeIn()) {
        Box(Modifier.fillMaxSize().background(GlassDialogScrim).clickable(interactionSource = null, indication = null, onClick = onDismissRequest))
    }
    // Dragged down by the handle or the content, as the Material sheet: far or fast enough closes it, else it springs back
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var sheetHeight by remember { mutableIntStateOf(0) }
    val flingToClose = with(LocalDensity.current) { SheetFlingToClose.toPx() }
    suspend fun settle(velocity: Float) {
        val close = dragOffset > sheetHeight / 3f || velocity > flingToClose
        animate(dragOffset, if (close) sheetHeight.toFloat() else 0f, velocity) { value, _ -> dragOffset = value }
        if (close) onDismissRequest()
    }
    val dragState = rememberDraggableState { dragOffset = (dragOffset + it).coerceAtLeast(0f) }
    val scrollConnection = remember {
        object : NestedScrollConnection {
            // Pulled down: scrolling back up lifts the sheet before it scrolls the content
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f || dragOffset <= 0f) return Offset.Zero
                val taken = available.y.coerceAtLeast(-dragOffset)
                dragOffset += taken
                return Offset(0f, taken)
            }

            // The content at its top pulls the sheet down
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                dragOffset += available.y
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragOffset <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }
    Box(Modifier.matchParentSize().then(modifier), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visibleState = shown, enter = slideInVertically { it }) {
            val colors = MaterialTheme.colorScheme
            MaterialTheme(colorScheme = colors.copy(surface = Color.Transparent)) {
                Surface(
                    modifier = Modifier.offset { IntOffset(0, dragOffset.roundToInt()) }
                        .onSizeChanged { sheetHeight = it.height }
                        .nestedScroll(scrollConnection)
                        .draggable(dragState, Orientation.Vertical, onDragStopped = { settle(it) })
                        .fillMaxWidth().edgeGlass(glass, GlassEdge.Top, colors.surfaceContainerLow.copy(alpha = GLASS_SHEET_TINT), GlassStyle.Sheet, GLASS_SHEET_CORNER),
                    shape = GlassBottomSheetShape,
                    color = Color.Transparent,
                    contentColor = colors.onSurface,
                ) {
                    Column {
                        BottomSheetDefaults.DragHandle(modifier = Modifier.align(Alignment.CenterHorizontally))
                        content()
                    }
                }
            }
        }
    }
}

/**
 * Fork: an [AlertDialog] as liquid glass over the page, like [showNoButton], laid out like the Material one. A secure
 * dialog stays a window, its own flag keeps it from screenshots.
 */
@Composable
fun BoxScope.EhAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val glass = rememberLiquidGlass(LocalPageBackdrop.current)
    if (glass == null || properties.securePolicy == SecureFlagPolicy.SecureOn) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            title = title,
            text = text,
            properties = properties,
        )
        return
    }
    GlassDialog(
        glass,
        onDismissRequest,
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(min = 280.dp, max = 560.dp),
        dismissOnBackPress = properties.dismissOnBackPress,
        dismissOnClickOutside = properties.dismissOnClickOutside,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            title?.let {
                ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                    Box(modifier = Modifier.padding(bottom = 16.dp)) { it() }
                }
            }
            // The text in the color of the title: the gray of a dialog is hard to read on the glass
            text?.let {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                    Box(modifier = Modifier.weight(1f, fill = false).padding(bottom = 24.dp)) { it() }
                }
            }
            FlowRow(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

context(_: DialogState)
suspend fun awaitSelectTime(
    title: String,
    initialHour: Int,
    initialMinute: Int,
) = dialog { cont ->
    val state = rememberTimePickerState(initialHour, initialMinute)
    val confirmButton = @Composable {
        TextButton(onClick = { cont.resume(state.hour to state.minute) }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(id = android.R.string.ok))
        }
    }
    val titleText = @Composable {
        Text(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            text = title,
            style = MaterialTheme.typography.labelMedium,
        )
    }
    val dismissButton = @Composable {
        TextButton(onClick = { cont.cancel() }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(id = android.R.string.cancel))
        }
    }
    // Fork: as liquid glass, like the other dialogs
    val glass = rememberLiquidGlass(LocalPageBackdrop.current)
    if (glass != null) {
        GlassDialog(glass, onDismissRequest = { cont.cancel() }, modifier = Modifier) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                titleText()
                TimePicker(state = state)
                Row(
                    modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    dismissButton()
                    confirmButton()
                }
            }
        }
    } else {
        TimePickerDialog(
            onDismissRequest = { cont.cancel() },
            confirmButton = confirmButton,
            title = titleText,
            dismissButton = dismissButton,
            content = { TimePicker(state = state) },
        )
    }
}

context(_: DialogState)
suspend fun awaitSingleChoice(
    items: List<String>,
    selected: Int,
    @StringRes title: Int? = null,
): Int = showNoButton {
    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        title?.let {
            Text(
                text = stringResource(id = it),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        items.forEachIndexed { index, text ->
            Row(
                modifier = Modifier.clickable { resume(index) }.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = index == selected, onClick = { resume(index) })
                Text(text = text)
            }
        }
    }
}

context(_: DialogState)
suspend fun awaitSelectItem(
    items: List<String>,
    @StringRes title: Int? = null,
    selected: Int = -1,
    respectDefaultWidth: Boolean = true,
) = awaitSelectItem(items, title?.right(), selected, respectDefaultWidth)

context(_: DialogState)
suspend fun awaitSelectItem(
    items: List<String>,
    title: Either<String, Int>?,
    selected: Int = -1,
    respectDefaultWidth: Boolean = true,
): Int = showNoButton(respectDefaultWidth) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        if (title != null) {
            Text(
                text = title.fold({ it }, { stringResource(id = it) }),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        FastScrollLazyColumn {
            itemsIndexed(items) { index, text ->
                CheckableItem(
                    text = text,
                    checked = index == selected,
                    modifier = Modifier.fillMaxWidth().clickable { resume(index) }.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

context(_: DialogState)
suspend inline fun awaitSelectAction(
    @StringRes title: Int? = null,
    selected: Int = -1,
    builder: ActionScope.() -> Unit,
): suspend () -> Unit {
    val (items, actions) = buildList { builder { action, that -> add(action to that) } }.unzip()
    val index = awaitSelectItem(items, title, selected)
    return actions[index]
}

context(_: DialogState)
suspend fun awaitSelectItemWithCheckBox(
    items: List<String>,
    @StringRes title: Int,
    @StringRes checkBoxText: Int,
    selected: Int = -1,
    initialChecked: Boolean = false,
): Pair<Int, Boolean> = showNoButton {
    var checked by remember { mutableStateOf(initialChecked) }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = stringResource(id = title),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        FastScrollLazyColumn {
            itemsIndexed(items) { index, text ->
                CheckableItem(
                    text = text,
                    checked = index == selected,
                    modifier = Modifier.fillMaxWidth().clickable { resume(index to checked) }.padding(horizontal = 8.dp),
                )
            }
        }
        LabeledCheckbox(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            checked = checked,
            onCheckedChange = { checked = !checked },
            label = stringResource(checkBoxText),
        )
    }
}

context(_: DialogState)
suspend fun awaitSelectItemWithIcon(
    items: List<Pair<ImageVector, Int>>,
    title: String,
): Int = showNoButton {
    LazyColumn {
        stickyHeader {
            Text(text = title, modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        }
        itemsIndexed(items) { index, (icon, text) ->
            ListItem(
                modifier = Modifier.clickable { resume(index) }.padding(horizontal = 8.dp),
                leadingContent = { Icon(imageVector = icon, contentDescription = null, tint = AlertDialogDefaults.iconContentColor) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                content = { Text(text = stringResource(id = text), style = MaterialTheme.typography.titleMedium) },
            )
        }
    }
}

/** Fork: the items in a circle you can spin, without the favorite note in the middle. */
context(_: DialogState)
suspend fun awaitSelectItemWithIconInCircle(
    items: List<Pair<ImageVector, String>>,
    @StringRes title: Int,
): Int = showNoButton(false) {
    Column {
        Text(text = stringResource(id = title), modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        CircularLayout(modifier = Modifier.fillMaxWidth().aspectRatio(1F)) {
            items.forEachIndexed { index, (icon, text) ->
                Column(
                    modifier = Modifier.clip(IconWithTextCorner).clickable { resume(index) }.fillMaxWidth(0.2F),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = AlertDialogDefaults.iconContentColor)
                    Text(
                        text = text,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 2,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckableItem(text: String, checked: Boolean, modifier: Modifier = Modifier) {
    val textStyle = MaterialTheme.typography.titleMedium
    val checkedColor = MaterialTheme.colorScheme.primary
    ListItem(
        modifier = modifier,
        trailingContent = checked.ifTrueThen {
            Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = checkedColor)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        content = {
            Text(
                text = text,
                style = if (checked) textStyle.copy(color = checkedColor) else textStyle,
            )
        },
    )
}

private val IconWithTextCorner = RoundedCornerShape(8.dp)
private val GlassDialogScrim = Color.Black.copy(alpha = 0.32f)
private val SheetFlingToClose = 1000.dp
private val GlassBottomSheetShape = RoundedCornerShape(topStart = GLASS_SHEET_CORNER, topEnd = GLASS_SHEET_CORNER)
private val DatePickerTitlePadding = PaddingValues(start = 24.dp, end = 12.dp, top = 16.dp)

val LocalGlobalDialogState = compositionLocalOf<DialogState> { error("CompositionLocal LocalDialogState not present!") }
