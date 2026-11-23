package com.hippo.ehviewer.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearOutSlowInEasing
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
import com.hippo.ehviewer.ui.main.CardFlightDetailFadeInMillis
import com.hippo.ehviewer.ui.main.CardFlightDetailPopFadeEasing
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
                val isCard = targetState.destination.route == detailRoute &&
                    CardFlight.prepareTransition(initialState.id, targetState.id, forward = true)
                if (!isCard) CardFlight.clearActiveTransition()
                if (isCard) {
                    // Fade Detail body in place so the morphing card follows a pure arc without horizontal page slide
                    fadeIn(tween(durationMillis = CardFlightDetailFadeInMillis, easing = LinearOutSlowInEasing))
                } else {
                    materialSharedAxisXIn(true, slideDistance)
                }
            }
            override val exitTransition: Ty.() -> ExitTransition = {
                val isCard = targetState.destination.route == detailRoute &&
                    CardFlight.prepareTransition(initialState.id, targetState.id, forward = true)
                if (!isCard) CardFlight.clearActiveTransition()
                if (isCard) {
                    // Keep the list steady in place behind the rising card
                    fadeOut(tween(durationMillis = CardFlightDurationMillis), targetAlpha = 0.88f)
                } else {
                    materialSharedAxisXOut(true, slideDistance)
                }
            }
            override val popEnterTransition: Ty.() -> EnterTransition = {
                val isCard = initialState.destination.route == detailRoute &&
                    CardFlight.prepareTransition(targetState.id, initialState.id, forward = false)
                if (!isCard) CardFlight.clearActiveTransition()
                if (isCard) {
                    // List is immediately visible in place so the returning card or top-bar capsule lands cleanly
                    fadeIn(tween(durationMillis = CardFlightDurationMillis), initialAlpha = 0.88f)
                } else {
                    materialSharedAxisXIn(false, slideDistance)
                }
            }
            override val popExitTransition: Ty.() -> ExitTransition = {
                val isCard = initialState.destination.route == detailRoute &&
                    CardFlight.prepareTransition(targetState.id, initialState.id, forward = false)
                if (!isCard) CardFlight.clearActiveTransition()
                if (isCard) {
                    // Front-loaded fade clears Detail content quickly while keeping it mounted for the full 380ms flight
                    fadeOut(tween(durationMillis = CardFlightDurationMillis, easing = CardFlightDetailPopFadeEasing))
                } else {
                    materialSharedAxisXOut(false, slideDistance)
                }
            }
        }
    }
}
