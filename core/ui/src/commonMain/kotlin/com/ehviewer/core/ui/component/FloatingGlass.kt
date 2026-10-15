package com.ehviewer.core.ui.component

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/**
 * Fork: the surface of a floating part (a button, a sheet) drawn by the app, as liquid glass: [shape] is the outline
 * of the part, [tint] the color laid over the glass so its content stays readable, [panel] a large part with text that
 * needs a calmer glass. The parts keep their own Material color while none is provided.
 */
fun interface FloatingGlass {
    fun surface(shape: Shape, tint: Color, panel: Boolean): Modifier
}

/** The glass of the buttons of [FabLayout]. */
val LocalFabGlass = compositionLocalOf<FloatingGlass?> { null }

/** The glass of the sheet of [MutableSideSheet]. */
val LocalSideSheetGlass = compositionLocalOf<FloatingGlass?> { null }

/** [glass] drawn behind the part, nothing without one. */
fun Modifier.floatingGlass(glass: FloatingGlass?, shape: Shape, tint: Color, panel: Boolean = false): Modifier = if (glass == null) this else then(glass.surface(shape, tint, panel))
