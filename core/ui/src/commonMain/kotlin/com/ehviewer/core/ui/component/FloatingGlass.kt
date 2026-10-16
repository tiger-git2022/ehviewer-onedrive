package com.ehviewer.core.ui.component

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/** Fork: how a part of liquid glass looks, see [FloatingGlass]. */
enum class GlassStyle {
    /** A small control (a button, a search field): a clear glass bending strongly at its edges. */
    Control,

    /** A large part with text (a top bar): a little more blur, so the text over busy covers stays readable. */
    Panel,

    /** A drawer: a clear glass, bending gently over a wide band at its inner edge, with a deeper shadow. */
    Sheet,

    /** A whole screen in front of the content (the expanded search): a heavy blur, no bending edges. */
    Backdrop,
}

/**
 * Fork: the surface of a floating part (a button, a sheet) drawn by the app, as liquid glass: [shape] is the outline
 * of the part, [tint] the color laid over the glass so its content stays readable. The parts keep their own Material
 * color while none is provided.
 */
fun interface FloatingGlass {
    fun surface(shape: Shape, tint: Color, style: GlassStyle): Modifier
}

/** The glass of the buttons of [FabLayout]. */
val LocalFabGlass = compositionLocalOf<FloatingGlass?> { null }

/** The glass of the sheet of [MutableSideSheet]. */
val LocalSideSheetGlass = compositionLocalOf<FloatingGlass?> { null }

/** [glass] drawn behind the part, nothing without one. */
fun Modifier.floatingGlass(glass: FloatingGlass?, shape: Shape, tint: Color, style: GlassStyle = GlassStyle.Control): Modifier = if (glass == null) this else then(glass.surface(shape, tint, style))
