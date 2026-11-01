package com.hippo.ehviewer.ui.main

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import java.util.WeakHashMap

/*
 * Fork: E-Hentai makes the preview of a page from the first frame of its image. Some animated images start with a
 * fully transparent frame (e.g. the animated WebP pages of gallery 4292915), so their preview is empty: the card
 * looks black, as if it never loaded, and the website shows nothing there either. Such a card shows a note instead.
 */

// Alpha below this counts as transparent (lossy WebP leaves a little noise)
private const val VISIBLE_ALPHA = 16

// For each column of a decoded preview image, the first row with a visible pixel (Int.MAX_VALUE: none).
// The 20 previews in one V2 sprite share one bitmap, so it is read once.
private val firstVisibleRows = WeakHashMap<Bitmap, IntArray>()

/** Whether the part of [bitmap] from column [x], [width] wide and [height] high from the top, has nothing visible. */
fun isBlankPreview(bitmap: Bitmap, x: Int, width: Int, height: Int): Boolean {
    if (!bitmap.hasAlpha()) return false
    // The memory cache may have recycled the bitmap meanwhile: then show the preview as it is
    val rows = runCatching {
        synchronized(firstVisibleRows) { firstVisibleRows.getOrPut(bitmap) { scanFirstVisibleRows(bitmap) } }
    }.getOrElse { return false }
    return isBlankArea(rows, x, width, height)
}

internal fun isBlankArea(firstVisibleRows: IntArray, x: Int, width: Int, height: Int): Boolean {
    val start = x.coerceAtLeast(0)
    val end = minOf(x + width, firstVisibleRows.size)
    if (start >= end) return false
    return (start until end).none { firstVisibleRows[it] < height }
}

/** [readRow] puts the ARGB pixels of row y into the array. */
internal fun firstVisibleRows(width: Int, height: Int, readRow: (Int, IntArray) -> Unit): IntArray {
    val rows = IntArray(width) { Int.MAX_VALUE }
    val line = IntArray(width)
    for (y in 0 until height) {
        readRow(y, line)
        for (x in 0 until width) {
            if (rows[x] == Int.MAX_VALUE && line[x] ushr 24 >= VISIBLE_ALPHA) rows[x] = y
        }
    }
    return rows
}

private fun scanFirstVisibleRows(bitmap: Bitmap): IntArray {
    // Pixels of a hardware bitmap cannot be read, read a copy
    val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    // Could not copy: say every column is visible, so the preview is shown as it is
    readable ?: return IntArray(bitmap.width)
    try {
        val width = readable.width
        return firstVisibleRows(width, readable.height) { y, line ->
            readable.getPixels(line, 0, width, 0, y, width, 1)
        }
    } finally {
        if (readable !== bitmap) readable.recycle()
    }
}

@Composable
fun BlankPreviewNote(modifier: Modifier = Modifier) = Column(
    modifier = modifier.fillMaxSize().padding(6.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Icon(
        imageVector = Icons.Outlined.HideImage,
        contentDescription = null,
        modifier = Modifier.size(28.dp),
        tint = color,
    )
    Text(
        text = stringResource(R.string.preview_blank),
        modifier = Modifier.padding(top = 6.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
    )
}
