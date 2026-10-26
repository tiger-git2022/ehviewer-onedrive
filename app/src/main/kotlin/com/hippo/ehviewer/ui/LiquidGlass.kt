package com.hippo.ehviewer.ui

import androidx.compose.foundation.shape.CircleShape
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
import com.ehviewer.core.ui.component.LocalTileGlass
import com.ehviewer.core.ui.component.floatingGlass
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
 * (it would draw itself): the scrollbar and the refresh indicator are only translucent. The cards and the chips of the
 * expanded search refract its blurred glass instead (see SearchBarScreen). The refraction needs Android 13,
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
    // Fork: the drawers, the dialogs and the expanded search as set in Settings → EH
    val sheetBlur by Settings.sheetGlassBlur.collectAsState()
    val sheetTint by Settings.sheetGlassTint.collectAsState()
    val sheetFringe by Settings.sheetGlassFringe.collectAsState()
    return remember(backdrop, dark, sheetBlur, sheetTint, sheetFringe) {
        backdrop?.let { liquidGlass(it, dark, sheetBlur / 10f, sheetTint / 100f, sheetFringe / 100f) }
    }
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

// The cards and the chips of the expanded search: many of them side by side, a faint shadow keeps the page calm
private val TileShadow = Shadow(radius = 12.dp, offset = DpOffset(0.dp, 1.dp), color = Color.Black.copy(alpha = 0.1f))

private fun liquidGlass(backdrop: Backdrop, dark: Boolean, sheetBlur: Float, sheetTint: Float, sheetFringe: Float) = FloatingGlass { shape, tint, style ->
    // The drawers and the dialogs take the tint set in the settings
    val alpha = if (style == GlassStyle.Sheet) sheetTint.coerceIn(0f, 1f) else tint.alpha
    val surface = if (dark) tint.copy(alpha = alpha) else Color.White.copy(alpha = alpha)
    val edge = when {
        !dark || style == GlassStyle.Backdrop -> null
        style == GlassStyle.Sheet -> DarkSheetEdge
        else -> DarkGlassEdge
    }
    val shadow = when {
        // A screen-wide glass has no shadow to cast
        style == GlassStyle.Backdrop -> null
        style == GlassStyle.Sheet -> SheetShadow
        style == GlassStyle.Tile -> TileShadow
        dark -> Shadow.Default
        else -> LightGlassShadow
    }
    Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            // A tile refracts a glass that has its vibrancy already: brighter colors would set the tile apart from it
            if (style != GlassStyle.Tile) vibrancy()
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
                // A wide gentle bend at the edge, chosen in the drawer glass tuner, and the blur set in the settings
                // (2.5 dp by default) keeping the lists readable over busy covers; the color fringes at the edge are
                // a setting too, up to 12 dp apart (6 by default) over the outer 24 dp
                GlassStyle.Sheet -> {
                    if (sheetBlur > 0f) blur(sheetBlur.dp.toPx())
                    lens(25f.dp.toPx(), 10f.dp.toPx())
                    edgeFringe(24f.dp.toPx(), sheetFringe * 12f.dp.toPx())
                }
                // The blur of the drawers and the dialogs, set in the settings (none at 0): the list stays visible
                // behind the search
                GlassStyle.Backdrop -> if (sheetBlur > 0f) blur(sheetBlur.dp.toPx())
                // Refracting the blurred glass of the expanded search, so no blur: only the edge bends, over less
                // of the small chips than of the cards
                GlassStyle.Tile -> {
                    val height = minOf(16f.dp.toPx(), size.minDimension * 0.3f)
                    lens(height, height * 1.4f, chromaticAberration = true)
                }
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

/**
 * A round toggle checked over liquid glass ("汉", hiding the favorited, the comment translation): a small lens of clear
 * glass around its text or icon, with the lit edge and the shadow of the floating buttons, instead of a flat blue light.
 * On the expanded search it refracts the blurred glass there, like the cards ([LocalTileGlass]). Nothing while unchecked
 * or without liquid glass.
 */
@Composable
fun Modifier.checkedGlass(checked: Boolean): Modifier {
    val tileGlass = LocalTileGlass.current
    val pageGlass = rememberLiquidGlass(LocalPageBackdrop.current)
    val glass = tileGlass ?: pageGlass
    if (!checked || glass == null) return this
    // A neutral glass like the floating buttons: a tint of the primary color read as a solid colored block
    val tint = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = CHECKED_GLASS_TINT)
    return floatingGlass(glass, CircleShape, tint, if (tileGlass != null) GlassStyle.Tile else GlassStyle.Control)
}

private const val CHECKED_GLASS_TINT = 0.3f

/** The items of the left drawer: black or white like the right drawer, the tinted gray was hard to read on the glass. */
@Composable
fun glassDrawerItemColors() = NavigationDrawerItemDefaults.colors(
    unselectedIconColor = MaterialTheme.colorScheme.onSurface,
    unselectedTextColor = MaterialTheme.colorScheme.onSurface,
    selectedContainerColor = glassSelectedColor(),
    selectedIconColor = glassOnSelectedColor(),
    selectedTextColor = glassOnSelectedColor(),
)
