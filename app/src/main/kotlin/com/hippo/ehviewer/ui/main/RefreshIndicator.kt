package com.hippo.ehviewer.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.ui.LocalPageBackdrop

/**
 * Fork: the pull to refresh indicator. With liquid glass, a disc of glass with a lit rim, like the floating buttons
 * (it lies inside the content the glass refracts, so it cannot refract it itself): a thin ring fills up with the pull
 * around an arrow turning with it, and spins while refreshing. Without it, the Material indicator.
 */
@Composable
fun RefreshIndicator(state: PullToRefreshState, isRefreshing: Boolean, modifier: Modifier = Modifier) {
    if (LocalPageBackdrop.current == null) {
        PullToRefreshDefaults.LoadingIndicator(
            state = state,
            isRefreshing = isRefreshing,
            containerColor = LoadingIndicatorDefaults.containedContainerColor,
            modifier = modifier,
        )
        return
    }
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val rim = if (dark) Color.White.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.12f)
    val color = scheme.primary
    PullToRefreshDefaults.IndicatorBox(
        state = state,
        isRefreshing = isRefreshing,
        modifier = modifier,
        shape = CircleShape,
        containerColor = scheme.surfaceContainerHigh.copy(alpha = 0.55f),
        elevation = 0.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize().border(1.dp, rim, CircleShape), contentAlignment = Alignment.Center) {
            Crossfade(targetState = isRefreshing, label = "Refresh") { refreshing ->
                Box(modifier = Modifier.size(RingSize), contentAlignment = Alignment.Center) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.fillMaxSize(),
                            color = color,
                            strokeWidth = RingStroke,
                            trackColor = color.copy(alpha = 0.18f),
                            strokeCap = StrokeCap.Round,
                            gapSize = ProgressIndicatorDefaults.CircularIndicatorTrackGapSize,
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { state.distanceFraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxSize(),
                            color = color,
                            strokeWidth = RingStroke,
                            trackColor = color.copy(alpha = 0.18f),
                            strokeCap = StrokeCap.Round,
                            gapSize = ProgressIndicatorDefaults.CircularIndicatorTrackGapSize,
                        )
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(ArrowSize).graphicsLayer {
                                val fraction = state.distanceFraction.coerceIn(0f, 1f)
                                alpha = fraction
                                rotationZ = fraction * 270f
                            },
                        )
                    }
                }
            }
        }
    }
}

private val RingSize = 28.dp
private val RingStroke = 2.dp
private val ArrowSize = 16.dp
