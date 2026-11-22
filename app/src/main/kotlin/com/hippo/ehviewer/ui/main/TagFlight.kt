package com.hippo.ehviewer.ui.main

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.layout.ContentScale
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
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                zIndexInOverlay = 1f,
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

/**
 * Fork: container-transform flight between a tapped gallery card (list/grid/download) and the full
 * [com.hippo.ehviewer.ui.screen.GalleryDetailScreen] it opens. Keeps a small history of active page pairs so nested
 * stacks (List → Detail → SimilarList → Detail) each return cleanly to their own originating card, and never triggers
 * on Detail ↔ SimilarList transitions (fixing cover flicker on return).
 */
object CardFlight {
    private const val MAX_ENTRIES = 16

    private data class Entry(val gid: Long, val from: String, var to: String? = null)

    // SnapshotStateList so the tapped card attaches its sharedBounds immediately before leaving
    private val entries = mutableStateListOf<Entry>()

    /** Records that [gid] on page [fromEntry] was tapped to open its detail page. */
    fun start(gid: Long, fromEntry: String?) {
        if (fromEntry == null) return
        entries.removeAll { it.from == fromEntry }
        entries.add(Entry(gid = gid, from = fromEntry))
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    /**
     * Always returns the page-scoped key for card [gid] on list page [entry] so visible cards already have their
     * lookahead bounds measured before click (preventing frame-0 origin jumps). Only the card whose [GalleryDetailScreen]
     * claims the same key via [targetKey] matches and animates.
     */
    fun sourceKey(gid: Long, entry: String?): String? = entry?.let { keyOf(it, gid) }

    /** Claims/returns the shared key for [GalleryDetailScreen] of [gid] on page [entry], or null if opened elsewhere. */
    fun targetKey(gid: Long, entry: String?): String? {
        if (entry == null) return null
        val existing = entries.lastOrNull { it.to == entry && it.gid == gid }
        if (existing != null) return keyOf(existing.from, existing.gid)
        val pending = entries.lastOrNull { it.to == null && it.gid == gid } ?: return null
        pending.to = entry
        return keyOf(pending.from, pending.gid)
    }

    /**
     * Whether navigation between list page [fromId] and detail page [toId] is a card container transform (used by
     * [com.hippo.ehviewer.ui.rememberEhNavAnim] to keep the list solid underneath instead of sliding/fading away).
     */
    fun isCardTransition(fromId: String, toId: String): Boolean {
        val match = entries.lastOrNull { it.from == fromId && (it.to == toId || it.to == null) } ?: return false
        if (match.to == null) match.to = toId
        return true
    }

    private fun keyOf(from: String, gid: Long) = "card-flight:$from:$gid"
}

/** Container-transform source modifier for a gallery card in a list/grid/download screen. */
@Composable
fun Modifier.cardFlightSource(gid: Long): Modifier {
    val key = CardFlight.sourceKey(gid, currentPageEntry()) ?: return this
    return cardFlightBounds(key)
}

/** Container-transform target modifier for the root [Scaffold] of [GalleryDetailScreen]. */
@Composable
fun Modifier.cardFlightTarget(gid: Long): Modifier {
    val key = CardFlight.targetKey(gid, currentPageEntry()) ?: return this
    return cardFlightBounds(key)
}

@Composable
private fun Modifier.cardFlightBounds(key: String): Modifier {
    val visibility = LocalPageVisibility.current ?: return this
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    return with(shared) {
        val sharedState = rememberSharedContentState(key)
        val matched = sharedState.isMatchFound
        this@cardFlightBounds.sharedBounds(
            sharedContentState = sharedState,
            animatedVisibilityScope = visibility,
            enter = if (matched) CardFlightEnter else EnterTransition.None,
            exit = if (matched) CardFlightExit else ExitTransition.None,
            boundsTransform = CardFlightBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Crop, Alignment.Center),
            clipInOverlayDuringTransition = OverlayClip(CardFlightShape),
        )
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
private val QueryFlightCornerRadius = 20.dp
private val QueryFlightShape = RoundedCornerShape(QueryFlightCornerRadius)
private val QueryFlightBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = QueryFlightDurationMillis, easing = FastOutSlowInEasing)
}

const val CardFlightDurationMillis = 360
private const val CardFlightExitFadeMillis = 110
private const val CardFlightEnterDelayMillis = 70
private const val CardFlightEnterFadeMillis = 180
private val CardFlightEnter = fadeIn(
    tween(
        durationMillis = CardFlightEnterFadeMillis,
        delayMillis = CardFlightEnterDelayMillis,
        easing = LinearOutSlowInEasing,
    ),
)
private val CardFlightExit = fadeOut(
    tween(
        durationMillis = CardFlightExitFadeMillis,
        easing = FastOutLinearInEasing,
    ),
)
private val CardFlightShape = RoundedCornerShape(24.dp)
private val CardFlightBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = CardFlightDurationMillis, easing = FastOutSlowInEasing)
}
