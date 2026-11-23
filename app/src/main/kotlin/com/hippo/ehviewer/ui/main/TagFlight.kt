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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.ui.util.TransitionsVisibilityScope
import com.hippo.ehviewer.ui.LocalSharedTransitionScope
import com.hippo.ehviewer.ui.screen.searchTagParts
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
        val coverModifier = Modifier.sharedElement(
            sharedContentState = rememberSharedContentState(coverKey),
            animatedVisibilityScope = visibility,
            boundsTransform = HeaderCardBoundsTransform,
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
 * Shared-element modifier on the cover image inside [GalleryDetailHeaderCard] when [active]. Scales the full cover
 * smoothly in lockstep with [HeaderCardBoundsTransform] above the morphing card so the cover is never clipped.
 */
@Composable
fun Modifier.cardFlightHeaderCover(gid: Long, active: Boolean): Modifier {
    if (!active) return this
    val visibility = LocalPageVisibility.current ?: return this
    val detailEntry = currentPageEntry()
    val coverKey = CardFlight.targetCoverKey(gid, detailEntry) ?: return this
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    return with(shared) {
        this@cardFlightHeaderCover.sharedElement(
            sharedContentState = rememberSharedContentState(coverKey),
            animatedVisibilityScope = visibility,
            boundsTransform = HeaderCardBoundsTransform,
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
 * Invisible top header-card anchor placed outside [Scaffold] when the real [GalleryDetailHeaderCard] has scrolled off
 * screen. It intercepts no touches and remains invisible while browsing; on return to the originating list it becomes
 * active so the cover and header card launch smoothly from the top of the screen along the exact same reversed arc as
 * an unscrolled return.
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
    val flightActive = CardFlight.isActiveDetail(info.gid, detailEntry)
    val exiting = visibility.transition.targetState != EnterExitState.Visible
    val showAnchor = flightActive && exiting
    Box(
        modifier = modifier.graphicsLayer { alpha = if (showAnchor) 1f else 0f },
    ) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxSize()
                .cardFlightHeaderTarget(info.gid, active = true),
        ) {
            Row(modifier = Modifier.cardFlightHeaderContent()) {
                EhThumbCard(
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
 * Fork: solid Material3 container-transform flight between the comments [CrystalCard] on
 * [com.hippo.ehviewer.ui.screen.GalleryDetailScreen] and [com.hippo.ehviewer.ui.screen.GalleryCommentsScreen].
 * Uses a pure solid surface container (no per-frame liquid glass shader) so the transition stays 120fps-smooth and
 * visually consistent with the solid comment card and comments screen.
 */
object CommentFlight {
    private const val MAX_ENTRIES = 16

    private class Entry(val gid: Long, val from: String, var to: String? = null)

    private val entries = mutableStateListOf<Entry>()
    private var activeFrom by mutableStateOf<String?>(null)
    private var activeTo by mutableStateOf<String?>(null)
    private var activeForward by mutableStateOf(true)

    val isForward: Boolean
        get() = activeForward

    /** Records that the comments card of [gid] on detail page [fromEntry] was tapped. */
    fun start(gid: Long, fromEntry: String?) {
        if (fromEntry == null) return
        entries.removeAll { it.from == fromEntry }
        entries.add(Entry(gid = gid, from = fromEntry))
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

    fun isActiveSource(gid: Long, fromEntry: String?): Boolean {
        if (fromEntry == null || activeFrom != fromEntry) return false
        val to = activeTo ?: return false
        return entries.lastOrNull { it.from == fromEntry && it.to == to }?.gid == gid
    }

    fun isActiveComments(gid: Long, commentsEntry: String?): Boolean {
        if (commentsEntry == null || activeTo != commentsEntry) return false
        val from = activeFrom ?: return false
        return entries.lastOrNull { it.from == from && it.to == commentsEntry }?.gid == gid
    }

    private fun keyOf(from: String, gid: Long) = "comment-flight:$from:$gid"
}

/** Source modifier on the comments [CrystalCard] in [com.hippo.ehviewer.ui.screen.GalleryDetailContent]. */
@Composable
fun Modifier.commentFlightCardSource(gid: Long): Modifier {
    val visibility = LocalPageVisibility.current ?: return this
    val pageEntry = currentPageEntry()
    val key = CommentFlight.sourceKey(gid, pageEntry) ?: return this
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    val active = CommentFlight.isActiveSource(gid, pageEntry)
    return with(shared) {
        this@commentFlightCardSource.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = visibility,
            enter = if (active) CommentSourceEnter else EnterTransition.None,
            exit = if (active) CommentSourceExit else ExitTransition.None,
            boundsTransform = CommentFlightBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(HeaderCardShape),
        )
    }
}

/**
 * Elevates the detail screen's top app bar above the returning comments card during Comments -> Detail pop so a
 * comment card partially tucked under the top bar settles smoothly underneath it instead of popping behind the bar
 * after the animation finishes.
 */
@Composable
fun Modifier.commentFlightDetailTopBarOverlay(): Modifier {
    val visibility = LocalPageVisibility.current ?: return this
    val pageEntry = currentPageEntry() ?: return this
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    val returningToDetail = !CommentFlight.isForward && CommentFlight.isActiveSourcePage(pageEntry)
    val overlayAlpha by visibility.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                tween(
                    durationMillis = CommentTopBarRevealMillis,
                    delayMillis = CommentTopBarRevealDelayMillis,
                    easing = LinearOutSlowInEasing,
                )
            } else {
                tween(durationMillis = 1)
            }
        },
        label = "commentDetailTopBarOverlayAlpha",
    ) { state ->
        if (state == EnterExitState.Visible) 1f else 0f
    }
    return with(shared) {
        this@commentFlightDetailTopBarOverlay
            .renderInSharedTransitionScopeOverlay(
                renderInOverlay = { isTransitionActive && !CommentFlight.isForward && CommentFlight.isActiveSourcePage(pageEntry) },
                zIndexInOverlay = 2f,
            )
            .graphicsLayer {
                if (returningToDetail && isTransitionActive) {
                    alpha = overlayAlpha
                }
            }
    }
}

/**
 * Full-screen solid-surface container for [com.hippo.ehviewer.ui.screen.GalleryCommentsScreen].
 * Expands bi-directionally from the detail screen's comment card and collapses smoothly back into it on return,
 * centered inside the morphing bounds so the top app bar never pulls toward the top-left corner.
 */
@Composable
fun CommentFlightContainer(
    gid: Long,
    content: @Composable (contentModifier: Modifier) -> Unit,
) {
    val visibility = LocalPageVisibility.current
    val commentsEntry = currentPageEntry()
    val key = CommentFlight.targetKey(gid, commentsEntry)
    if (visibility == null || key == null) {
        content(Modifier.fillMaxSize())
        return
    }
    val shared: SharedTransitionScope = LocalSharedTransitionScope.current
    val active = CommentFlight.isActiveComments(gid, commentsEntry)
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
    with(shared) {
        val contentAlpha by visibility.transition.animateFloat(
            transitionSpec = {
                if (targetState == EnterExitState.Visible) {
                    tween(
                        durationMillis = CommentContentEnterMillis,
                        delayMillis = CommentContentEnterDelayMillis,
                        easing = LinearOutSlowInEasing,
                    )
                } else {
                    tween(
                        durationMillis = CommentContentExitMillis,
                        easing = FastOutLinearInEasing,
                    )
                }
            },
            label = "commentContentAlpha",
        ) { state ->
            if (state == EnterExitState.Visible) 1f else 0f
        }
        val scrimAlpha by visibility.transition.animateFloat(
            transitionSpec = {
                if (targetState == EnterExitState.Visible) {
                    tween(
                        durationMillis = CommentScrimFadeOutMillis,
                        delayMillis = CommentContentEnterDelayMillis,
                        easing = LinearOutSlowInEasing,
                    )
                } else {
                    tween(
                        durationMillis = CommentScrimFadeInMillis,
                        easing = LinearOutSlowInEasing,
                    )
                }
            },
            label = "commentScrimAlpha",
        ) { state ->
            if (state == EnterExitState.Visible) 0f else 1f
        }
        Box(
            modifier = Modifier
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key),
                    animatedVisibilityScope = visibility,
                    enter = EnterTransition.None,
                    exit = ExitTransition.None,
                    boundsTransform = CommentFlightBoundsTransform,
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                    clipInOverlayDuringTransition = OverlayClip(HeaderCardShape),
                )
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (active && scrimAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawRoundRect(
                                color = surfaceColor.copy(alpha = scrimAlpha),
                                cornerRadius = CornerRadius(12.dp.toPx()),
                            )
                        },
                )
            }
            val contentModifier = if (active) {
                Modifier
                    .fillMaxSize()
                    .skipToLookaheadSize()
                    .graphicsLayer { alpha = contentAlpha }
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
const val CardFlightDetailFadeInMillis = 220
val CardFlightDetailPopFadeEasing = CubicBezierEasing(0.0f, 0.85f, 0.2f, 1.0f)

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

const val CommentFlightDurationMillis = 330
private const val CommentContentEnterDelayMillis = 110
private const val CommentContentEnterMillis = 180
private const val CommentContentExitMillis = 90
private const val CommentScrimFadeInMillis = 60
private const val CommentScrimFadeOutMillis = 160
private const val CommentTopBarRevealDelayMillis = 110
private const val CommentTopBarRevealMillis = 150
private val CommentSourceEnter = fadeIn(
    tween(
        durationMillis = 160,
        delayMillis = 170,
        easing = LinearOutSlowInEasing,
    ),
)
private val CommentSourceExit = fadeOut(
    tween(
        durationMillis = 90,
        easing = FastOutLinearInEasing,
    ),
)
private val CommentFlightBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = CommentFlightDurationMillis, easing = FastOutSlowInEasing)
}
