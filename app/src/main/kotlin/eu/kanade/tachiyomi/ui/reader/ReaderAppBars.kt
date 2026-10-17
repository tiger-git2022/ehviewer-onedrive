package eu.kanade.tachiyomi.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ehviewer.core.ui.component.GlassEdge
import com.ehviewer.core.ui.component.GlassStyle
import com.ehviewer.core.ui.component.IntSliderState
import com.ehviewer.core.ui.component.edgeGlass
import com.hippo.ehviewer.ui.LocalPageBackdrop
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.rememberLiquidGlass
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

private val animationSpec = tween<IntOffset>(200)

@Composable
context(navigator: DestinationsNavigator)
fun BoxScope.ReaderAppBars(
    visible: Boolean,
    title: String,
    isRtl: Boolean,
    showSeekBar: Boolean,
    slider: IntSliderState,
    totalPages: Int,
    onSliderValueChange: (Int) -> Unit,
    onClickSettings: () -> Unit,
) {
    val backgroundColor = BottomAppBarDefaults.containerColor.copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)
    // Fork: with liquid glass, the menus refract the page, see LiquidGlass.kt
    val glass = rememberLiquidGlass(LocalPageBackdrop.current)
    val glassTint = BottomAppBarDefaults.containerColor.copy(alpha = READER_GLASS_TINT)
    val barColor = if (glass != null) Color.Transparent else backgroundColor

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Horizontal)).align(Alignment.TopStart),
        enter = slideInVertically(initialOffsetY = { -it }, animationSpec = animationSpec),
        exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = animationSpec),
    ) {
        TopAppBar(
            title = { Text(text = title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            modifier = Modifier.edgeGlass(glass, GlassEdge.Bottom, glassTint, GlassStyle.Panel, READER_GLASS_CORNER),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = barColor),
            navigationIcon = { NavigationIcon() },
        )
    }

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Horizontal)).align(Alignment.BottomStart),
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = animationSpec),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = animationSpec),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showSeekBar && totalPages > 1) {
                ChapterNavigator(
                    isRtl = isRtl,
                    slider = slider,
                    totalPages = totalPages,
                    onSliderValueChange = onSliderValueChange,
                    containerColor = barColor,
                    glass = glass,
                    glassTint = glassTint,
                )
            }
            BottomReaderBar(
                onClickSettings = onClickSettings,
                containerColor = barColor,
                modifier = Modifier.edgeGlass(glass, GlassEdge.Top, glassTint, GlassStyle.Panel, READER_GLASS_CORNER),
            )
        }
    }
}

// Fork: the glass of the reader menus, only their edge toward the page shows, rounded; enough tint to read them over
// any page
private val READER_GLASS_CORNER = 24.dp
const val READER_GLASS_TINT = 0.3f
