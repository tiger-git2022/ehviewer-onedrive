package com.hippo.ehviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ehviewer.core.ui.component.FloatingGlass
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

/*
 * Fork: liquid glass for the floating parts (the search bar, the sync progress, the floating buttons and both drawers),
 * behind the "Liquid glass" switch. A part refracts the content below it, recorded with [glassContent]: a page records
 * its list (see Screen and SearchBarScreen), MainActivity the pages below the drawers. The refraction needs Android 13,
 * the blur Android 12; before, the glass is only its tint.
 */

/** The content the floating parts of a page refract, recorded by SearchBarScreen; null with liquid glass off. */
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

private fun liquidGlass(backdrop: Backdrop) = FloatingGlass { shape, tint, panel ->
    Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            // A panel with text: a little more blur, so the text over busy covers stays readable
            blur((if (panel) 6f else 2f).dp.toPx())
            if (panel) {
                lens(20f.dp.toPx(), 40f.dp.toPx(), chromaticAberration = true)
            } else {
                lens(12f.dp.toPx(), 24f.dp.toPx(), chromaticAberration = true)
            }
        },
        onDrawSurface = { drawRect(tint) },
    )
}
