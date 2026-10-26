package com.hippo.ehviewer.restore

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.CrystalCard

// Fork: how a gallery restored from PandaChika shows, see RestoredGalleries

/**
 * The badge on the cover of [gid] when restored, or found by a search on PandaChika: a panda in a triangle on the
 * corner, the top end one, or the top start one when not [atEnd]. A white triangle and a black panda in the light theme, the other way round in the dark one.
 */
@Composable
fun RestoredBadge(gid: Long, modifier: Modifier = Modifier, atEnd: Boolean = true) {
    val badged by RestoredGalleries.badged.collectAsState()
    if (gid in badged) {
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        Box(modifier.size(30.dp).background(if (dark) DarkCorner else LightCorner, if (atEnd) EndCorner else StartCorner)) {
            Image(
                imageVector = Panda,
                contentDescription = stringResource(R.string.pandachaika_badge),
                colorFilter = ColorFilter.tint(if (dark) Color.White else PandaBlack),
                modifier = Modifier.align(if (atEnd) Alignment.TopEnd else Alignment.TopStart).padding(2.dp).size(13.dp),
            )
        }
    }
}

private val LightCorner = Color(0x80FFFFFF)
private val DarkCorner = Color(0x80262626)
private val PandaBlack = Color(0xFF1E1E1E)

// Triangles with their right angle on the top end corner, or the top start one
private val EndCorner = GenericShape { size, direction ->
    if (direction == LayoutDirection.Ltr) corner(size.width, 0f, size) else corner(0f, size.width, size)
}
private val StartCorner = GenericShape { size, direction ->
    if (direction == LayoutDirection.Ltr) corner(0f, size.width, size) else corner(size.width, 0f, size)
}

// The right angle at x = [right] on the top edge, the other ends at x = [other] on the top edge and below the angle
private fun Path.corner(right: Float, other: Float, size: Size) {
    moveTo(other, 0f)
    lineTo(right, 0f)
    lineTo(right, size.height)
    close()
}

// A panda's head in one color (tinted), the eye patches and the nose cut out
private val Panda by lazy {
    val color = SolidColor(Color.Black)
    ImageVector.Builder("Panda", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(ovals(listOf(Oval(5.6f, 5.8f, 3.2f, 3.2f), Oval(18.4f, 5.8f, 3.2f, 3.2f))), fill = color)
        addPath(
            ovals(listOf(Oval(12f, 13.6f, 9.6f, 8.4f), Oval(8.4f, 12.9f, 2.1f, 2.6f), Oval(15.6f, 12.9f, 2.1f, 2.6f), Oval(12f, 17.1f, 1.4f, 0.95f))),
            pathFillType = PathFillType.EvenOdd,
            fill = color,
        )
        addPath(ovals(listOf(Oval(8.8f, 12.5f, 0.9f, 0.9f), Oval(15.2f, 12.5f, 0.9f, 0.9f))), fill = color)
    }.build()
}

private data class Oval(val cx: Float, val cy: Float, val rx: Float, val ry: Float)

private fun ovals(ovals: List<Oval>) = PathBuilder().apply {
    ovals.forEach { (cx, cy, rx, ry) ->
        moveTo(cx - rx, cy)
        arcToRelative(rx, ry, 0f, true, true, rx * 2, 0f)
        arcToRelative(rx, ry, 0f, true, true, -rx * 2, 0f)
        close()
    }
}.nodes

/** In the details of a restored gallery, in place of the previews: where they come from. */
fun LazyGridScope.restoredNote() = item(
    key = "pandachaika",
    span = { GridItemSpan(maxLineSpan) },
    contentType = "pandachaika",
) {
    CrystalCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.pandachaika_restored_note),
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
