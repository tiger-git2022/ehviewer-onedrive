package com.hippo.ehviewer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.BitmapImage
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.model.V2GalleryPreview
import com.ehviewer.core.ui.component.FloatingGlass
import com.ehviewer.core.ui.component.GLASS_TILE_TINT
import com.ehviewer.core.ui.component.GlassStyle
import com.ehviewer.core.ui.component.LocalTileGlass
import com.ehviewer.core.ui.component.floatingGlass
import com.hippo.ehviewer.chapters.ChapterBlock
import com.hippo.ehviewer.chapters.ChapterBlocks
import com.hippo.ehviewer.chapters.ChapterCompare
import com.hippo.ehviewer.chapters.ChapterCompareProgress
import com.hippo.ehviewer.chapters.ChapterCompareState
import com.hippo.ehviewer.chapters.ChapterSource
import com.hippo.ehviewer.chapters.ChapterTitles
import com.hippo.ehviewer.chapters.UncomparedChapter
import com.hippo.ehviewer.chapters.WholeFavorite
import com.hippo.ehviewer.download.PageRange
import com.hippo.ehviewer.download.PageRanges
import com.hippo.ehviewer.ui.main.requestOf
import com.hippo.ehviewer.ui.settings.SyncLinearProgress

/** Fork: blue for the pages favorited as chapters, see [ChapterCompare]. */
private val FavoritedBlue = Color(0xFF4A86E8)

/**
 * Fork: the content of the sheet of "Download some pages": the ranges picked as chips, a range added at a time from
 * two numbers; for a tankoubon ([detail] not null) the blocks of its compare with the favorites, ticked to pick them.
 */
@Composable
fun ColumnScope.PagePicker(
    pages: Int,
    initial: String,
    detail: GalleryDetail?,
    onCancel: () -> Unit,
    onDownload: (List<PageRange>) -> Unit,
    onOpen: (ChapterSource, String) -> Unit,
) {
    var picked by remember {
        val array = BooleanArray(pages)
        PageRanges.parse(initial)?.let { PageRanges.indices(it, pages) }?.forEach { array[it] = true }
        mutableStateOf(array)
    }
    fun set(range: IntRange, on: Boolean) {
        val copy = picked.copyOf()
        for (page in range) if (page in 1..pages) copy[page - 1] = on
        picked = copy
    }
    val runs = PageRanges.runs(picked)
    val count = picked.count { it }

    Column(
        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
    ) {
        Text(text = stringResource(R.string.download_partial), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        if (detail != null) {
            ChapterSection(detail, picked = picked, onPick = ::set, onOpen = { onOpen(it, PageRanges.format(runs)) })
        }
        Spacer(Modifier.height(8.dp))
    }
    // Kept apart from the list above it, and of the same height whatever is picked: the sheet does not jump
    Column(modifier = Modifier.navigationBarsPadding().imePadding().padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 16.dp)) {
        RangeInput(pages = pages, onAdd = { set(it, true) })
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(36.dp).horizontalScroll(rememberScrollState()),
        ) {
            if (runs.isEmpty()) {
                Text(
                    text = stringResource(R.string.download_partial_no_ranges),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }
            runs.forEach { run ->
                val text = run.toString().replace("-", "–")
                // Over liquid glass a glass chip like the selected ones of the search, a light in the glass
                val glass = LocalTileGlass.current
                // As tall as the chip: the glass is the chip, not the touch area around it
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (glass != null) 0.dp else LocalMinimumInteractiveComponentSize.current) {
                    InputChip(
                        selected = true,
                        onClick = { set(run.first..run.last!!, false) },
                        label = { Text(text = text, maxLines = 1) },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.download_partial_remove_range, text)) },
                        colors = if (glass != null) {
                            InputChipDefaults.inputChipColors(
                                selectedContainerColor = glassSelectedColor(),
                                selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                                selectedTrailingIconColor = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            InputChipDefaults.inputChipColors()
                        },
                        modifier = if (glass != null) Modifier.sheetGlass(glass, InputChipDefaults.shape) else Modifier,
                    )
                }
            }
        }
        Text(
            text = when (count) {
                0 -> stringResource(R.string.download_partial_none, pages)
                pages -> stringResource(R.string.download_partial_all, pages)
                else -> stringResource(R.string.download_partial_count, count, pages)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        val glass = LocalTileGlass.current
        if (glass != null) {
            // Over liquid glass, glass buttons like the floating ones: no edge, the download one blue glass. As tall as
            // the buttons (no 48dp touch area around them), so the glass is the button
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    OutlinedButton(
                        onClick = onCancel,
                        border = null,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                        shapes = GlassButtonShapes,
                        modifier = Modifier.weight(1f).sheetGlass(glass, CircleShape),
                    ) {
                        Text(text = stringResource(android.R.string.cancel))
                    }
                    Button(
                        onClick = { onDownload(runs) },
                        enabled = count > 0,
                        shapes = GlassButtonShapes,
                        colors = ButtonDefaults.buttonColors(containerColor = FavoritedBlue.copy(alpha = 0.8f), contentColor = Color.White),
                        modifier = Modifier.weight(1f).sheetGlass(glass, CircleShape),
                    ) {
                        Text(text = stringResource(R.string.download_partial_download, count))
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                // By day a grey button, without the dark edge
                val light = isLightSheet()
                OutlinedButton(
                    onClick = onCancel,
                    border = if (light) null else edgeBorder(),
                    colors = if (light) ButtonDefaults.outlinedButtonColors(containerColor = lightFill()) else ButtonDefaults.outlinedButtonColors(),
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
                Button(
                    onClick = { onDownload(runs) },
                    enabled = count > 0,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = FavoritedBlue, contentColor = Color.White),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(R.string.download_partial_download, count))
                }
            }
        }
    }
}

/** The border of the buttons and the chips: that of the floating buttons, light in dark themes and a pale grey in light ones. */
@Composable
private fun edgeBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)

/** The buttons as glass keep their pill shape when pressed: the glass under them does not follow a change of shape. */
private val GlassButtonShapes = ButtonShapes(CircleShape, CircleShape)

/** A button as glass over the glass of the sheet, see EhModalBottomSheet: a clear glass without an edge, like the floating buttons. */
@Composable
private fun Modifier.sheetGlass(glass: FloatingGlass, shape: Shape) =
    floatingGlass(glass, shape, MaterialTheme.colorScheme.surfaceBright.copy(alpha = GLASS_TILE_TINT), GlassStyle.Tile)

/** An assist chip of the sheet: over liquid glass a glass one without an edge, else outlined. */
@Composable
private fun SheetChip(onClick: () -> Unit, label: String) {
    val glass = LocalTileGlass.current
    if (glass == null) {
        AssistChip(border = edgeBorder(), onClick = onClick, label = { Text(label) })
        return
    }
    // The glass on the chip, not on the 48dp touch area around it, which still reaches it
    Box(Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            AssistChip(
                onClick = onClick,
                label = { Text(label) },
                border = null,
                colors = AssistChipDefaults.assistChipColors(containerColor = Color.Transparent),
                modifier = Modifier.sheetGlass(glass, AssistChipDefaults.shape),
            )
        }
    }
}

/** Whether the sheet is light: by day the cards, the tags and the cancel button are grey fills instead of outlined. */
@Composable
private fun isLightSheet() = MaterialTheme.colorScheme.surface.luminance() >= 0.5f

/** The grey fill of the cards, the tags and the cancel button by day. */
@Composable
private fun lightFill() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)

/** "From [ ] to [ ]" and a button adding those pages; an empty "to" adds one page. */
@Composable
private fun RangeInput(pages: Int, onAdd: (IntRange) -> Unit) {
    val from = rememberTextFieldState()
    val to = rememberTextFieldState()
    val first = from.text.toString().toIntOrNull()?.takeIf { it in 1..pages }
    val lastText = to.text.toString()
    val last = if (lastText.isBlank()) first else lastText.toIntOrNull()?.takeIf { it in 1..pages }
    val valid = first != null && last != null
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
    // Lower than the 56dp of a text field
    val fieldPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.download_partial_from))
        OutlinedTextField(
            state = from,
            keyboardOptions = numbers,
            lineLimits = TextFieldLineLimits.SingleLine,
            isError = from.text.isNotEmpty() && first == null,
            contentPadding = fieldPadding,
            modifier = Modifier.weight(1f).height(44.dp),
        )
        Text(text = stringResource(R.string.download_partial_to))
        OutlinedTextField(
            state = to,
            keyboardOptions = numbers,
            lineLimits = TextFieldLineLimits.SingleLine,
            isError = lastText.isNotEmpty() && last == null,
            contentPadding = fieldPadding,
            modifier = Modifier.weight(1f).height(44.dp),
        )
        val suffix = stringResource(R.string.download_partial_suffix)
        if (suffix.isNotBlank()) Text(text = suffix)
        // Over liquid glass a glass button like the others of the sheet, as tall as the button
        val glass = LocalTileGlass.current
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (glass != null) 0.dp else LocalMinimumInteractiveComponentSize.current) {
            TextButton(
                onClick = {
                    onAdd(minOf(first!!, last!!)..maxOf(first, last))
                    from.setTextAndPlaceCursorAtEnd("")
                    to.setTextAndPlaceCursorAtEnd("")
                },
                enabled = valid,
                shapes = if (glass != null) GlassButtonShapes else ButtonDefaults.shapes(),
                modifier = if (glass != null) Modifier.sheetGlass(glass, CircleShape) else Modifier,
            ) {
                Text(text = "+ " + stringResource(R.string.download_partial_add), maxLines = 1, softWrap = false)
            }
        }
    }
}

/** The compare of the tankoubon [detail] with the favorites: its progress, then its blocks to tick. */
@Composable
private fun ChapterSection(detail: GalleryDetail, picked: BooleanArray, onPick: (IntRange, Boolean) -> Unit, onOpen: (ChapterSource) -> Unit) {
    val gid = detail.gid
    val pages = detail.pages
    LaunchedEffect(gid) {
        ChapterCompare.load(gid)
        ChapterCompare.start(detail)
    }
    val states by ChapterCompare.states.collectAsState()
    when (val state = states[gid]) {
        is ChapterCompareState.Running -> {
            val progress = state.progress
            CompareProgressCard(progress)
            if (progress.blocks.isNotEmpty()) {
                BlocksBar(pages, progress.blocks, picked, pending = true, onPick = onPick)
                progress.blocks.filter { it.source != null }.forEach { BlockCard(detail, it, picked, onPick, onOpen) }
            }
            Text(
                text = stringResource(R.string.chapter_compare_pending),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        is ChapterCompareState.Done -> {
            val result = state.result
            val summary = if (result.favoritedCount == 0) {
                if (result.compared > 0) stringResource(R.string.chapter_compare_none_matched, result.compared) else stringResource(R.string.chapter_compare_none)
            } else {
                stringResource(R.string.chapter_compare_summary, result.favoritedCount, result.favoritedPages, result.pages - result.favoritedPages)
            }
            Text(text = summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (result.favoritedCount > 0) {
                BlocksBar(pages, result.blocks, picked, pending = false, onPick = onPick)
                result.blocks.forEach { BlockCard(detail, it, picked, onPick, onOpen) }
            }
            if (result.whole.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.chapter_whole_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                result.whole.forEach { WholeRow(it, onOpen) }
            }
            // Often many (favorites gone from E-H): folded, last
            if (result.uncompared.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clip(MaterialTheme.shapes.small).clickable { open = !open }.padding(vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chapter_uncompared_title, result.uncompared.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (open) result.uncompared.forEach { UncomparedRow(it) }
            }
            if (result.wnacgBlocked) {
                var checking by remember { mutableStateOf(false) }
                Text(
                    text = stringResource(R.string.chapter_wnacg_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                SheetChip(onClick = { checking = true }, label = stringResource(R.string.chapter_wnacg_open))
                if (checking) {
                    val title = result.uncompared.firstOrNull { it.gone }?.source?.title?.let(ChapterTitles::core)
                    WnacgCheck(
                        url = wnacgCheckUrl(title),
                        onCancel = { checking = false },
                        onDone = {
                            checking = false
                            ChapterCompare.start(detail, force = true)
                        },
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                SheetChip(onClick = { onPick(1..pages, false) }, label = stringResource(R.string.chapter_pick_clear))
                SheetChip(onClick = { ChapterCompare.start(detail, force = true) }, label = stringResource(R.string.chapter_compare_again))
            }
        }
        is ChapterCompareState.Failed -> {
            Text(text = stringResource(R.string.chapter_compare_failed, state.message), color = MaterialTheme.colorScheme.error)
            SheetChip(onClick = { ChapterCompare.start(detail, force = true) }, label = stringResource(R.string.chapter_compare_again))
        }
        null -> Unit
    }
}

/**
 * The progress as the one of the sync below the search bar: what is done and the share of all the thumbnails, a bar,
 * then each gallery read now with its thumbnails, the title wrapping rather than cut.
 */
@Composable
private fun CompareProgressCard(progress: ChapterCompareProgress) {
    val label = when (progress.stage) {
        ChapterCompareProgress.Stage.Finding -> stringResource(R.string.chapter_compare_finding)
        ChapterCompareProgress.Stage.Comparing -> stringResource(R.string.chapter_compare_chapter, progress.finished, progress.count)
    }
    val fraction = progress.fraction
    val small = MaterialTheme.typography.labelMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f), MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = small, color = color, modifier = Modifier.weight(1f))
            fraction?.let { Text(text = "${(it * 100).toInt()}%", style = small, color = color, maxLines = 1, modifier = Modifier.padding(start = 8.dp)) }
        }
        SyncLinearProgress(fraction = fraction, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        progress.active.forEach { gallery ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 6.dp)) {
                val title = if (gallery.book) stringResource(R.string.chapter_compare_book, gallery.title) else gallery.title
                Text(text = title, style = small, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(text = "${gallery.done}/${gallery.total}", style = small, color = color, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * The book as a bar of its blocks, a tap on one picking it or not; the picked ones outlined. While [pending], the pages
 * not found yet are lighter.
 */
@Composable
private fun BlocksBar(pages: Int, blocks: List<ChapterBlock>, picked: BooleanArray, pending: Boolean, onPick: (IntRange, Boolean) -> Unit) {
    val grey = MaterialTheme.colorScheme.onSurface.copy(alpha = if (pending) 0.08f else 0.2f)
    val outline = MaterialTheme.colorScheme.onSurface
    val segments = remember(pages, blocks) { ChapterBlocks.segments(pages, blocks) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().height(28.dp).padding(vertical = 2.dp),
    ) {
        segments.forEach { block ->
            val on = isPicked(block, picked)
            Box(
                Modifier.weight(block.size.toFloat() / pages.coerceAtLeast(1)).fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onPick(block.pages, !on) }
                    .background(if (block.source != null) FavoritedBlue else grey, RoundedCornerShape(4.dp))
                    .then(if (on) Modifier.border(2.dp, outline, RoundedCornerShape(4.dp)) else Modifier),
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(1, pages / 4, pages / 2, pages * 3 / 4, pages).distinct().forEach {
            Text(text = "$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text(
        text = stringResource(R.string.chapter_bar_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

private fun isPicked(block: ChapterBlock, picked: BooleanArray) = block.pages.all { it - 1 in picked.indices && picked[it - 1] }

/**
 * A block as a card, a tap picking it or not: the cover of the favorite it is (the page of the book when none is), its
 * pages and whether it is favorited on the first line, the title of the favorite below on up to three lines.
 */
@Composable
private fun BlockCard(detail: GalleryDetail, block: ChapterBlock, picked: BooleanArray, onPick: (IntRange, Boolean) -> Unit, onOpen: (ChapterSource) -> Unit) {
    val on = isPicked(block, picked)
    val source = block.source
    val shape = RoundedCornerShape(12.dp)
    // By day a card not picked is a grey fill without an edge
    val light = isLightSheet()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(shape)
            .then(
                when {
                    on -> Modifier.background(FavoritedBlue.copy(alpha = 0.18f)).border(1.dp, FavoritedBlue.copy(alpha = 0.7f), shape)
                    light -> Modifier.background(lightFill())
                    else -> Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                },
            )
            .clickable { onPick(block.pages, !on) }
            .padding(end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        // The whole card picks it: the checkbox needs no touch area of its own, the room goes to the text
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Checkbox(
                checked = on,
                onCheckedChange = { onPick(block.pages, it) },
                colors = CheckboxDefaults.colors(checkedColor = FavoritedBlue, checkmarkColor = Color.White, uncheckedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (light) 0.45f else 1f)),
                modifier = Modifier.padding(start = 10.dp, end = 10.dp),
            )
        }
        val thumb = Modifier.size(width = 56.dp, height = 79.dp).clip(RoundedCornerShape(6.dp))
        if (source != null) {
            val info = remember(source) { BaseGalleryInfo(gid = source.gid, token = source.token, title = source.title, thumbKey = source.thumbKey) }
            if (source.thumbKey != null) {
                AsyncImage(model = requestOf(info), contentDescription = null, contentScale = ContentScale.Crop, modifier = thumb)
            } else {
                Box(thumb.background(FavoritedBlue.copy(alpha = 0.4f)))
            }
        } else {
            val preview by produceState<GalleryPreview?>(null, detail.gid, block.first) { value = ChapterCompare.previewOf(detail, block.first) }
            preview?.let { PreviewThumb(it, thumb) } ?: Box(thumb.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)))
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            // Each piece whole: smaller type to fit on a line, the tag on the next one when they still do not
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (block.size == 1) stringResource(R.string.chapter_block_page, block.first) else stringResource(R.string.chapter_block_pages, block.first, block.last),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                )
                if (block.size > 1) {
                    Text(
                        text = stringResource(R.string.chapter_block_size, block.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                if (source != null) {
                    Text(
                        text = stringResource(R.string.chapter_block_favorited),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.background(FavoritedBlue, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.chapter_block_unfavorited),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = if (light) {
                            Modifier.background(lightFill(), RoundedCornerShape(8.dp))
                        } else {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        }.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            if (source != null) {
                Text(
                    text = source.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            // The same chapter favorited again (in another language), a tap opening it
            block.sources.drop(1).forEach { other ->
                Text(
                    text = "· " + other.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp).clip(MaterialTheme.shapes.extraSmall).clickable { onOpen(other) },
                )
            }
        }
        // Opens the favorite to check it; the sheet comes back as it was
        if (source != null) {
            IconButton(onClick = { onOpen(source) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.chapter_open_favorite),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The preview of a page, a part of a sheet of previews cut out. */
@Composable
private fun PreviewThumb(preview: GalleryPreview, modifier: Modifier) {
    val painter = rememberAsyncImagePainter(
        model = requestOf(preview),
        transform = {
            val bitmap = ((it as? AsyncImagePainter.State.Success)?.result?.image as? BitmapImage)?.bitmap
            if (bitmap != null && preview is V2GalleryPreview) {
                it.copy(painter = BitmapPainter(bitmap.asImageBitmap(), IntOffset(preview.offsetX, 0), IntSize(preview.clipWidth - 1, preview.clipHeight - 1)))
            } else {
                it
            }
        },
    )
    Image(painter = painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

/** A favorite as long as most of the book, not compared: its cover, its title on up to three lines; a tap opens it. */
@Composable
private fun WholeRow(item: WholeFavorite, onOpen: (ChapterSource) -> Unit) {
    val info = remember(item) {
        BaseGalleryInfo(gid = item.source.gid, token = item.source.token, title = item.source.title, thumbKey = item.thumbKey, pages = item.pages)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onOpen(item.source) }.padding(vertical = 6.dp),
    ) {
        if (item.thumbKey != null) {
            AsyncImage(
                model = requestOf(info),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 52.dp, height = 74.dp).clip(MaterialTheme.shapes.small),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = if (item.thumbKey != null) 12.dp else 0.dp)) {
            Text(text = item.source.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                text = stringResource(R.string.chapter_whole_pages, item.pages),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UncomparedRow(item: UncomparedChapter) {
    val text = if (item.gone) {
        stringResource(R.string.chapter_uncompared_gone, item.source.title) +
            (item.coverPage?.let { "\n" + stringResource(R.string.chapter_uncompared_cover, it) } ?: "")
    } else {
        stringResource(R.string.chapter_uncompared_error, item.source.title)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}
