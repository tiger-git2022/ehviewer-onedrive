package com.hippo.ehviewer.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ehviewer.core.ui.component.FloatingGlass
import com.ehviewer.core.ui.component.GlassStyle
import com.ehviewer.core.ui.component.floatingGlass
import com.hippo.ehviewer.ui.LocalPageBackdrop
import kotlin.math.roundToInt

/**
 * Fork: the pull to refresh of a list inside the content liquid glass refracts. Glass drawn there cannot refract the
 * content it is part of, so the page holding the glass draws the indicator over that content (see SearchBarScreen)
 * from what the list tells it here.
 */
@Stable
class RefreshIndicatorHost {
    var state by mutableStateOf<PullToRefreshState?>(null)
    var isRefreshing by mutableStateOf(false)
    var top by mutableStateOf(0.dp)
    var offsetY: () -> Int = { 0 }
}

/** The host of the page, null where the list draws its indicator itself. */
val LocalRefreshIndicatorHost = staticCompositionLocalOf<RefreshIndicatorHost?> { null }

/**
 * Fork: the pull to refresh indicator of every list: the refresh arrow, turning with the pull and while refreshing, in
 * a drop of glass ([glass] refracting the page; without one, a translucent disc with a lit rim), or on a plain disc
 * with liquid glass off.
 */
@Composable
fun RefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
    glass: FloatingGlass? = null,
    offsetY: () -> Int = { 0 },
) {
    // Nothing to draw (nor to refract) while not pulled
    if (state.distanceFraction <= 0f && !isRefreshing) return
    val scheme = MaterialTheme.colorScheme
    val spin = rememberInfiniteTransition(label = "Refresh")
    val spinAngle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "Spin")
    Box(
        modifier = modifier
            .offset {
                // Comes down with the pull from above the top of the list, like the Material indicator
                val travel = state.distanceFraction * PullToRefreshDefaults.PositionalThreshold.toPx() - IndicatorSize.toPx()
                IntOffset(0, travel.roundToInt() + offsetY())
            }
            .graphicsLayer { alpha = if (isRefreshing) 1f else (state.distanceFraction * 2f).coerceIn(0f, 1f) }
            .size(IndicatorSize)
            .refreshDrop(glass),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = null,
            tint = scheme.onSurface,
            modifier = Modifier.size(ArrowSize).graphicsLayer {
                rotationZ = if (isRefreshing) spinAngle else state.distanceFraction.coerceIn(0f, 1f) * 270f
            },
        )
    }
}

/**
 * Fork: a page or a part of it loading: the Material loading shape, morphing between rounded shapes as it turns, in
 * place of the Material wavy circle. No container: on a plain page a drop of glass read as a gray dot.
 */
@Composable
fun ShapeLoadingIndicator(modifier: Modifier = Modifier) {
    LoadingIndicator(modifier = modifier.size(LoadingSize), color = MaterialTheme.colorScheme.primary)
}

// The drop behind the arrow: glass, else a translucent disc with a lit rim over glass pages, else a plain disc
@Composable
private fun Modifier.refreshDrop(glass: FloatingGlass?): Modifier {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return if (glass != null) {
        floatingGlass(glass, CircleShape, scheme.surfaceContainerHigh.copy(alpha = GLASS_TINT), GlassStyle.Control)
    } else if (LocalPageBackdrop.current == null) {
        // Liquid glass off: the same arrow on a plain disc
        shadow(3.dp, CircleShape).background(scheme.surfaceContainerHigh, CircleShape)
    } else {
        val rim = if (dark) Color.White.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.12f)
        background(scheme.surfaceContainerHigh.copy(alpha = 0.55f), CircleShape).border(1.dp, rim, CircleShape)
    }
}

private val IndicatorSize: Dp = 40.dp
private val LoadingSize: Dp = 48.dp
private val ArrowSize: Dp = 22.dp

// Like the floating buttons
private const val GLASS_TINT = 0.25f
