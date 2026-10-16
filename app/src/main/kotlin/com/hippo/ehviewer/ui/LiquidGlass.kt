package com.hippo.ehviewer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    return if (enabled) rememberLayerBackdrop() else null
}

/** Records this content for the glass in front of it. */
fun Modifier.glassContent(backdrop: LayerBackdrop?): Modifier = if (backdrop == null) this else layerBackdrop(backdrop)

/** Liquid glass refracting [backdrop], null without one. */
@Composable
fun rememberLiquidGlass(backdrop: Backdrop?): FloatingGlass? = remember(backdrop) { backdrop?.let(::liquidGlass) }

// The lit edge of the glass: wider and brighter than the library default (0.5 dp), so the glass reads as glass
private val GlassEdge = Highlight(width = 1.5.dp, blurRadius = 1.dp, style = HighlightStyle.Default(color = Color.White.copy(alpha = 0.6f)))

private fun liquidGlass(backdrop: Backdrop) = FloatingGlass { shape, tint, style ->
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
                // A light blur, chosen in the search glass tuner: the list stays visible behind the search
                GlassStyle.Backdrop -> blur(5f.dp.toPx())
            }
        },
        // A screen-wide glass has no edge to light nor a shadow to cast
        highlight = if (style == GlassStyle.Backdrop) null else ({ GlassEdge }),
        shadow = if (style == GlassStyle.Backdrop) null else ({ Shadow.Default }),
        onDrawSurface = { drawRect(tint) },
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
