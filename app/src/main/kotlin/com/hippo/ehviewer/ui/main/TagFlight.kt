package com.hippo.ehviewer.ui.main

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import com.hippo.ehviewer.ui.LocalSharedTransitionScope
import com.hippo.ehviewer.ui.screen.searchTagParts

/** Fork: the page's own enter and exit, given by Screen, for the shared elements between pages. */
val LocalPageVisibility = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Fork: a tag of a gallery tapped flies into the search bar of the list of the tag it opens, and back to the gallery
 * when that list closes. Only between those two pages: elsewhere the same tag doesn't fly, as when a gallery of that
 * list is opened. The same for searching similar galleries by title ([startQuery]): the similar-gallery button morphs
 * inside a rounded capsule into the full (1- or 2-line) title in the search bar, and morphs back into the button on
 * return.
 */
object TagFlight {
    // State, so the tag tapped takes its flight before its page leaves
    private var key by mutableStateOf<String?>(null)
    private var from by mutableStateOf<String?>(null)

    // Taken by the list as it's made, not state: nothing needs to change when it is
    private var to: String? = null

    /** The tag [tag] of the page [entry] tapped, opening its list. */
    fun start(tag: String, entry: String?) = startKey(flightKey(tag), entry)

    /** The title [query] searched from the page [entry], opening its list. */
    fun startQuery(query: String, entry: String?) = startKey(queryKey(query), entry)

    private fun startKey(flight: String, entry: String?) {
        key = flight
        from = entry
        to = null
    }

    /** Whether [tag] on the page [entry] flies; the list it opened takes the flight as it comes ([claim]). */
    fun flies(tag: String, entry: String?, claim: Boolean): Boolean = fliesKey(flightKey(tag), entry, claim)

    /** Whether the title [query] on the page [entry] flies, as [flies]. */
    fun fliesQuery(query: String, entry: String?, claim: Boolean): Boolean = fliesKey(queryKey(query), entry, claim)

    private fun fliesKey(flight: String, entry: String?, claim: Boolean): Boolean {
        if (entry == null || key == null || flight != key) return false
        if (entry == from || entry == to) return true
        if (claim && to == null) {
            to = entry
            return true
        }
        return false
    }

    private fun flightKey(tag: String) = searchTagParts(tag).let { (namespace, name) -> "${namespace.lowercase()}:${name.lowercase()}" }

    // Never the key of a tag, which has a colon
    private fun queryKey(query: String) = "query|" + query.trim().lowercase()
}

/** The page's own entry in the navigation, by its id. */
@Composable
fun currentPageEntry(): String? = (LocalViewModelStoreOwner.current as? NavBackStackEntry)?.id

/** Flies as the tag [tag] when it's the one of [TagFlight] between these pages; the list's chip takes it ([claim]). */
@Composable
fun Modifier.tagFlight(tag: String, claim: Boolean = false): Modifier {
    val visibility = LocalPageVisibility.current ?: return this
    if (!TagFlight.flies(tag, currentPageEntry(), claim)) return this
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    return with(shared) {
        this@tagFlight.sharedBounds(
            rememberSharedContentState(TagFlightKey),
            visibility,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
        )
    }
}

/**
 * Morphs between the similar-gallery button on the gallery detail page and the full (1- or 2-line) title block in
 * the search bar inside a temporary rounded capsule:
 * - Forward: 「相似画廊」button blooms into a capsule, glides up, expands and crossfades into the full title, then the
 *   capsule dissolves into the search bar.
 * - Return: the full 1- or 2-line title lifts off inside a capsule, glides back down, contracts and crossfades back
 *   into the 「相似画廊」button, then the capsule dissolves at rest.
 */
@Composable
fun Modifier.queryFlight(query: String, claim: Boolean = false): Modifier {
    val visibility = LocalPageVisibility.current ?: return this
    if (!TagFlight.fliesQuery(query, currentPageEntry(), claim)) return this
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    val pillColor = MaterialTheme.colorScheme.tertiaryContainer
    return with(shared) {
        val sharedState = rememberSharedContentState(QueryFlightKey)
        val transitionTarget = visibility.transition.targetState
        var matched by remember(transitionTarget) { mutableStateOf(false) }
        if (sharedState.isMatchFound) matched = true
        val pillAlpha by visibility.transition.animateFloat(
            transitionSpec = {
                if (targetState == EnterExitState.Visible) {
                    // Hold the capsule through the morph, then dissolve smoothly as it lands
                    tween(
                        durationMillis = QueryFlightPillFadeOutMillis,
                        delayMillis = QueryFlightPillHoldMillis,
                        easing = LinearOutSlowInEasing,
                    )
                } else {
                    tween(durationMillis = QueryFlightPillFadeInMillis, easing = LinearOutSlowInEasing)
                }
            },
            label = "queryFlightPillAlpha",
        ) { state ->
            if (state == EnterExitState.Visible) 0f else QueryFlightPillAlpha
        }
        this@queryFlight
            .sharedBounds(
                sharedContentState = sharedState,
                animatedVisibilityScope = visibility,
                enter = fadeIn(
                    tween(
                        durationMillis = QueryFlightEnterFadeMillis,
                        delayMillis = QueryFlightEnterDelayMillis,
                        easing = LinearOutSlowInEasing,
                    ),
                ),
                exit = fadeOut(
                    tween(
                        durationMillis = QueryFlightExitFadeMillis,
                        easing = FastOutLinearInEasing,
                    ),
                ),
                boundsTransform = QueryFlightBoundsTransform,
                resizeMode = SharedTransitionScope.ResizeMode.remeasureToBounds,
                clipInOverlayDuringTransition = OverlayClip(QueryFlightShape),
            )
            .drawBehind {
                if (matched && pillAlpha > 0f) {
                    drawRoundRect(
                        color = pillColor.copy(alpha = pillAlpha),
                        cornerRadius = CornerRadius(QueryFlightCornerRadius.toPx()),
                    )
                }
            }
    }
}

private const val TagFlightKey = "tag-flight"
private const val QueryFlightKey = "query-flight"
private const val QueryFlightDurationMillis = 420
private const val QueryFlightExitFadeMillis = 190
private const val QueryFlightEnterDelayMillis = 120
private const val QueryFlightEnterFadeMillis = 240
private const val QueryFlightPillFadeInMillis = 90
private const val QueryFlightPillHoldMillis = 240
private const val QueryFlightPillFadeOutMillis = 180
private const val QueryFlightPillAlpha = 0.92f
private val QueryFlightCornerRadius = 16.dp
private val QueryFlightShape = RoundedCornerShape(QueryFlightCornerRadius)
private val QueryFlightBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = QueryFlightDurationMillis, easing = FastOutSlowInEasing)
}
