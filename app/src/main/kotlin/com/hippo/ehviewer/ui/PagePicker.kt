package com.hippo.ehviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryDetail
import com.hippo.ehviewer.chapters.ChapterBlock
import com.hippo.ehviewer.chapters.ChapterCompare
import com.hippo.ehviewer.chapters.ChapterCompareProgress
import com.hippo.ehviewer.chapters.ChapterCompareState
import com.hippo.ehviewer.chapters.ChapterTitles
import com.hippo.ehviewer.chapters.UncomparedChapter
import com.hippo.ehviewer.download.PageRange
import com.hippo.ehviewer.download.PageRanges
import com.hippo.ehviewer.ui.settings.SyncLinearProgress
import com.hippo.ehviewer.ui.settings.SyncProgressLine
import com.hippo.ehviewer.ui.settings.SyncProgressLineText

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
            ChapterSection(detail, picked = picked, onPick = ::set)
        }
    }
    Column(modifier = Modifier.navigationBarsPadding().imePadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
        RangeInput(pages = pages, onAdd = { set(it, true) })
        if (runs.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                runs.forEach { run ->
                    val text = run.toString().replace("-", "–")
                    InputChip(
                        selected = true,
                        onClick = { set(run.first..run.last!!, false) },
                        label = { Text(text = text) },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.download_partial_remove_range, text)) },
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
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            OutlinedButton(onClick = onCancel, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                Text(text = stringResource(android.R.string.cancel))
            }
            Button(onClick = { onDownload(runs) }, enabled = count > 0, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.download_partial_download, count))
            }
        }
    }
}

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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.download_partial_from))
        OutlinedTextField(
            state = from,
            keyboardOptions = numbers,
            lineLimits = TextFieldLineLimits.SingleLine,
            isError = from.text.isNotEmpty() && first == null,
            modifier = Modifier.width(80.dp),
        )
        Text(text = stringResource(R.string.download_partial_to))
        OutlinedTextField(
            state = to,
            keyboardOptions = numbers,
            lineLimits = TextFieldLineLimits.SingleLine,
            isError = lastText.isNotEmpty() && last == null,
            modifier = Modifier.width(80.dp),
        )
        val suffix = stringResource(R.string.download_partial_suffix)
        if (suffix.isNotBlank()) Text(text = suffix)
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = {
                onAdd(minOf(first!!, last!!)..maxOf(first, last))
                from.setTextAndPlaceCursorAtEnd("")
                to.setTextAndPlaceCursorAtEnd("")
            },
            enabled = valid,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(text = "+ " + stringResource(R.string.download_partial_add))
        }
    }
}

/** The compare of the tankoubon [detail] with the favorites: its progress, then its blocks to tick. */
@Composable
private fun ChapterSection(detail: GalleryDetail, picked: BooleanArray, onPick: (IntRange, Boolean) -> Unit) {
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
                BlocksBar(pages, progress.blocks, picked, pending = true)
                progress.blocks.filter { it.source != null }.forEach { BlockRow(it, picked, onPick) }
            }
            Text(
                text = stringResource(R.string.chapter_compare_pending),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        is ChapterCompareState.Done -> {
            val result = state.result
            val summary = if (result.favoritedCount == 0) {
                stringResource(R.string.chapter_compare_none)
            } else {
                stringResource(R.string.chapter_compare_summary, result.favoritedCount, result.favoritedPages, result.pages - result.favoritedPages)
            }
            Text(text = summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (result.favoritedCount > 0) {
                BlocksBar(pages, result.blocks, picked, pending = false)
                result.blocks.forEach { BlockRow(it, picked, onPick) }
            }
            result.uncompared.forEach { UncomparedRow(it) }
            if (result.wnacgBlocked) {
                var checking by remember { mutableStateOf(false) }
                Text(
                    text = stringResource(R.string.chapter_wnacg_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                AssistChip(onClick = { checking = true }, label = { Text(stringResource(R.string.chapter_wnacg_open)) })
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (result.favoritedCount > 0) {
                    AssistChip(
                        onClick = { result.blocks.filter { it.source == null }.forEach { onPick(it.pages, true) } },
                        label = { Text(stringResource(R.string.chapter_pick_unfavorited)) },
                    )
                    AssistChip(
                        onClick = { result.blocks.filter { it.source != null }.forEach { onPick(it.pages, true) } },
                        label = { Text(stringResource(R.string.chapter_pick_favorited)) },
                    )
                }
                AssistChip(onClick = { onPick(1..pages, false) }, label = { Text(stringResource(R.string.chapter_pick_clear)) })
                AssistChip(onClick = { ChapterCompare.start(detail, force = true) }, label = { Text(stringResource(R.string.chapter_compare_again)) })
            }
        }
        is ChapterCompareState.Failed -> {
            Text(text = stringResource(R.string.chapter_compare_failed, state.message), color = MaterialTheme.colorScheme.error)
            AssistChip(onClick = { ChapterCompare.start(detail, force = true) }, label = { Text(stringResource(R.string.chapter_compare_again)) })
        }
        null -> Unit
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
}

/** The progress as the one of the sync below the search bar: a line of text, the numbers on the right, and a bar. */
@Composable
private fun CompareProgressCard(progress: ChapterCompareProgress) {
    val label = when (progress.stage) {
        ChapterCompareProgress.Stage.Finding -> stringResource(R.string.chapter_compare_finding)
        ChapterCompareProgress.Stage.Book -> stringResource(R.string.chapter_compare_book)
        ChapterCompareProgress.Stage.Chapter -> stringResource(R.string.chapter_compare_chapter, progress.index, progress.count, progress.title.orEmpty())
    }
    val fraction = progress.fraction
    val numbers = listOfNotNull(
        "${progress.done}/${progress.total}".takeIf { progress.total > 0 },
        fraction?.let { "${(it * 100).toInt()}%" },
    ).joinToString("  ")
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f), MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SyncProgressLineText(
            line = SyncProgressLine(label = label, numbers = numbers, full = if (numbers.isEmpty()) label else "$label  $numbers"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        SyncLinearProgress(fraction = fraction, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

/** The book as a bar of its blocks; the picked ones outlined. While [pending], the pages not found yet are lighter. */
@Composable
private fun BlocksBar(pages: Int, blocks: List<ChapterBlock>, picked: BooleanArray, pending: Boolean) {
    val grey = MaterialTheme.colorScheme.onSurface.copy(alpha = if (pending) 0.08f else 0.2f)
    val outline = MaterialTheme.colorScheme.onSurface
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().height(28.dp).padding(vertical = 2.dp),
    ) {
        blocks.forEach { block ->
            val on = isPicked(block, picked)
            Box(
                Modifier.weight(block.size.toFloat() / pages.coerceAtLeast(1)).fillMaxHeight()
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
}

private fun isPicked(block: ChapterBlock, picked: BooleanArray) = block.pages.all { it - 1 in picked.indices && picked[it - 1] }

@Composable
private fun BlockRow(block: ChapterBlock, picked: BooleanArray, onPick: (IntRange, Boolean) -> Unit) {
    val on = isPicked(block, picked)
    val source = block.source
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onPick(block.pages, !on) }.padding(vertical = 2.dp),
    ) {
        Checkbox(checked = on, onCheckedChange = { onPick(block.pages, it) })
        Box(
            Modifier.size(width = 4.dp, height = 36.dp)
                .background(if (source != null) FavoritedBlue else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), RoundedCornerShape(2.dp)),
        )
        Text(
            text = if (block.size == 1) stringResource(R.string.chapter_block_page, block.first) else stringResource(R.string.chapter_block_pages, block.first, block.last),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp).width(96.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            if (source != null) {
                Text(text = source.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(text = stringResource(R.string.chapter_block_favorited), style = MaterialTheme.typography.labelSmall, color = FavoritedBlue)
            } else {
                Text(text = stringResource(R.string.chapter_block_unfavorited), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
