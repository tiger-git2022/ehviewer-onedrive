package com.hippo.ehviewer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.ehviewer.core.ui.component.FloatingGlass
import com.ehviewer.core.ui.component.GlassStyle
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow

/*
 * Fork: liquid glass for the floating parts (the search bar and the expanded search, the sync progress, the floating
 * buttons, both drawers, the top bar of the gallery details), behind the "Liquid glass" switch. A part refracts the
 * content below it, recorded with [glassContent]: a page records its list (see Screen, SearchBarScreen and
 * GalleryDetailScreen), MainActivity the pages below the drawers. A part inside the recorded content cannot refract it
 * (it would draw itself): the scrollbar and the refresh indicator are only translucent. The refraction needs Android 13,
 * the blur Android 12; before, the glass is only its tint and its edge.
 */

/** The content the floating parts of a page refract; null with liquid glass off. */
val LocalPageBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** A backdrop to record content in while liquid glass is on, else null. */
@Composable
fun rememberGlassBackdrop(): LayerBackdrop? {
    val enabled by Settings.liquidGlass.collectAsState()
    if (!enabled) return null
    // Recorded on the page color: where nothing is drawn (the room left for the search bar at the top of the list),
    // the edge of the glass refracted a hole, a light ring on a light page
    val background = MaterialTheme.colorScheme.background
    val onDraw: ContentDrawScope.() -> Unit = remember(background) {
        {
            drawRect(background)
            drawContent()
        }
    }
    return rememberLayerBackdrop(onDraw = onDraw)
}

/** Records this content for the glass in front of it. */
fun Modifier.glassContent(backdrop: LayerBackdrop?): Modifier = if (backdrop == null) this else layerBackdrop(backdrop)

/** Liquid glass refracting [backdrop], null without one. */
@Composable
fun rememberLiquidGlass(backdrop: Backdrop?): FloatingGlass? {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return remember(backdrop, dark) { backdrop?.let { liquidGlass(it, dark) } }
}

// The edge of the glass: a lit edge, wider and brighter than the library default (0.5 dp), so the glass reads as glass.
// None in light themes, like the system apps of vivo: on the light page a lit edge was a white ring around the glass,
// a soft shadow all around outlines it instead. The glass is tinted white there: tinted with the gray surface colors,
// it was darker than the page
private val DarkGlassEdge = Highlight(width = 1.5.dp, blurRadius = 1.dp, style = HighlightStyle.Default(color = Color.White.copy(alpha = 0.6f)))
private val LightGlassShadow = Shadow(radius = 20.dp, offset = DpOffset(0.dp, 2.dp), color = Color.Black.copy(alpha = 0.12f))

// The drawers and the dialogs, chosen in the drawer glass tuner: a fainter edge and a deeper shadow, lifting them off the pages
private val DarkSheetEdge = Highlight(width = 1.5.dp, blurRadius = 1.dp, style = HighlightStyle.Default(color = Color.White.copy(alpha = 0.5f)))
private val SheetShadow = Shadow(radius = 20.dp, offset = DpOffset(0.dp, 2.dp), color = Color.Black.copy(alpha = 0.3f))

private fun liquidGlass(backdrop: Backdrop, dark: Boolean) = FloatingGlass { shape, tint, style ->
    val surface = if (dark) tint else Color.White.copy(alpha = tint.alpha)
    val edge = when {
        !dark || style == GlassStyle.Backdrop -> null
        style == GlassStyle.Sheet || style == GlassStyle.Dialog -> DarkSheetEdge
        else -> DarkGlassEdge
    }
    val shadow = when {
        // A screen-wide glass has no shadow to cast
        style == GlassStyle.Backdrop -> null
        style == GlassStyle.Sheet || style == GlassStyle.Dialog -> SheetShadow
        dark -> Shadow.Default
        else -> LightGlassShadow
    }
    Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            when (style) {
                // Only the rim bends: a deeper lens stretched the text below the search bar
                GlassStyle.Control -> {
                    blur(1.5f.dp.toPx())
                    lens(12f.dp.toPx(), 20f.dp.toPx(), chromaticAberration = true)
                }
                GlassStyle.Panel -> {
                    blur(4f.dp.toPx())
                    lens(16f.dp.toPx(), 28f.dp.toPx(), chromaticAberration = true)
                }
                // A wide gentle bend at the inner edge, chosen in the drawer glass tuner. With the blur of a panel,
                // the drawers read as frosted glass; with the blur of a dialog, their lists were hard to read
                GlassStyle.Sheet -> {
                    blur(2f.dp.toPx())
                    lens(25f.dp.toPx(), 10f.dp.toPx())
                }
                GlassStyle.Dialog -> {
                    blur(1f.dp.toPx())
                    lens(25f.dp.toPx(), 10f.dp.toPx())
                }
                // A light blur, chosen in the search glass tuner: the list stays visible behind the search
                GlassStyle.Backdrop -> blur(10f.dp.toPx())
            }
        },
        highlight = edge?.let { { it } },
        shadow = shadow?.let { { it } },
        onDrawSurface = { drawRect(surface) },
    )
}

/** A selected item, toggle or chip over liquid glass: a light in the glass instead of a solid block. */
@Composable
fun glassSelectedColor() = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

/** The text and icon of [glassSelectedColor]. */
@Composable
fun glassOnSelectedColor() = MaterialTheme.colorScheme.primary

@Composable
fun glassDrawerItemColors() = NavigationDrawerItemDefaults.colors(
    selectedContainerColor = glassSelectedColor(),
    selectedIconColor = glassOnSelectedColor(),
    selectedTextColor = glassOnSelectedColor(),
)
