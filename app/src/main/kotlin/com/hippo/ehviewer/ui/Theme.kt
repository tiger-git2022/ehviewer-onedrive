package com.hippo.ehviewer.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import com.ehviewer.core.ui.util.ProvideVectorPainterCache
import com.hippo.ehviewer.ui.destinations.GalleryDetailScreenDestination
import com.hippo.ehviewer.ui.main.CardFlight
import com.hippo.ehviewer.ui.main.CardFlightDurationMillis
import com.hippo.ehviewer.ui.theme.EhTheme
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.LocalGlobalDialogState
import com.ramcosta.composedestinations.animations.NavHostAnimatedDestinationStyle
import me.zhanghai.compose.preference.ProvidePreferenceTheme
import me.zhanghai.compose.preference.preferenceTheme
import soup.compose.material.motion.animation.materialSharedAxisXIn
import soup.compose.material.motion.animation.materialSharedAxisXOut
import soup.compose.material.motion.animation.rememberSlideDistance

inline fun ComponentActivity.setMD3Content(crossinline content: @Composable DialogState.() -> Unit) = setContent {
    EhTheme(useDarkTheme = isSystemInDarkTheme()) {
        val theme = preferenceTheme(
            iconColor = MaterialTheme.colorScheme.primary,
            titleTextStyle = MaterialTheme.typography.titleMedium,
        )
        ProvidePreferenceTheme(theme) {
            ProvideVectorPainterCache {
                val dialogState = remember { DialogState() }
                CompositionLocalProvider(LocalGlobalDialogState provides dialogState) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        content(dialogState)
                        // Fork: the app-wide dialogs as liquid glass over the pages too, see DialogState.backdrop
                        CompositionLocalProvider(LocalPageBackdrop provides dialogState.backdrop) {
                            dialogState.Place()
                        }
                    }
                }
            }
        }
    }
}

private typealias Ty = AnimatedContentTransitionScope<NavBackStackEntry>

@Composable
fun rememberEhNavAnim(): NavHostAnimatedDestinationStyle {
    val slideDistance = rememberSlideDistance()
    val detailRoute = GalleryDetailScreenDestination.route
    return remember(slideDistance, detailRoute) {
        object : NavHostAnimatedDestinationStyle() {
            override val enterTransition: Ty.() -> EnterTransition = {
                if (targetState.destination.route == detailRoute && CardFlight.isCardTransition(initialState.id, targetState.id)) {
                    // Let CardFlight's sharedBounds own the fade/morph without NavHost double-fading Detail
                    fadeIn(tween(durationMillis = 1))
                } else {
                    materialSharedAxisXIn(true, slideDistance)
                }
            }
            override val exitTransition: Ty.() -> ExitTransition = {
                if (targetState.destination.route == detailRoute && CardFlight.isCardTransition(initialState.id, targetState.id)) {
                    // Keep the list solidly visible behind the expanding card (gently dimmed to 85%)
                    fadeOut(tween(durationMillis = CardFlightDurationMillis), targetAlpha = 0.85f)
                } else {
                    materialSharedAxisXOut(true, slideDistance)
                }
            }
            override val popEnterTransition: Ty.() -> EnterTransition = {
                if (initialState.destination.route == detailRoute && CardFlight.isCardTransition(targetState.id, initialState.id)) {
                    // List is immediately visible underneath so the shrinking detail visibly lands back into its card
                    fadeIn(tween(durationMillis = CardFlightDurationMillis), initialAlpha = 0.85f)
                } else {
                    materialSharedAxisXIn(false, slideDistance)
                }
            }
            override val popExitTransition: Ty.() -> ExitTransition = {
                if (initialState.destination.route == detailRoute && CardFlight.isCardTransition(targetState.id, initialState.id)) {
                    // Keep Detail mounted through the full sharedBounds shrink back into the card
                    fadeOut(tween(durationMillis = 1, delayMillis = CardFlightDurationMillis))
                } else {
                    materialSharedAxisXOut(false, slideDistance)
                }
            }
        }
    }
}
