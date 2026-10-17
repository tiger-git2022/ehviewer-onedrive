package com.ehviewer.core.ui.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/** Fork: how a part of liquid glass looks, see [FloatingGlass]. */
enum class GlassStyle {
    /** A small control (a button, a search field): a clear glass bending strongly at its edges. */
    Control,

    /** A large part with text (a top bar): a little more blur, so the text over busy covers stays readable. */
    Panel,

    /** A drawer or a dialog: a blurred glass, bending gently over a wide band at its edge, with a deeper shadow. */
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

/** Fork: the one side of a bar or a drawer whose glass edge shows, see [edgeGlass]. */
enum class GlassEdge { Top, Bottom, Start, End }

// How far the glass reaches past the other sides, off the screen: more than the bend of the lens
private val GlassBleed = 48.dp

/**
 * Fork: [glass] for a part along a side of the screen (a top bar, a drawer), showing only its [edge]: the glass reaches
 * past the other sides, so their lit edge, shadow and bend lay off the screen and the part melts into the screen there.
 * With [corner]s, the part keeps them at both ends of its edge, and only the side facing away from it reaches off the
 * screen: the two sides between stay, along the screen borders for a bar, at the ends of the screen for a drawer.
 */
fun Modifier.edgeGlass(glass: FloatingGlass?, edge: GlassEdge, tint: Color, style: GlassStyle, corner: Dp = 0.dp): Modifier {
    if (glass == null) return this
    val rounded = corner > 0.dp
    // A corner-based shape: the lens of the glass takes only rounded rectangles (another shape throws on Android 13+)
    val shape = when {
        !rounded -> RoundedCornerShape(0.dp)
        edge == GlassEdge.Bottom -> RoundedCornerShape(bottomStart = corner, bottomEnd = corner)
        edge == GlassEdge.Top -> RoundedCornerShape(topStart = corner, topEnd = corner)
        edge == GlassEdge.Start -> RoundedCornerShape(topStart = corner, bottomStart = corner)
        else -> RoundedCornerShape(topEnd = corner, bottomEnd = corner)
    }
    var left = 0
    var top = 0
    var right = 0
    var bottom = 0
    return layout { measurable, constraints ->
        val bleed = GlassBleed.roundToPx()
        val shown = edge.absolute(layoutDirection)
        // Off the screen: every side but the edge, or with corners only the side facing away from it
        fun bleeds(side: AbsoluteEdge) = side != shown && (!rounded || side == shown.opposite)
        left = if (bleeds(AbsoluteEdge.Left)) bleed else 0
        right = if (bleeds(AbsoluteEdge.Right)) bleed else 0
        top = if (bleeds(AbsoluteEdge.Top)) bleed else 0
        bottom = if (bleeds(AbsoluteEdge.Bottom)) bleed else 0
        val placeable = measurable.measure(constraints.offset(left + right, top + bottom))
        layout(placeable.width - left - right, placeable.height - top - bottom) { placeable.place(-left, -top) }
    }.then(glass.surface(shape, tint, style)).layout { measurable, constraints ->
        // The content keeps the bounds of the part
        val placeable = measurable.measure(constraints.offset(-left - right, -top - bottom))
        layout(placeable.width + left + right, placeable.height + top + bottom) { placeable.place(left, top) }
    }
}

private enum class AbsoluteEdge {
    Left,
    Top,
    Right,
    Bottom,
    ;

    val opposite get() = when (this) {
        Left -> Right
        Top -> Bottom
        Right -> Left
        Bottom -> Top
    }
}

private fun GlassEdge.absolute(direction: LayoutDirection) = when (this) {
    GlassEdge.Top -> AbsoluteEdge.Top
    GlassEdge.Bottom -> AbsoluteEdge.Bottom
    GlassEdge.Start -> if (direction == LayoutDirection.Ltr) AbsoluteEdge.Left else AbsoluteEdge.Right
    GlassEdge.End -> if (direction == LayoutDirection.Ltr) AbsoluteEdge.Right else AbsoluteEdge.Left
}
