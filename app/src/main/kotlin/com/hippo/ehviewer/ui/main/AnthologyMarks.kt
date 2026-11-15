package com.hippo.ehviewer.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.ReplacementSpan
import android.text.style.URLSpan
import androidx.annotation.ColorInt
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import arrow.core.left
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryComment
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.TagNamespace
import com.hippo.ehviewer.anthology.AnthologyContents
import com.hippo.ehviewer.anthology.AnthologyMatchState
import com.hippo.ehviewer.anthology.AnthologyMatches
import com.hippo.ehviewer.anthology.MindControlHints
import com.hippo.ehviewer.anthology.TocEntry
import com.hippo.ehviewer.chapters.ChapterSource
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.sync.MihonSearch
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.screen.asDst
import com.hippo.ehviewer.ui.screen.asGalleryLinkDst
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.awaitSelectItem
import com.hippo.ehviewer.util.addTextToClipboard
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.math.roundToInt
import moe.tarsin.navigate

/** Fork: the links of the marks below, handled by [handleAnthologyLink]. */
const val ANTHOLOGY_LINK = "ehv-anthology://"

/**
 * Fork: what the comments of a gallery tell of its works (see anthology/), to mark on them: the contents list a comment
 * gives, the favorites found for its entries, and the pages comments say the mind control (催眠) work is at.
 */
class AnthologyMarks(
    val contents: AnthologyContents?,
    val state: AnthologyMatchState?,
    /** The pages said, by the comment saying them. */
    val hints: Map<Long, List<IntRange>>,
) {
    val entries get() = contents?.entries.orEmpty()
    private val matches = (state as? AnthologyMatchState.Done)?.result?.matches.orEmpty()

    fun favoritesOf(entry: TocEntry): List<ChapterSource> = matches[entry.key].orEmpty()

    /** The entries over [pages]. */
    fun entriesAt(pages: IntRange) = entries.indices.filter { i -> entries[i].first <= pages.last && entries[i].last >= pages.first }

    /** The entries of mind control: said so by a comment, or by their title. */
    val mindControl: Set<Int> = entries.indices.filter { i ->
        entries[i].title?.let(MindControlHints::isMindControlTitle) == true || hints.values.flatten().any { entries[i].first <= it.last && entries[i].last >= it.first }
    }.toSet()

    val favoritedCount get() = entries.count { favoritesOf(it).isNotEmpty() }
}

/** Fork: the marks of the [comments] of the gallery [gid] of [pages] pages; finds the favorites of its contents. */
@Composable
fun rememberAnthologyMarks(gid: Long, pages: Int, comments: List<GalleryComment>): AnthologyMarks {
    val contents = remember(gid, pages, comments) { AnthologyMatches.contentsOf(gid, pages, comments) }
    LaunchedEffect(contents) { contents?.let { AnthologyMatches.start(gid, it) } }
    val states by AnthologyMatches.states.collectAsState()
    val state = states[gid]
    val hints = remember(comments) { anthologyHints(comments) }
    return remember(contents, state, hints) { AnthologyMarks(contents, state, hints) }
}

/** Fork: the pages of the mind control work said by [comments], by the comment saying them. */
fun anthologyHints(comments: List<GalleryComment>): Map<Long, List<IntRange>> =
    comments.mapNotNull { c -> MindControlHints.pages(AnthologyMatches.textOf(c).first).takeIf { it.isNotEmpty() }?.let { c.id to it } }.toMap()

private class MarkColors(@ColorInt val background: Int, @ColorInt val content: Int)

private class Mark(val text: String, val colors: MarkColors, val url: String? = null)

/**
 * Fork: [text], the comment [comment] as shown, with the marks of [marks]: on the contents list, a line of how many
 * works, favorited and of mind control above it, the names of the artists as links, "♥ 已收藏" and "🌀 催眠" after the
 * entries; on a comment saying where the mind control work is, a line of that page and the entry there, below the
 * comment ([hintFirst]: above it, for the few lines of a comment on the gallery details). [original]: the comment is
 * shown as written, not translated (the contents list is marked only then).
 */
@Composable
fun AnthologyMarks.decorate(text: CharSequence, comment: GalleryComment, original: Boolean, hintFirst: Boolean = false): CharSequence {
    val contents = contents
    val pages = hints[comment.id]
    val isContents = contents != null && contents.commentId == comment.id && original
    if (!isContents && pages == null) return text
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val favorite = if (dark) MarkColors(0xFF2A4677.toInt(), 0xFFD6E3FF.toInt()) else MarkColors(0xFFD6E3FF.toInt(), 0xFF16336B.toInt())
    val mind = if (dark) MarkColors(0xFF5C2238.toInt(), 0xFFFFD9E3.toInt()) else MarkColors(0xFFFFD9E3.toInt(), 0xFF7B2949.toInt())
    val grey = MarkColors(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f).toArgb(), MaterialTheme.colorScheme.onSurfaceVariant.toArgb())
    val density = LocalDensity.current.density
    val favoritedText = stringResource(R.string.anthology_mark_favorited)
    val mindText = stringResource(R.string.anthology_mark_mind_control)
    val countText = stringResource(R.string.anthology_summary_count, entries.size)
    val favoritedCountText = stringResource(R.string.anthology_summary_favorited, favoritedCount)
    val mindCountText = stringResource(R.string.anthology_summary_mind_control, mindControl.size)
    val running = state as? AnthologyMatchState.Running
    val findingText = running?.let { stringResource(R.string.anthology_summary_finding, it.done, it.total) }
    val hintTexts = pages?.map { range ->
        val page = if (range.first == range.last) "${range.first}" else "${range.first}–${range.last}"
        stringResource(R.string.anthology_hint_page, page)
    }
    val readText = stringResource(R.string.anthology_hint_read)

    val builder = SpannableStringBuilder(text)
    // The contents as parsed must still be the text shown: the names where they were
    if (isContents && entries.all { e -> e.lineEnd <= text.length && e.names.all { text.substring(it.start, it.end) == it.text } }) {
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            val marks = buildList {
                if (favoritesOf(entry).isNotEmpty()) add(Mark(favoritedText, favorite, "${ANTHOLOGY_LINK}favorite/$i"))
                if (i in mindControl) add(Mark(mindText, mind))
            }
            builder.insertMarks(entry.lineEnd, marks, density, leading = true)
            entry.names.forEachIndexed { n, name -> builder.setSpan(URLSpan("${ANTHOLOGY_LINK}author/$i/$n"), name.start, name.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        val summary = buildList {
            add(Mark(countText, grey))
            if (favoritedCount > 0) add(Mark(favoritedCountText, favorite))
            if (mindControl.isNotEmpty()) add(Mark(mindCountText, mind))
            findingText?.let { add(Mark(it, grey)) }
        }
        builder.insert(0, "\n")
        builder.insertMarks(0, summary, density, leading = false)
    }
    if (pages != null && hintTexts != null) {
        val hint = SpannableStringBuilder()
        pages.forEachIndexed { index, range ->
            if (index > 0) hint.append('\n')
            val start = hint.length
            hint.append(hintTexts[index])
            val at = if (contents != null) entriesAt(range) else emptyList()
            if (at.isNotEmpty()) {
                hint.append(" → ")
                val linkStart = hint.length
                hint.append(at.joinToString("、") { i -> entries[i].describe() })
                hint.setSpan(URLSpan("${ANTHOLOGY_LINK}entry/${at.first()}"), linkStart, hint.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            hint.append("  ")
            val readStart = hint.length
            hint.append(readText)
            hint.setSpan(URLSpan("${ANTHOLOGY_LINK}read/${range.first}"), readStart, hint.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val pad = (10 * density).roundToInt()
            hint.setSpan(BlockSpan(mind.background, 10 * density), start, hint.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            hint.setSpan(LeadingMarginSpan.Standard(pad), start, hint.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            hint.setSpan(android.text.style.ForegroundColorSpan(mind.content), start, readStart, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (hintFirst) {
            hint.append('\n')
            builder.insert(0, hint)
        } else {
            // A paragraph of its own, for its background to take the whole width
            builder.append("\n")
            builder.append(hint)
        }
    }
    return builder
}

/** "049 池竜静留《覚性》": the entry in a line, its name as written first. */
private fun TocEntry.describe() = buildString {
    append(label)
    (names.firstOrNull { !it.latin } ?: names.firstOrNull())?.let { append(' ').append(it.text) }
    title?.let { append('《').append(it).append('》') }
}

/** Marks as chips at [at], after a space ([leading]) or with a space after each. */
private fun SpannableStringBuilder.insertMarks(at: Int, marks: List<Mark>, density: Float, leading: Boolean) {
    if (marks.isEmpty()) return
    val chips = SpannableStringBuilder()
    marks.forEachIndexed { index, mark ->
        if (leading || index > 0) chips.append(' ')
        val start = chips.length
        chips.append(mark.text)
        chips.setSpan(ChipSpan(mark.colors.background, mark.colors.content, density), start, chips.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        mark.url?.let { chips.setSpan(URLSpan(it), start, chips.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
    insert(at, chips)
}

/** A short text drawn as a rounded chip, a little smaller than the text around it. */
private class ChipSpan(@ColorInt private val background: Int, @ColorInt private val content: Int, density: Float) : ReplacementSpan() {
    private val padH = 7 * density
    private val padV = 2 * density
    private val radius = 9 * density

    private fun chipPaint(paint: Paint) = TextPaint(paint).apply {
        textSize *= SCALE
        isUnderlineText = false
    }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = (chipPaint(paint).measureText(text, start, end) + 2 * padH).roundToInt()

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val p = chipPaint(paint)
        val width = p.measureText(text, start, end)
        val outer = paint.fontMetrics
        val inner = p.fontMetrics
        // Centered on the text around it
        val center = y + (outer.ascent + outer.descent) / 2
        val half = (inner.descent - inner.ascent) / 2 + padV
        p.color = background
        canvas.drawRoundRect(RectF(x, center - half, x + width + 2 * padH, center + half), radius, radius, p)
        p.color = content
        canvas.drawText(text, start, end, x + padH, center - (inner.ascent + inner.descent) / 2, p)
    }

    companion object {
        private const val SCALE = 0.82f
    }
}

/** The background of a paragraph, the width of the text view, rounded at its first and last lines. */
private class BlockSpan(@ColorInt private val color: Int, private val radius: Float) : LineBackgroundSpan {
    private val path = Path()

    override fun drawBackground(canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, lineNumber: Int) {
        val spanned = text as? Spanned ?: return
        val spanStart = spanned.getSpanStart(this)
        val spanEnd = spanned.getSpanEnd(this)
        if (end <= spanStart || start >= spanEnd) return
        val first = start <= spanStart
        val last = end >= spanEnd || spanned.subSequence(end, spanEnd).isBlank()
        val t = if (first) radius else 0f
        val b = if (last) radius else 0f
        val old = paint.color
        paint.color = color
        path.reset()
        path.addRoundRect(RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()), floatArrayOf(t, t, t, t, b, b, b, b), Path.Direction.CW)
        canvas.drawPath(path, paint)
        paint.color = old
    }
}

/**
 * Fork: a tap on a link of the marks ([ANTHOLOGY_LINK]): the menu of an artist, the favorite of an entry, the contents
 * list at an entry ([showContents]), the reader at a page. False for any other link.
 */
context(_: DialogState, _: DestinationsNavigator)
suspend fun AnthologyMarks.handleAnthologyLink(
    url: String,
    context: Context,
    detail: GalleryDetail,
    showContents: suspend () -> Unit,
    onMihonMissing: suspend () -> Unit,
): Boolean {
    if (!url.startsWith(ANTHOLOGY_LINK)) return false
    val parts = url.removePrefix(ANTHOLOGY_LINK).split('/')
    val entry = parts.getOrNull(1)?.toIntOrNull()?.let { entries.getOrNull(it) }
    when (parts.firstOrNull()) {
        "author" -> if (entry != null) authorMenu(entry, parts.getOrNull(2)?.toIntOrNull() ?: 0, context, onMihonMissing)
        "favorite" -> entry?.let { favoritesOf(it).firstOrNull() }?.let { openFavorite(it) }
        "entry" -> showContents()
        "read" -> parts.getOrNull(1)?.toIntOrNull()?.let { navToReader(detail.galleryInfo, (it - 1).coerceIn(0, (detail.pages - 1).coerceAtLeast(0))) }
    }
    return true
}

context(_: DestinationsNavigator)
private fun openFavorite(source: ChapterSource) = navigate(BaseGalleryInfo(gid = source.gid, token = source.token, title = source.title, thumbKey = source.thumbKey).asDst())

/**
 * The menu of an artist of [entry], tapped on its name [nameIndex]: its favorite found, the artist tag (when the tag
 * database has one by either name), each name as a search, the name and the title as one (for the work alone), Mihon, copy.
 */
context(_: DialogState, _: DestinationsNavigator)
private suspend fun AnthologyMarks.authorMenu(entry: TocEntry, nameIndex: Int, context: Context, onMihonMissing: suspend () -> Unit) {
    val tapped = entry.names.getOrNull(nameIndex)?.text ?: return
    val names = (listOf(tapped) + entry.names.map { it.text }).distinctBy { it.lowercase() }
    val written = entry.names.firstOrNull { !it.latin }?.text ?: tapped
    val tag = names.firstNotNullOfOrNull { name ->
        EhTagDatabase.findTag(TagNamespace.Artist, name)?.let { "${TagNamespace.Artist.value}:$it" }
            ?: EhTagDatabase.findTag(TagNamespace.Group, name)?.let { "${TagNamespace.Group.value}:$it" }
    }
    val actions = ArrayList<Pair<String, suspend () -> Unit>>()
    favoritesOf(entry).forEach { source ->
        actions += context.getString(R.string.anthology_menu_open_favorite, source.title) to { openFavorite(source) }
    }
    tag?.let { actions += context.getString(R.string.anthology_menu_search_tag, it) to { navigate(ListUrlBuilder(mode = ListUrlBuilder.MODE_TAG, keyword = it).asGalleryLinkDst()) } }
    names.forEach { name ->
        actions += context.getString(R.string.anthology_menu_search_name, name) to { navigate(ListUrlBuilder(keyword = name).asGalleryLinkDst()) }
    }
    entry.title?.let { title ->
        val query = "$written $title"
        actions += context.getString(R.string.anthology_menu_search_work, query) to { navigate(ListUrlBuilder(keyword = query).asGalleryLinkDst()) }
    }
    actions += context.getString(R.string.search_in_mihon_query, written) to { if (!MihonSearch.search(context, written)) onMihonMissing() }
    actions += context.getString(R.string.anthology_menu_copy, tapped) to { with(context) { addTextToClipboard(tapped) } }
    val header = context.getString(R.string.anthology_menu_title, entry.first, entry.names.joinToString(" / ") { it.text }) +
        (entry.title?.let { "\n《$it》" } ?: "")
    val index = awaitSelectItem(actions.map { it.first }, header.left())
    actions[index].second()
}
