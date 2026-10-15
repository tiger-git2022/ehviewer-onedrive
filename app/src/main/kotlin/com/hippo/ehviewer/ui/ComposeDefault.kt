package com.hippo.ehviewer.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ehviewer.core.ui.component.LocalFabGlass
import com.ehviewer.core.ui.util.NoopTransitionsVisibilityScope
import com.ehviewer.core.ui.util.TransitionsVisibilityScope
import com.ehviewer.core.ui.util.togetherWith
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.LocalGlobalDialogState
import com.hippo.ehviewer.util.findActivity
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.CoroutineScope

@Composable
context(scope: AnimatedVisibilityScope)
inline fun <R> Screen(
    navigator: DestinationsNavigator,
    crossinline block: @Composable context(MainActivity, SnackbarHostState, DialogState, SharedTransitionScope, TransitionsVisibilityScope, DestinationsNavigator, CoroutineScope)
    () -> R,
) = Box(modifier = Modifier.fillMaxSize()) {
    val dialogState = with(LocalGlobalDialogState.current) { rememberLocal() }
    // Fork: liquid glass refracting the list of the page, see LiquidGlass.kt
    val backdrop = rememberGlassBackdrop()
    CompositionLocalProvider(LocalPageBackdrop provides backdrop, LocalFabGlass provides rememberLiquidGlass(backdrop)) {
        with(NoopTransitionsVisibilityScope) {
            togetherWith(scope) {
                block(
                    with(LocalContext.current) { remember { findActivity() } },
                    LocalSnackBarHostState.current,
                    dialogState,
                    LocalSharedTransitionScope.current,
                    this,
                    navigator,
                    rememberCoroutineScope(),
                )
                dialogState.Place()
            }
        }
    }
}
