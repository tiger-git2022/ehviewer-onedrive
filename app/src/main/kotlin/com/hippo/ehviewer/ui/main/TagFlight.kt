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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.ui.util.TransitionsVisibilityScope
import com.hippo.ehviewer.ui.LocalSharedTransitionScope
import com.hippo.ehviewer.ui.screen.searchTagParts
import kotlin.math.pow
import kotlin.math.roundToInt

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
 * Fork: container-transform flight between a tapped gallery card (list/grid/download) and the top header card on
 * [com.hippo.ehviewer.ui.screen.GalleryDetailScreen]. Keeps a small history of active page pairs so nested stacks
 * (List -> Detail -> SimilarList -> Detail) each return cleanly to their own originating card, and never triggers on
 * Detail <-> SimilarList transitions.
 */
object CardFlight {
    private const val MAX_ENTRIES = 16

    private class Entry(val gid: Long, val from: String, var to: String? = null)

    // SnapshotStateList so the tapped card attaches its sharedBounds immediately before leaving
    private val entries = mutableStateListOf<Entry>()

    // Exact active List <-> Detail transition pair set by rememberEhNavAnim before frame 0 (eliminates isMatchFound delay)
    private var activeFrom by mutableStateOf<String?>(null)
    private var activeTo by mutableStateOf<String?>(null)
    private var activeForward by mutableStateOf(true)

    /** Whether the active List <-> Detail transition is entering Detail (true) or returning to the list (false). */
    val isForward: Boolean
        get() = activeForward

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

    /** Page-scoped shared-element key for the gallery cover image on list page [entry]. */
    fun sourceCoverKey(gid: Long, entry: String?): String? = entry?.let { coverKeyOf(it, gid) }

    /** Claims/returns the shared key for [GalleryDetailScreen] of [gid] on page [entry], or null if opened elsewhere. */
    fun targetKey(gid: Long, entry: String?): String? {
        if (entry == null) return null
        val existing = entries.lastOrNull { it.to == entry && it.gid == gid }
        if (existing != null) return keyOf(existing.from, existing.gid)
        val pending = entries.lastOrNull { it.to == null && it.gid == gid } ?: return null
        pending.to = entry
        return keyOf(pending.from, pending.gid)
    }

    /** Claims/returns the page-scoped cover shared-element key for [GalleryDetailScreen] of [gid] on page [entry]. */
    fun targetCoverKey(gid: Long, entry: String?): String? {
        if (entry == null) return null
        val existing = entries.lastOrNull { it.to == entry && it.gid == gid }
        if (existing != null) return coverKeyOf(existing.from, existing.gid)
        val pending = entries.lastOrNull { it.to == null && it.gid == gid } ?: return null
        pending.to = entry
        return coverKeyOf(pending.from, pending.gid)
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

    private fun keyOf(from: String, gid: Long) = "card-flight:$from:$gid"

    private fun coverKeyOf(from: String, gid: Long) = "card-cover:$from:$gid"
}

/**
 * Source wrapper for a gallery card in a list/grid/download screen:
 * morphs directly into [GalleryDetailHeaderCard] at the top of detail on enter (horizontal-lead arc) and morphs back
 * along the exact reversed path (vertical-lead arc) on return — even after scrolling down in detail. Its cover image
 * flies as a synchronized [sharedElement] above the card so the full cover scales smoothly without clipping.
 */
@Composable
fun CardFlightSource(
    gid: Long,
    content: @Composable (cardModifier: Modifier, contentModifier: Modifier, coverModifier: Modifier) -> Unit,
) {
    val visibility = LocalPageVisibility.current
    val pageEntry = currentPageEntry()
    val key = CardFlight.sourceKey(gid, pageEntry)
    val coverKey = CardFlight.sourceCoverKey(gid, pageEntry)
    if (visibility == null || key == null || coverKey == null) {
        content(Modifier, Modifier, Modifier)
        return
    }
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    with(shared) {
        val sharedState = rememberSharedContentState(key)
        val active = CardFlight.isActiveSource(gid, pageEntry)
        val cardModifier = Modifier.sharedBounds(
            sharedContentState = sharedState,
            animatedVisibilityScope = visibility,
            enter = if (active) HeaderCardEnter else EnterTransition.None,
            exit = if (active) HeaderCardExit else ExitTransition.None,
            boundsTransform = HeaderCardBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(HeaderCardShape),
        )
        val coverModifier = Modifier.sharedBounds(
            sharedContentState = rememberSharedContentState(coverKey),
            animatedVisibilityScope = visibility,
            enter = if (active) CoverFlightEnter else EnterTransition.None,
            exit = if (active) CoverFlightExit else ExitTransition.None,
            boundsTransform = HeaderCardBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Crop, Alignment.Center),
            zIndexInOverlay = 1f,
            clipInOverlayDuringTransition = OverlayClip(HeaderCardShape),
        )
        content(cardModifier, Modifier.skipToLookaheadSize(), coverModifier)
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

/**
 * Shared-bounds modifier on the cover image inside [GalleryDetailHeaderCard] when [active]. Uses overlapped crossfade
 * + [SharedTransitionScope.ResizeMode.scaleToBounds] above the morphing card so the existing decoded bitmap stays
 * visible for the first 70ms while Coil resolves the target image, preventing any blank-frame flash or clipping.
 */
@Composable
fun Modifier.cardFlightHeaderCover(gid: Long, active: Boolean): Modifier {
    if (!active) return this
    val visibility = LocalPageVisibility.current ?: return this
    val detailEntry = currentPageEntry()
    val coverKey = CardFlight.targetCoverKey(gid, detailEntry) ?: return this
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    val flightActive = CardFlight.isActiveDetail(gid, detailEntry)
    return with(shared) {
        this@cardFlightHeaderCover.sharedBounds(
            sharedContentState = rememberSharedContentState(coverKey),
            animatedVisibilityScope = visibility,
            enter = if (flightActive) CoverFlightEnter else EnterTransition.None,
            exit = if (flightActive) CoverFlightExit else ExitTransition.None,
            boundsTransform = HeaderCardBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Crop, Alignment.Center),
            zIndexInOverlay = 1f,
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

/**
 * Whether [com.hippo.ehviewer.ui.screen.GalleryDetailScreen] of [gid] is currently running the forward List -> Detail
 * [CardFlight] enter transition. While entering, [CardFlightScrolledHeaderAnchor] must stay unmounted so scrolling down
 * mid-enter never promotes the invisible anchor's cover/card into the shared transition overlay.
 */
@Composable
fun isCardFlightEntering(gid: Long): Boolean {
    val visibility = LocalPageVisibility.current ?: return false
    val detailEntry = currentPageEntry() ?: return false
    if (!CardFlight.isForward || !CardFlight.isActiveDetail(gid, detailEntry)) return false
    val shared = LocalSharedTransitionScope.current
    return shared.isTransitionActive || visibility.transition.currentState != visibility.transition.targetState
}

/**
 * Non-interactive, invisible top header-card anchor placed outside [Scaffold] when the real [GalleryDetailHeaderCard]
 * has scrolled off screen. Avoids Material [ElevatedCard]/[Surface] completely so it NEVER intercepts touches in the
 * upper quarter of the screen while browsing; on return to the originating list it becomes visible and launches the
 * full cover + card along the exact same reversed arc as an unscrolled return.
 */
@Composable
context(_: SharedTransitionScope, _: TransitionsVisibilityScope)
fun CardFlightScrolledHeaderAnchor(
    info: GalleryInfo,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!active) return
    val visibility = LocalPageVisibility.current ?: return
    val detailEntry = currentPageEntry()
    val shared = LocalSharedTransitionScope.current
    val flightActive = CardFlight.isActiveDetail(info.gid, detailEntry)
    val inTransition = shared.isTransitionActive || visibility.transition.currentState != visibility.transition.targetState
    if (CardFlight.isForward && flightActive && inTransition) return
    val exiting = visibility.transition.targetState != EnterExitState.Visible
    val showAnchor = flightActive && exiting
    val cardColor = MaterialTheme.colorScheme.surfaceContainerLow
    Box(
        modifier = modifier.graphicsLayer { alpha = if (showAnchor) 1f else 0f },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .cardFlightHeaderTarget(info.gid, active = true)
                .drawBehind {
                    drawRoundRect(
                        color = cardColor,
                        cornerRadius = CornerRadius(12.dp.toPx()),
                    )
                },
        ) {
            Row(modifier = Modifier.cardFlightHeaderContent()) {
                EhThumbImage(
                    key = remember(info.gid) { info },
                    modifier = Modifier
                        .cardFlightHeaderCover(info.gid, active = true)
                        .size(
                            dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_detail_thumb_width),
                            dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_detail_thumb_height),
                        ),
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Fork: unified solid Material3 container-transform flight between the comments [CrystalCard] on
 * [com.hippo.ehviewer.ui.screen.GalleryDetailScreen] and [com.hippo.ehviewer.ui.screen.GalleryCommentsScreen].
 * All comments (preview and non-preview alike) live inside one single shared container on each side and share the
 * exact same horizontal centering and vertical top-bar alignment (`lerp(cardTop + previewTopOffset, topBarHeight, p)`),
 * making inter-comment desync or overlap physically impossible.
 */
object CommentFlight {
    private const val MAX_ENTRIES = 16

    private class Entry(
        val gid: Long,
        val from: String,
        val cardHeightPx: Float,
        val previewTopOffsetPx: Float,
        var to: String? = null,
    ) {
        var targetTopBarHeightPx: Float = 0f
        var targetContainerHeightPx: Float = 0f
    }

    private val entries = mutableStateListOf<Entry>()
    private var activeFrom by mutableStateOf<String?>(null)
    private var activeTo by mutableStateOf<String?>(null)
    private var activeForward by mutableStateOf(true)

    val isForward: Boolean
        get() = activeForward

    /** Records that the comments card of [gid] on detail page [fromEntry] was tapped, with its resting height and top item offset. */
    fun start(
        gid: Long,
        fromEntry: String?,
        cardHeightPx: Float = 0f,
        previewTopOffsetPx: Float = 0f,
    ) {
        if (fromEntry == null) return
        entries.removeAll { it.from == fromEntry }
        entries.add(
            Entry(
                gid = gid,
                from = fromEntry,
                cardHeightPx = cardHeightPx,
                previewTopOffsetPx = previewTopOffsetPx,
            ),
        )
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    /** Always returns the page-scoped key for the comments card on detail page [entry] so lookahead bounds are ready. */
    fun sourceKey(gid: Long, entry: String?): String? = entry?.let { keyOf(it, gid) }

    /** Claims/returns the shared key for [GalleryCommentsScreen] of [gid] on page [entry]. */
    fun targetKey(gid: Long, entry: String?): String? {
        if (entry == null) return null
        val existing = entries.lastOrNull { it.to == entry && it.gid == gid }
        if (existing != null) return keyOf(existing.from, existing.gid)
        val pending = entries.lastOrNull { it.to == null && it.gid == gid } ?: return null
        pending.to = entry
        return keyOf(pending.from, pending.gid)
    }

    /** Records the resting top-bar height on [GalleryCommentsScreen] for accurate vertical alignment. */
    fun reportTargetTopBarHeight(gid: Long, commentsEntry: String?, heightPx: Float) {
        if (commentsEntry == null || heightPx <= 0f) return
        val match = entries.lastOrNull { it.to == commentsEntry && it.gid == gid }
            ?: entries.lastOrNull { it.to == null && it.gid == gid }
            ?: return
        match.targetTopBarHeightPx = heightPx
    }

    /** Records the full lookahead container height on [GalleryCommentsScreen] so source and target compute identical progress `p`. */
    fun reportTargetContainerHeight(gid: Long, commentsEntry: String?, heightPx: Float) {
        if (commentsEntry == null || heightPx <= 0f) return
        val match = entries.lastOrNull { it.to == commentsEntry && it.gid == gid }
            ?: entries.lastOrNull { it.to == null && it.gid == gid }
            ?: return
        match.targetContainerHeightPx = heightPx
    }

    /** Synchronously marks [fromId] <-> [toId] as the active Detail <-> Comments flight before frame 0. */
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

    fun clearActiveTransition() {
        if (activeFrom != null || activeTo != null) {
            activeFrom = null
            activeTo = null
        }
    }

    fun isActiveSourcePage(fromEntry: String?): Boolean = fromEntry != null && activeFrom == fromEntry && activeTo != null

    fun isActiveSource(gid: Long, fromEntry: String?): Boolean = activeSourceEntry(gid, fromEntry) != null

    fun isActiveComments(gid: Long, commentsEntry: String?): Boolean = activeCommentsEntry(gid, commentsEntry) != null

    internal fun topGapForSource(gid: Long, fromEntry: String?, fallbackTopBarHeightPx: Float): Float {
        val entry = activeSourceEntry(gid, fromEntry) ?: return 0f
        return topGapOf(entry, fallbackTopBarHeightPx)
    }

    internal fun topGapForComments(gid: Long, commentsEntry: String?, fallbackTopBarHeightPx: Float): Float {
        val entry = activeCommentsEntry(gid, commentsEntry) ?: return 0f
        return topGapOf(entry, fallbackTopBarHeightPx)
    }

    internal fun targetContainerHeightForSource(gid: Long, fromEntry: String?, fallbackHeightPx: Float): Float {
        val recorded = activeSourceEntry(gid, fromEntry)?.targetContainerHeightPx ?: 0f
        return if (recorded > 0f) recorded else fallbackHeightPx
    }

    internal fun cardHeightForComments(gid: Long, commentsEntry: String?): Float =
        activeCommentsEntry(gid, commentsEntry)?.cardHeightPx ?: 0f

    private fun activeSourceEntry(gid: Long, fromEntry: String?): Entry? {
        if (fromEntry == null || activeFrom != fromEntry) return null
        val to = activeTo ?: return null
        return entries.lastOrNull { it.from == fromEntry && it.to == to && it.gid == gid }
    }

    private fun activeCommentsEntry(gid: Long, commentsEntry: String?): Entry? {
        if (commentsEntry == null || activeTo != commentsEntry) return null
        val from = activeFrom ?: return null
        return entries.lastOrNull { it.from == from && it.to == commentsEntry && it.gid == gid }
    }

    private fun topGapOf(entry: Entry, fallbackTopBarHeightPx: Float): Float {
        val topBar = if (entry.targetTopBarHeightPx > 0f) entry.targetTopBarHeightPx else fallbackTopBarHeightPx
        return (topBar - entry.previewTopOffsetPx).coerceAtLeast(0f)
    }

    private fun keyOf(from: String, gid: Long) = "comment-flight:$from:$gid"
}

/**
 * While [isActive], measures the child at its exact full lookahead size (avoiding text reflow) while placing it
 * horizontally centered (`x = (containerWidth - lookaheadWidth) / 2`) and vertically offset by [verticalOffsetPx]
 * inside the animating container bounds in the same layout pass — eliminating both horizontal drift and 1-frame
 * vertical lag. At rest (`!isActive()`), passes constraints through normally so dynamic content growth (e.g.
 * asynchronous favorite badges or translations) is never clipped to a stale lookahead height.
 */
private fun Modifier.skipToLookaheadSizeCentered(
    isActive: () -> Boolean,
    verticalOffsetPx: ((liveHeightPx: Float, lookaheadHeightPx: Float) -> Float)? = null,
): Modifier = approachLayout(
    isMeasurementApproachInProgress = { isActive() },
) { measurable, constraints ->
    if (!isActive()) {
        val placeable = measurable.measure(constraints)
        return@approachLayout layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
        }
    }
    val placeable = measurable.measure(Constraints.fixed(lookaheadSize.width, lookaheadSize.height))
    val width = constraints.constrainWidth(lookaheadSize.width)
    val height = constraints.constrainHeight(lookaheadSize.height)
    val offsetY = verticalOffsetPx?.invoke(height.toFloat(), lookaheadSize.height.toFloat())?.roundToInt() ?: 0
    layout(width, height) {
        placeable.place((width - placeable.width) / 2, offsetY)
    }
}

/** Whether [GalleryCommentsScreen] of [gid] is currently running an active Detail <-> Comments container flight. */
@Composable
fun isCommentFlightTransitioning(gid: Long): Boolean {
    val visibility = LocalPageVisibility.current ?: return false
    val commentsEntry = currentPageEntry() ?: return false
    if (!CommentFlight.isActiveComments(gid, commentsEntry)) return false
    val shared = LocalSharedTransitionScope.current
    return shared.isTransitionActive || visibility.transition.currentState != visibility.transition.targetState
}

/** Whether the detail comments card of [gid] is currently running an active Detail <-> Comments container flight. */
@Composable
fun isCommentFlightSourceTransitioning(gid: Long): Boolean {
    val visibility = LocalPageVisibility.current ?: return false
    val pageEntry = currentPageEntry() ?: return false
    if (!CommentFlight.isActiveSource(gid, pageEntry)) return false
    val shared = LocalSharedTransitionScope.current
    return shared.isTransitionActive || visibility.transition.currentState != visibility.transition.targetState
}

/**
 * Unified source wrapper for the comments [CrystalCard] in [com.hippo.ehviewer.ui.screen.GalleryDetailContent]:
 * provides [cardModifier] for the card container and [contentModifier] for its inner [Column] so the preview comments
 * stay horizontally centered and vertically aligned to the exact same `lerp(cardTop + 4dp, topBarHeight, p)` trajectory
 * as [CommentFlightContainer]'s full comments list.
 */
@Composable
fun CommentFlightCardSource(
    gid: Long,
    content: @Composable (cardModifier: Modifier, contentModifier: Modifier) -> Unit,
) {
    val visibility = LocalPageVisibility.current
    val pageEntry = currentPageEntry()
    val key = CommentFlight.sourceKey(gid, pageEntry)
    if (visibility == null || key == null) {
        content(Modifier, Modifier.fillMaxWidth())
        return
    }
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    val active = CommentFlight.isActiveSource(gid, pageEntry)
    val density = LocalDensity.current
    val fallbackTopBarHeightPx = WindowInsets.statusBars.getTop(density) + with(density) { 64.dp.toPx() }
    val windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    val liveHeightRef = remember { FloatArray(1) }
    val progressState = visibility.transition.animateFloat(
        transitionSpec = { tween(durationMillis = CommentFlightDurationMillis, easing = FastOutSlowInEasing) },
        label = "commentSourceProgress",
    ) { state ->
        if (state == EnterExitState.Visible) 0f else 1f
    }
    val inTransition: () -> Boolean = {
        CommentFlight.isActiveSource(gid, pageEntry) &&
            (shared.isTransitionActive || visibility.transition.currentState != visibility.transition.targetState)
    }
    with(shared) {
        val cardModifier = Modifier
            .sharedBounds(
                sharedContentState = rememberSharedContentState(key),
                animatedVisibilityScope = visibility,
                enter = if (active) CommentSourceEnter else EnterTransition.None,
                exit = if (active) CommentSourceExit else ExitTransition.None,
                boundsTransform = CommentFlightBoundsTransform,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                clipInOverlayDuringTransition = OverlayClip(HeaderCardShape),
            )
            .layout { measurable, constraints ->
                liveHeightRef[0] = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else 0f
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.place(0, 0)
                }
            }
        val contentModifier = Modifier
            .fillMaxWidth()
            .skipToLookaheadSizeCentered(
                isActive = inTransition,
                verticalOffsetPx = { _, cardH ->
                    val liveH = liveHeightRef[0]
                    val fullH = CommentFlight.targetContainerHeightForSource(gid, pageEntry, windowHeightPx)
                    val span = fullH - cardH
                    val p = if (cardH > 0f && liveH > 0f && span > 1f) {
                        ((liveH - cardH) / span).coerceIn(0f, 1f)
                    } else {
                        progressState.value
                    }
                    p * CommentFlight.topGapForSource(gid, pageEntry, fallbackTopBarHeightPx)
                },
            )
        content(cardModifier, contentModifier)
    }
}

/**
 * Fades the "More comments" footer inside the detail comments [CrystalCard] cleanly during Detail <-> Comments morph
 * while it rides the card content's unified vertical trajectory.
 */
@Composable
fun Modifier.commentFlightMoreButton(gid: Long): Modifier {
    val visibility = LocalPageVisibility.current ?: return this
    val pageEntry = currentPageEntry() ?: return this
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    val active = CommentFlight.isActiveSource(gid, pageEntry)
    val buttonAlphaState = visibility.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                tween(
                    durationMillis = CommentMoreButtonEnterMillis,
                    delayMillis = CommentMoreButtonEnterDelayMillis,
                    easing = LinearOutSlowInEasing,
                )
            } else {
                tween(
                    durationMillis = CommentMoreButtonExitMillis,
                    easing = FastOutLinearInEasing,
                )
            }
        },
        label = "commentMoreButtonAlpha",
    ) { state ->
        if (state == EnterExitState.Visible) 1f else 0f
    }
    return with(shared) {
        this@commentFlightMoreButton.graphicsLayer {
            val inTransition = isTransitionActive || visibility.transition.currentState != visibility.transition.targetState
            if (active && inTransition) {
                alpha = buttonAlphaState.value
            }
        }
    }
}

/**
 * Elevates the detail screen's top app bar above the morphing comments card during Detail <-> Comments transitions so
 * both screens' top bars crossfade cleanly in place at the top of the screen, and returning comment cards tuck cleanly
 * underneath the detail bar.
 */
@Composable
fun Modifier.commentFlightDetailTopBarOverlay(): Modifier {
    val visibility = LocalPageVisibility.current ?: return this
    val pageEntry = currentPageEntry() ?: return this
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    val activeSourcePage = CommentFlight.isActiveSourcePage(pageEntry)
    val overlayAlphaState = visibility.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                tween(
                    durationMillis = CommentTopBarRevealMillis,
                    delayMillis = CommentTopBarRevealDelayMillis,
                    easing = LinearOutSlowInEasing,
                )
            } else {
                tween(
                    durationMillis = CommentTopBarExitMillis,
                    easing = FastOutLinearInEasing,
                )
            }
        },
        label = "commentDetailTopBarOverlayAlpha",
    ) { state ->
        if (state == EnterExitState.Visible) 1f else 0f
    }
    return with(shared) {
        this@commentFlightDetailTopBarOverlay
            .renderInSharedTransitionScopeOverlay(
                renderInOverlay = {
                    CommentFlight.isActiveSourcePage(pageEntry) &&
                        (isTransitionActive || visibility.transition.currentState != visibility.transition.targetState)
                },
                zIndexInOverlay = 2f,
            )
            .graphicsLayer {
                if (activeSourcePage && (isTransitionActive || visibility.transition.currentState != visibility.transition.targetState)) {
                    alpha = overlayAlphaState.value
                }
            }
    }
}

/**
 * Keeps [GalleryCommentsScreen]'s top bar anchored in place at the top of the screen with a clean crossfade instead of
 * riding the expanding container bounds from bottom-right.
 */
@Composable
fun Modifier.commentFlightCommentsTopBarOverlay(gid: Long): Modifier {
    val visibility = LocalPageVisibility.current ?: return this
    val commentsEntry = currentPageEntry() ?: return this
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    val active = CommentFlight.isActiveComments(gid, commentsEntry)
    val barAlphaState = visibility.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                tween(
                    durationMillis = CommentTopBarRevealMillis,
                    delayMillis = CommentTopBarRevealDelayMillis,
                    easing = LinearOutSlowInEasing,
                )
            } else {
                tween(
                    durationMillis = CommentTopBarExitMillis,
                    easing = FastOutLinearInEasing,
                )
            }
        },
        label = "commentCommentsTopBarOverlayAlpha",
    ) { state ->
        if (state == EnterExitState.Visible) 1f else 0f
    }
    return with(shared) {
        this@commentFlightCommentsTopBarOverlay
            .renderInSharedTransitionScopeOverlay(
                renderInOverlay = {
                    CommentFlight.isActiveComments(gid, commentsEntry) &&
                        (isTransitionActive || visibility.transition.currentState != visibility.transition.targetState)
                },
                zIndexInOverlay = 2f,
            )
            .graphicsLayer {
                if (active && (isTransitionActive || visibility.transition.currentState != visibility.transition.targetState)) {
                    alpha = barAlphaState.value
                }
            }
    }
}

/** Keeps [GalleryCommentsScreen]'s reply FAB anchored in place at bottom-right with a clean fade during container flight. */
@Composable
fun Modifier.commentFlightFabOverlay(gid: Long): Modifier {
    val visibility = LocalPageVisibility.current ?: return this
    val commentsEntry = currentPageEntry() ?: return this
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    val active = CommentFlight.isActiveComments(gid, commentsEntry)
    val fabAlphaState = visibility.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                tween(
                    durationMillis = CommentTopBarRevealMillis,
                    delayMillis = CommentTopBarRevealDelayMillis,
                    easing = LinearOutSlowInEasing,
                )
            } else {
                tween(
                    durationMillis = CommentTopBarExitMillis,
                    easing = FastOutLinearInEasing,
                )
            }
        },
        label = "commentFabOverlayAlpha",
    ) { state ->
        if (state == EnterExitState.Visible) 1f else 0f
    }
    return with(shared) {
        this@commentFlightFabOverlay
            .renderInSharedTransitionScopeOverlay(
                renderInOverlay = {
                    CommentFlight.isActiveComments(gid, commentsEntry) &&
                        (isTransitionActive || visibility.transition.currentState != visibility.transition.targetState)
                },
                zIndexInOverlay = 2f,
            )
            .graphicsLayer {
                if (active && (isTransitionActive || visibility.transition.currentState != visibility.transition.targetState)) {
                    alpha = fabAlphaState.value
                }
            }
    }
}

/**
 * Full-screen solid-surface container for the comments list body of [com.hippo.ehviewer.ui.screen.GalleryCommentsScreen].
 * Crossfades as a single unified layer with [CommentFlightCardSource] while keeping all comments (preview and
 * non-preview alike) inside one rigid layout pass, eliminating inter-comment desync or overlap.
 */
@Composable
fun CommentFlightContainer(
    gid: Long,
    topBarHeightPx: Float,
    content: @Composable (contentModifier: Modifier) -> Unit,
) {
    val visibility = LocalPageVisibility.current
    val commentsEntry = currentPageEntry()
    val key = CommentFlight.targetKey(gid, commentsEntry)
    SideEffect {
        if (topBarHeightPx > 0f) {
            CommentFlight.reportTargetTopBarHeight(gid, commentsEntry, topBarHeightPx)
        }
    }
    if (visibility == null || key == null) {
        content(Modifier.fillMaxSize())
        return
    }
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    val active = CommentFlight.isActiveComments(gid, commentsEntry)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val backgroundColor = MaterialTheme.colorScheme.background
    with(shared) {
        val progressState = visibility.transition.animateFloat(
            transitionSpec = { tween(durationMillis = CommentFlightDurationMillis, easing = FastOutSlowInEasing) },
            label = "commentFlightProgress",
        ) { state ->
            if (state == EnterExitState.Visible) 1f else 0f
        }
        val fullHeightRef = remember { FloatArray(1) }
        val inTransition: () -> Boolean = {
            isTransitionActive || visibility.transition.currentState != visibility.transition.targetState
        }
        fun progressOf(liveH: Float, fullH: Float): Float {
            val cardH = CommentFlight.cardHeightForComments(gid, commentsEntry)
            val span = fullH - cardH
            return if (cardH > 0f && liveH > 0f && span > 1f) {
                ((liveH - cardH) / span).coerceIn(0f, 1f)
            } else {
                progressState.value
            }
        }
        Box(
            modifier = Modifier
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key),
                    animatedVisibilityScope = visibility,
                    enter = if (active) CommentTargetEnter else EnterTransition.None,
                    exit = if (active) CommentTargetExit else ExitTransition.None,
                    boundsTransform = CommentFlightBoundsTransform,
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                    clipInOverlayDuringTransition = OverlayClip(HeaderCardShape),
                )
                .fillMaxSize()
                .drawBehind {
                    if (active && inTransition()) {
                        val p = progressOf(size.height, fullHeightRef[0])
                        drawRoundRect(
                            color = lerpColor(surfaceColor, backgroundColor, p),
                            cornerRadius = CornerRadius(lerp(12.dp.toPx(), 0f, p)),
                        )
                    }
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            val contentModifier = if (active) {
                Modifier
                    .fillMaxSize()
                    .skipToLookaheadSizeCentered(
                        isActive = inTransition,
                        verticalOffsetPx = { liveH, fullH ->
                            fullHeightRef[0] = fullH
                            if (fullH > 0f) {
                                CommentFlight.reportTargetContainerHeight(gid, commentsEntry, fullH)
                            }
                            val topGapPx = CommentFlight.topGapForComments(gid, commentsEntry, topBarHeightPx)
                            -(1f - progressOf(liveH, fullH)) * topGapPx
                        },
                    )
            } else {
                Modifier.fillMaxSize()
            }
            content(contentModifier)
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
private val QueryFlightCornerRadius = 20.dp
private val QueryFlightShape = RoundedCornerShape(QueryFlightCornerRadius)
private val QueryFlightBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = QueryFlightDurationMillis, easing = FastOutSlowInEasing)
}

const val CardFlightDurationMillis = 380
const val CardFlightDetailFadeInMillis = 240
val CardFlightDetailPopFadeEasing = CubicBezierEasing(0.2f, 0.55f, 0.35f, 1.0f)

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
private val CoverFlightEnter = fadeIn(
    tween(
        durationMillis = 120,
        delayMillis = 20,
        easing = LinearOutSlowInEasing,
    ),
)
private val CoverFlightExit = fadeOut(
    tween(
        durationMillis = 120,
        delayMillis = 70,
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

const val CommentFlightDurationMillis = 340
private const val CommentTopBarExitMillis = 100
private const val CommentTopBarRevealDelayMillis = 100
private const val CommentTopBarRevealMillis = 150
private const val CommentMoreButtonExitMillis = 90
private const val CommentMoreButtonEnterDelayMillis = 80
private const val CommentMoreButtonEnterMillis = 160
private val CommentSourceEnter = fadeIn(
    tween(
        durationMillis = 160,
        easing = LinearOutSlowInEasing,
    ),
)
private val CommentSourceExit = fadeOut(
    tween(
        durationMillis = 160,
        delayMillis = 60,
        easing = FastOutLinearInEasing,
    ),
)
private val CommentTargetEnter = fadeIn(
    tween(
        durationMillis = 160,
        easing = LinearOutSlowInEasing,
    ),
)
private val CommentTargetExit = fadeOut(
    tween(
        durationMillis = 160,
        delayMillis = 60,
        easing = FastOutLinearInEasing,
    ),
)
private val CommentFlightBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = CommentFlightDurationMillis, easing = FastOutSlowInEasing)
}
