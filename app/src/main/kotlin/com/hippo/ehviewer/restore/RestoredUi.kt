package com.hippo.ehviewer.restore

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.CrystalCard

// Fork: how a gallery restored from PandaChika shows, see RestoredGalleries

/**
 * The badge on the cover of [gid] when restored, or found by a search on PandaChika: a panda in a triangle on the
 * corner, the top end one, or the top start one when not [atEnd]. A white triangle and a black panda in the light
 * theme, the other way round in the dark one.
 */
@Composable
fun RestoredBadge(gid: Long, modifier: Modifier = Modifier, atEnd: Boolean = true) {
    val badged by RestoredGalleries.badged.collectAsState()
    if (gid in badged) {
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        Box(modifier.size(30.dp).background(if (dark) DarkCorner else LightCorner, if (atEnd) EndCorner else StartCorner)) {
            val description = stringResource(R.string.pandachaika_badge)
            Canvas(
                modifier = Modifier.align(if (atEnd) Alignment.TopEnd else Alignment.TopStart).padding(1.dp).size(14.dp)
                    .semantics { contentDescription = description },
            ) { drawPanda(if (dark) Color.White else PandaBlack) }
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

// A panda's head in lines of one color, in a box of 24: the ears and the eye patches filled, the face open
private val PandaEars = Path().apply {
    val ears = Path().apply {
        addOval(Rect(Offset(4.8f, 6.2f), 3.6f))
        addOval(Rect(Offset(19.2f, 6.2f), 3.6f))
    }
    op(ears, oval(12f, 13.6f, 9f, 7.7f), PathOperation.Difference)
}
private val PandaEyes = Path().apply {
    addPath(oval(8f, 13.2f, 2.3f, 3.1f, rotation = 42f))
    addPath(oval(16f, 13.2f, 2.3f, 3.1f, rotation = -42f))
}
private val PandaNose = oval(12f, 16.2f, 1.25f, 0.75f)
private val PandaMouth = Path().apply {
    moveTo(12f, 16.7f)
    lineTo(12f, 17.4f)
    moveTo(10.3f, 17.5f)
    quadraticTo(11.15f, 18.8f, 12f, 17.4f)
    quadraticTo(12.85f, 18.8f, 13.7f, 17.5f)
}

private fun oval(cx: Float, cy: Float, rx: Float, ry: Float, rotation: Float = 0f) = Path().apply {
    addOval(Rect(cx - rx, cy - ry, cx + rx, cy + ry))
    if (rotation != 0f) {
        transform(
            Matrix().apply {
                translate(cx, cy)
                rotateZ(rotation)
                translate(-cx, -cy)
            },
        )
    }
}

private fun DrawScope.drawPanda(color: Color) = scale(size.minDimension / 24f, pivot = Offset.Zero) {
    drawPath(PandaEars, color)
    drawOval(color, Offset(2.2f, 5.1f), Size(19.6f, 17f), style = Stroke(1.6f))
    drawPath(PandaEyes, color)
    drawPath(PandaNose, color)
    drawPath(PandaMouth, color, style = Stroke(0.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

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
