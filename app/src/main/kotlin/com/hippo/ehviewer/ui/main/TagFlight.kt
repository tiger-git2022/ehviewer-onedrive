package com.hippo.ehviewer.ui.main

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import com.ehviewer.core.ui.component.GlassStyle
import com.ehviewer.core.ui.component.floatingGlass
import com.hippo.ehviewer.ui.DARK_GLASS_EDGE
import com.hippo.ehviewer.ui.LocalSharedTransitionScope
import com.hippo.ehviewer.ui.rememberLiquidGlass
import com.hippo.ehviewer.ui.screen.searchTagParts
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlin.math.pow

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

    private class Entry(val gid: Long, val from: String, var to: String? = null) {
        var headerVisible by mutableStateOf(true)
    }

    // SnapshotStateList so the tapped card attaches its sharedBounds immediately before leaving
    private val entries = mutableStateListOf<Entry>()

    // Live LayerBackdrop per active screen entry (updated by Screen in ComposeDefault.kt on compose & popEnter)
    private val pageBackdrops = mutableStateMapOf<String, LayerBackdrop?>()

    // Exact active List <-> Detail transition pair set by rememberEhNavAnim before frame 0 (eliminates isMatchFound delay)
    private var activeFrom by mutableStateOf<String?>(null)
    private var activeTo by mutableStateOf<String?>(null)
    private var activeForward by mutableStateOf(true)

    /** Whether the active List <-> Detail transition is entering Detail (true) or returning to the list (false). */
    val isForward: Boolean
        get() = activeForward

    /** Registers the current live [LayerBackdrop] for screen [entry] so the glass capsule can refract it cleanly. */
    fun registerPageBackdrop(entry: String?, backdrop: LayerBackdrop?) {
        if (entry == null) return
        if (backdrop != null) {
            pageBackdrops[entry] = backdrop
        } else {
            pageBackdrops.remove(entry)
        }
    }

    /** Unregisters [backdrop] when screen [entry] leaves composition. */
    fun unregisterPageBackdrop(entry: String?, backdrop: LayerBackdrop?) {
        if (entry == null) return
        if (pageBackdrops[entry] === backdrop) {
            pageBackdrops.remove(entry)
        }
    }

    /** Records that [gid] on page [fromEntry] was tapped to open its detail page. */
    fun start(gid: Long, fromEntry: String?) {
        if (fromEntry == null) return
        entries.removeAll { it.from == fromEntry }
        entries.add(Entry(gid = gid, from = fromEntry))
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    /** Updates whether the top header card on detail page [detailEntry] is still visible (controls header vs top-bar return). */
    fun setDetailHeaderVisible(detailEntry: String?, visible: Boolean) {
        if (detailEntry == null) return
        val entry = entries.lastOrNull { it.to == detailEntry } ?: return
        if (entry.headerVisible != visible) {
            entry.headerVisible = visible
        }
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
     * Marks [fromId] <-> [toId] as the active card flight when navigating between a list page and its detail page.
     * Called synchronously by [com.hippo.ehviewer.ui.rememberEhNavAnim] before frame 0 so both source and target know
     * immediately whether to run their flight crossfades and which arc direction to use.
     */
    fun prepareTransition(fromId: String, toId: String, forward: Boolean): Boolean {
        val match = entries.lastOrNull { it.from == fromId && (it.to == toId || it.to == null) }
        if (match == null) {
            clearActiveTransition()
            return false
        }
        if (match.to == null) match.to = toId
        if (activeFrom != fromId || activeTo != toId) {
            activeFrom = fromId
            activeTo = toId
        }
        if (activeForward != forward) {
            activeForward = forward
        }
        return true
    }

    /** Clears the active card-flight pair for non-card transitions (e.g. Detail <-> SimilarList or Settings). */
    fun clearActiveTransition() {
        if (activeFrom != null || activeTo != null) {
            activeFrom = null
            activeTo = null
        }
    }

    /** Whether card [gid] on [fromEntry] is the active card participating in the current List <-> Detail transition. */
    fun isActiveSource(gid: Long, fromEntry: String?): Boolean {
        if (fromEntry == null || activeFrom != fromEntry) return false
        val to = activeTo ?: return false
        return entries.lastOrNull { it.from == fromEntry && it.to == to }?.gid == gid
    }

    /** Whether detail page [detailEntry] of [gid] is participating in the current List <-> Detail transition. */
    fun isActiveDetail(gid: Long, detailEntry: String?): Boolean {
        if (detailEntry == null || activeTo != detailEntry) return false
        val from = activeFrom ?: return false
        return entries.lastOrNull { it.from == from && it.to == detailEntry }?.gid == gid
    }

    /** Whether source card [gid] on [fromEntry] is receiving a scrolled-down glass top-bar capsule return. */
    fun useTopBarCapsuleForSource(fromEntry: String?, gid: Long): Boolean {
        if (fromEntry == null) return false
        val entry = entries.lastOrNull { it.from == fromEntry && it.gid == gid } ?: return false
        return !entry.headerVisible
    }

    /** Returns the live [LayerBackdrop] of the list page that opened [detailEntry], or null if unavailable. */
    fun sourceBackdropFor(detailEntry: String?, gid: Long): LayerBackdrop? {
        if (detailEntry == null) return null
        val entry = entries.lastOrNull { it.to == detailEntry && it.gid == gid } ?: return null
        return pageBackdrops[entry.from]
    }

    private fun keyOf(from: String, gid: Long) = "card-flight:$from:$gid"
}

/**
 * Source wrapper for a gallery card in a list/grid/download screen:
 * - Morphs directly into [GalleryDetailHeaderCard] at the top of detail on enter (horizontal-lead arc) and morphs
 *   back along the exact reversed path (vertical-lead arc) when returning near the top.
 * - Once the user scrolls past the header card, receives the Liquid Glass capsule morphing back from the detail
 *   screen's glass top app bar.
 */
@Composable
fun CardFlightSource(
    gid: Long,
    content: @Composable (cardModifier: Modifier, contentModifier: Modifier) -> Unit,
) {
    val visibility = LocalPageVisibility.current
    val pageEntry = currentPageEntry()
    val key = CardFlight.sourceKey(gid, pageEntry)
    if (visibility == null || key == null) {
        content(Modifier, Modifier)
        return
    }
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    with(shared) {
        val sharedState = rememberSharedContentState(key)
        val active = CardFlight.isActiveSource(gid, pageEntry)
        val useCapsule = CardFlight.useTopBarCapsuleForSource(pageEntry, gid)
        val enter = when {
            !active -> EnterTransition.None
            useCapsule -> CapsuleSourceEnter
            else -> HeaderCardEnter
        }
        val exit = when {
            !active -> ExitTransition.None
            useCapsule -> CapsuleSourceExit
            else -> HeaderCardExit
        }
        val cardModifier = Modifier.sharedBounds(
            sharedContentState = sharedState,
            animatedVisibilityScope = visibility,
            enter = enter,
            exit = exit,
            boundsTransform = if (useCapsule) CapsuleFlightBoundsTransform else HeaderCardBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            zIndexInOverlay = if (active && useCapsule) 0.5f else 0f,
            clipInOverlayDuringTransition = OverlayClip(if (useCapsule) CapsuleFlightShape else HeaderCardShape),
        )
        content(cardModifier, Modifier.skipToLookaheadSize())
    }
}

/**
 * Wrapper around the gallery detail screen's glass top app bar. When [active] (the user has scrolled past the top
 * header card so the top bar is in its glass state), returning to the originating list morphs the glass top bar into
 * a floating Liquid Glass capsule that refracts the revealed list page and lands back into the originating card.
 * Keeps the outer [Box] stable so toggling [active] during scrolling never recreates the top bar.
 */
@Composable
fun CardFlightTopBarContainer(
    gid: Long,
    active: Boolean,
    content: @Composable (contentModifier: Modifier) -> Unit,
) {
    val visibility = LocalPageVisibility.current
    val detailEntry = currentPageEntry()
    val key = CardFlight.targetKey(gid, detailEntry)
    if (visibility == null || key == null) {
        content(Modifier)
        return
    }
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    val flightActive = CardFlight.isActiveDetail(gid, detailEntry)
    with(shared) {
        val contentAlpha by visibility.transition.animateFloat(
            transitionSpec = {
                if (targetState == EnterExitState.Visible) {
                    tween(durationMillis = 1)
                } else {
                    tween(
                        durationMillis = CapsuleContentExitMillis,
                        easing = FastOutLinearInEasing,
                    )
                }
            },
            label = "cardTopBarContentAlpha",
        ) { state ->
            if (state == EnterExitState.Visible) 1f else 0f
        }
        val capsuleAlpha by visibility.transition.animateFloat(
            transitionSpec = {
                if (targetState == EnterExitState.Visible) {
                    tween(durationMillis = 1)
                } else {
                    tween(
                        durationMillis = CapsuleGlassFadeInMillis,
                        easing = LinearOutSlowInEasing,
                    )
                }
            },
            label = "cardTopBarCapsuleAlpha",
        ) { state ->
            if (state == EnterExitState.Visible) 0f else 1f
        }
        val boundsModifier = if (active) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key),
                animatedVisibilityScope = visibility,
                enter = EnterTransition.None,
                exit = ExitTransition.None,
                boundsTransform = CapsuleFlightBoundsTransform,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                clipInOverlayDuringTransition = OverlayClip(CapsuleFlightShape),
            )
        } else {
            Modifier
        }
        Box(modifier = Modifier.fillMaxWidth().then(boundsModifier)) {
            val topBarModifier = if (active && flightActive) {
                Modifier
                    .skipToLookaheadSize()
                    .graphicsLayer { alpha = contentAlpha }
            } else {
                Modifier
            }
            content(topBarModifier)
            if (active && flightActive && capsuleAlpha > 0f) {
                val sourceBackdrop = CardFlight.sourceBackdropFor(detailEntry, gid)
                val glass = rememberLiquidGlass(sourceBackdrop)
                val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                val glassTint = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                    alpha = if (dark) CapsuleDarkGlassTint else CapsuleLightGlassTint,
                )
                val sheenTop = Color.White.copy(alpha = if (dark) 0.14f else 0.36f)
                val sheenMid = Color.White.copy(alpha = if (dark) 0.03f else 0.08f)
                val sheenBottom = Color.White.copy(alpha = if (dark) 0.08f else 0.22f)
                val borderColor = if (dark) {
                    Color.White.copy(alpha = if (glass != null) 0.20f else DARK_GLASS_EDGE)
                } else {
                    Color.White.copy(alpha = if (glass != null) 0.50f else 0.72f)
                }
                val fallbackFill = if (dark) {
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.84f)
                }
                val surfaceModifier = if (glass != null) {
                    Modifier.floatingGlass(glass, CapsuleFlightShape, glassTint, GlassStyle.Panel)
                } else {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = fallbackFill,
                            cornerRadius = CornerRadius(CapsuleCornerRadius.toPx()),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = capsuleAlpha }
                        .then(surfaceModifier)
                        .drawBehind {
                            // Subtle specular highlight sheen so the capsule reads as luminous glass even over AMOLED black
                            drawRoundRect(
                                brush = Brush.verticalGradient(listOf(sheenTop, sheenMid, sheenBottom)),
                                cornerRadius = CornerRadius(CapsuleCornerRadius.toPx()),
                            )
                        }
                        .border(1.dp, borderColor, CapsuleFlightShape),
                )
            }
        }
    }
}

/**
 * Target modifier on [GalleryDetailHeaderCard] when [active] (header card visible near the top of the detail page).
 */
@Composable
fun Modifier.cardFlightHeaderTarget(gid: Long, active: Boolean): Modifier {
    if (!active) return this
    val visibility = LocalPageVisibility.current ?: return this
    val detailEntry = currentPageEntry()
    val key = CardFlight.targetKey(gid, detailEntry) ?: return this
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    val flightActive = CardFlight.isActiveDetail(gid, detailEntry)
    return with(shared) {
        val sharedState = rememberSharedContentState(key)
        this@cardFlightHeaderTarget.sharedBounds(
            sharedContentState = sharedState,
            animatedVisibilityScope = visibility,
            enter = if (flightActive) HeaderCardEnter else EnterTransition.None,
            exit = if (flightActive) HeaderCardExit else ExitTransition.None,
            boundsTransform = HeaderCardBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(HeaderCardShape),
        )
    }
}

/** Keeps the content of [GalleryDetailHeaderCard] at its natural lookahead size during header-card morph. */
@Composable
fun Modifier.cardFlightHeaderContent(): Modifier {
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    return with(shared) { this@cardFlightHeaderContent.skipToLookaheadSize() }
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

const val CardFlightDurationMillis = 380
const val CardFlightDetailFadeInMillis = 220
val CardFlightDetailPopFadeEasing = CubicBezierEasing(0.0f, 0.85f, 0.2f, 1.0f)
private const val CapsuleContentExitMillis = 55
private const val CapsuleContentEnterDelayMillis = 230
private const val CapsuleContentEnterMillis = 150
private const val CapsuleGlassFadeInMillis = 35
private const val CapsuleDarkGlassTint = 0.24f
private const val CapsuleLightGlassTint = 0.34f
private val CapsuleCornerRadius = 28.dp
private val CapsuleFlightShape = RoundedCornerShape(CapsuleCornerRadius)
private val CapsuleSourceEnter = fadeIn(
    tween(
        durationMillis = CapsuleContentEnterMillis,
        delayMillis = CapsuleContentEnterDelayMillis,
        easing = LinearOutSlowInEasing,
    ),
)
private val CapsuleSourceExit = fadeOut(
    tween(
        durationMillis = CapsuleContentExitMillis,
        easing = FastOutLinearInEasing,
    ),
)
private val CapsuleFlightBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = CardFlightDurationMillis, easing = FastOutSlowInEasing)
}

private const val HeaderCardDurationMillis = 380
private const val HeaderCardArcSteps = 16
private const val HeaderCardArcExponent = 1.6f
private const val HeaderCardEnterMillis = 200
private const val HeaderCardExitDelayMillis = 80
private const val HeaderCardExitMillis = 180
private val HeaderCardEnter = fadeIn(
    tween(
        durationMillis = HeaderCardEnterMillis,
        easing = LinearOutSlowInEasing,
    ),
)
private val HeaderCardExit = fadeOut(
    tween(
        durationMillis = HeaderCardExitMillis,
        delayMillis = HeaderCardExitDelayMillis,
        easing = FastOutLinearInEasing,
    ),
)
private val HeaderCardShape = ShapeDefaults.Medium

/**
 * Symmetric reversed-path arc transform for the gallery card <-> top header card morph:
 * - Forward (List -> Detail): horizontal leads (`1 - (1 - s)^k`) while vertical trails (`s^k`), sweeping smoothly
 *   from bottom-left -> right -> top.
 * - Return (Detail -> List): vertical leads (`1 - (1 - s)^k`) while horizontal trails (`s^k`), tracing the exact
 *   same geometric curve in reverse from top-right -> down -> left.
 */
private val HeaderCardBoundsTransform = BoundsTransform { initialBounds, targetBounds ->
    val forward = CardFlight.isForward
    keyframes {
        durationMillis = HeaderCardDurationMillis
        for (step in 1 until HeaderCardArcSteps) {
            val u = step.toFloat() / HeaderCardArcSteps
            val s = FastOutSlowInEasing.transform(u)
            val lead = 1f - (1f - s).pow(HeaderCardArcExponent)
            val lag = s.pow(HeaderCardArcExponent)
            val fx = if (forward) lead else lag
            val fy = if (forward) lag else lead
            Rect(
                left = lerp(initialBounds.left, targetBounds.left, fx),
                top = lerp(initialBounds.top, targetBounds.top, fy),
                right = lerp(initialBounds.right, targetBounds.right, fx),
                bottom = lerp(initialBounds.bottom, targetBounds.bottom, fy),
            ) at (HeaderCardDurationMillis * u).toInt() using LinearEasing
        }
    }
}
