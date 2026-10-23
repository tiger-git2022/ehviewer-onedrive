package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarDefaults.InputField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.ehviewer.core.database.dao.SearchDao
import com.ehviewer.core.database.model.Search
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.ui.component.FloatingGlass
import com.ehviewer.core.ui.component.GlassStyle
import com.ehviewer.core.ui.component.LocalTileGlass
import com.ehviewer.core.ui.component.floatingGlass
import com.ehviewer.core.ui.util.thenIf
import com.hippo.ehviewer.EhApplication.Companion.searchDatabase
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.sync.OneDriveSync
import com.hippo.ehviewer.sync.SyncCategory
import com.hippo.ehviewer.ui.LocalPageBackdrop
import com.hippo.ehviewer.ui.destinations.ImageSearchScreenDestination
import com.hippo.ehviewer.ui.glassContent
import com.hippo.ehviewer.ui.glassOnSelectedColor
import com.hippo.ehviewer.ui.glassSelectedColor
import com.hippo.ehviewer.ui.main.LocalRefreshIndicatorHost
import com.hippo.ehviewer.ui.main.OneDriveSyncProgressBanner
import com.hippo.ehviewer.ui.main.RefreshIndicator
import com.hippo.ehviewer.ui.main.RefreshIndicatorHost
import com.hippo.ehviewer.ui.rememberLiquidGlass
import com.hippo.ehviewer.ui.theme.scrim
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.ui.tools.rememberCompositionActiveState
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import moe.tarsin.navigate

fun interface SuggestionProvider {
    suspend fun providerSuggestions(text: String): List<Suggestion>
}

abstract class Suggestion {
    abstract val keyword: String
    open val hint: String? = null
    abstract fun onClick()
    open val canDelete: Boolean = false
    open val canOpenDirectly: Boolean = false
}

suspend fun SearchDao.suggestions(prefix: String, limit: Int) = (if (prefix.isBlank()) list(limit) else rawSuggestions(prefix, limit))

@Composable
context(_: DialogState, _: DestinationsNavigator)
fun SearchBarScreen(
    onApplySearch: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    title: String?,
    searchFieldHint: String,
    searchFieldState: TextFieldState = rememberTextFieldState(),
    suggestionProvider: SuggestionProvider? = null,
    localSearch: Boolean = true,
    searchBarOffsetY: () -> Int = { 0 },
    trailingIcon: @Composable () -> Unit = {},
    // Fork: clears the search from the collapsed search bar, without opening the keyboard; null without a search
    onClearSearch: (() -> Unit)? = null,
    // Fork: before the clear button of the expanded search bar
    expandedTrailingIcon: @Composable () -> Unit = {},
    filter: @Composable (() -> Unit)? = null,
    floatingActionButton: @Composable () -> Unit = {},
    // Fork: the progress of a OneDrive sync below the search bar
    showSyncProgress: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    var mSuggestionList by remember { mutableStateOf(emptyList<Suggestion>()) }
    val mSearchDatabase = searchDatabase.searchDao()
    val scope = rememberCoroutineScope { Dispatchers.IO }
    val context = LocalContext.current
    val animateItems by Settings.animateItems.collectAsState()

    class TagSuggestion(
        override val hint: String?,
        override val keyword: String,
    ) : Suggestion() {
        override fun onClick() {
            val query = searchFieldState.text.toString()
            val (index, keyword) = if (localSearch) {
                query.lastIndexOf(' ') to
                    "${keyword.substringAfter(':')} "
            } else {
                // Fork: typing in an open quote, the suggestion takes the place of the whole tag, see SearchQuery.kt
                tagSuggestionIndex(query) to
                    if (keyword.endsWith(':')) keyword else "${wrapTagKeyword(keyword)} "
            }
            val keywords = if (index == -1) {
                keyword
            } else {
                "${query.substring(0, index + 1).trimEnd()} $keyword"
            }
            searchFieldState.setTextAndPlaceCursorAtEnd(keywords)
        }
    }

    class KeywordSuggestion(
        override val keyword: String,
        // Fork: translations of its tags
        override val hint: String? = null,
    ) : Suggestion() {
        override val canDelete = true
        override fun onClick() {
            searchFieldState.setTextAndPlaceCursorAtEnd(keyword)
        }
    }

    fun mergedSuggestionFlow(): Flow<Suggestion> = with(context) {
        flow {
            val query = searchFieldState.text.toString()
            suggestionProvider?.run { providerSuggestions(query).forEach { emit(it) } }
            val translate = Settings.showTagTranslations.value
            mSearchDatabase.suggestions(query, 128).forEach { emit(KeywordSuggestion(it, if (translate) translateTagQuery(it) else null)) }
            val index = if (localSearch) query.lastIndexOf(' ') else query.lastIndexOfAny(TagTerminators)
            val keyword = query.substring(index + 1).trimStart()
            if (keyword.isNotEmpty()) {
                EhTagDatabase.suggestion(keyword, Settings.showTagTranslations.value).take(50)
                    .forEach { emit(TagSuggestion(it.hint, it.tag)) }
            }
        }
    }

    suspend fun updateSuggestions() {
        mSuggestionList = mergedSuggestionFlow().toList()
    }

    if (expanded) {
        LaunchedEffect(Unit) {
            snapshotFlow { searchFieldState.text }.collectLatest {
                updateSuggestions()
            }
        }
    }

    fun hideSearchView() {
        onExpandedChange(false)
    }

    fun onApplySearch() {
        // May have invalid whitespaces if pasted from clipboard, replace them with spaces
        // Fork: with its quoted tags closed, see SearchQuery.kt
        val query = closeTagQuotes(searchFieldState.text.trim().replace(WhitespaceRegex, " "))
        if (query.isNotEmpty()) {
            scope.launch {
                mSearchDatabase.deleteQuery(query)
                val search = Search(System.currentTimeMillis(), query)
                mSearchDatabase.insert(search)
                OneDriveSync.notifyChanged(SyncCategory.SUGGESTIONS)
            }
        }
        onApplySearch(query)
    }

    fun deleteKeyword(keyword: String) {
        scope.launch {
            awaitConfirmationOrCancel(confirmText = R.string.delete) {
                Text(text = stringResource(id = R.string.delete_search_history, keyword))
            }
            mSearchDatabase.deleteQuery(keyword)
            OneDriveSync.notifyChanged(SyncCategory.SUGGESTIONS)
            updateSuggestions()
        }
    }

    // Fork: height of the sync progress below the search bar, the content leaves room for it
    var syncProgressHeight by remember { mutableIntStateOf(0) }
    // Fork: liquid glass refracting the list, see LiquidGlass.kt
    val backdrop = LocalPageBackdrop.current
    val glass = rememberLiquidGlass(backdrop)
    val refreshHost = remember { RefreshIndicatorHost() }
    val glassShape = SearchBarDefaults.inputFieldShape
    val glassTint = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = GLASS_BAR_TINT)
    val expandedGlassTint = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f)
    // Fork: the expanded search grows out of the search field like the Material search bar, in step with it
    val expandProgress = remember { Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(expanded) {
        expandProgress.animateTo(if (expanded) 1f else 0f, if (expanded) ExpandSpec else CollapseSpec)
    }
    // Closing, the field takes its glass once the blurred glass has shrunk back into it: while it shrank, the outline
    // of the field showed already where the glass was still coming back
    val shrunkBack by remember { derivedStateOf { expandProgress.value == 0f } }
    // The blurred glass of the expanded search, for its cards and chips to refract
    val frostedBackdrop = rememberLayerBackdrop()
    val tileGlass = rememberLiquidGlass(frostedBackdrop.takeIf { glass != null })
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Column {
                    val scrim = MaterialTheme.colorScheme.background.scrim()
                    // Fork: with liquid glass, the list goes on below the status bar
                    Box(Modifier.windowInsetsTopHeight(WindowInsets.statusBars).fillMaxWidth().thenIf(glass == null) { background(scrim) })

                    // Placeholder, fill immutable SearchBar padding
                    Spacer(modifier = Modifier.height(SearchBarDefaults.InputFieldHeight + 16.dp))
                    // Fork
                    if (showSyncProgress) Spacer(modifier = Modifier.height(with(LocalDensity.current) { syncProgressHeight.toDp() }))
                }
            },
            floatingActionButton = floatingActionButton,
            content = if (backdrop == null) {
                content
            } else {
                @Composable { padding: PaddingValues ->
                    CompositionLocalProvider(LocalRefreshIndicatorHost provides refreshHost) {
                        Box(Modifier.fillMaxSize().glassContent(backdrop)) { content(padding) }
                    }
                }
            },
        )
        // Fork: the pull to refresh of the list, over the content the glass refracts so it is glass itself, see
        // RefreshIndicatorHost
        if (backdrop != null) {
            refreshHost.state?.let { state ->
                RefreshIndicator(
                    state = state,
                    isRefreshing = refreshHost.isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = refreshHost.top),
                    glass = glass,
                    offsetY = refreshHost.offsetY,
                )
            }
        }
        // Fork: below the search bar, it stays below the status bar when the search bar scrolls away
        if (showSyncProgress && !expanded) {
            val statusBarTop = WindowInsets.statusBars.getTop(LocalDensity.current)
            OneDriveSyncProgressBanner(
                onHeightChanged = { syncProgressHeight = it },
                glass = glass,
                modifier = Modifier.align(Alignment.TopCenter)
                    .offset {
                        val below = statusBarTop + (SearchBarDefaults.InputFieldHeight + 16.dp).roundToPx()
                        IntOffset(0, maxOf(statusBarTop, below + searchBarOffsetY()))
                    }
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                    .widthIn(max = (maxWidth - SearchBarHorizontalPadding * 2).coerceAtMost(M3SearchBarMaxWidth))
                    .fillMaxWidth(),
            )
        }
        // Fork: expanded, the whole screen behind the search is a blurred glass over the list. Its own layer, not the
        // search bar's: the bar is as wide as the field, it left a strip unblurred at each side. It grows out of the
        // floating search field with the search bar's own animation and shrinks back into it, its lit edge fading as it
        // fills the screen; the cards and the chips of the search refract it (frostedBackdrop)
        if (glass != null) {
            ExpandedSearchGlass(
                expanded = expanded,
                progress = expandProgress,
                glass = glass,
                backdrop = frostedBackdrop,
                fieldMaxWidth = (maxWidth - SearchBarHorizontalPadding * 2).coerceAtMost(M3SearchBarMaxWidth),
                tint = glassTint,
                expandedTint = expandedGlassTint,
            )
        }
        // https://issuetracker.google.com/337191298
        // Workaround for can't exit SearchBar due to refocus in non-touch mode
        Box(Modifier.size(1.dp).focusable())
        val activeState = rememberCompositionActiveState()
        SearchBar(
            modifier = Modifier.align(Alignment.TopCenter).thenIf(!expanded) { offset { IntOffset(0, searchBarOffsetY()) } }
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
            inputField = {
                InputField(
                    state = searchFieldState,
                    onSearch = {
                        hideSearchView()
                        onApplySearch()
                    },
                    expanded = expanded,
                    onExpandedChange = onExpandedChange,
                    modifier = Modifier.widthIn(max = (maxWidth - SearchBarHorizontalPadding * 2).coerceAtMost(M3SearchBarMaxWidth)).fillMaxWidth()
                        // Fork: the field is the glass while the search bar is collapsed
                        .thenIf(!expanded && shrunkBack) { floatingGlass(glass, glassShape, glassTint) },
                    // Fork: over the glass, the hint, the title and the buttons in the color of the text, white at night
                    // and black by day: the paler Material color got lost over the list showing through
                    colors = if (glass != null) {
                        val onGlass = MaterialTheme.colorScheme.onSurface
                        SearchBarDefaults.inputFieldColors(
                            focusedLeadingIconColor = onGlass,
                            unfocusedLeadingIconColor = onGlass,
                            focusedTrailingIconColor = onGlass,
                            unfocusedTrailingIconColor = onGlass,
                            focusedPlaceholderColor = onGlass,
                            unfocusedPlaceholderColor = onGlass,
                        )
                    } else {
                        SearchBarDefaults.inputFieldColors()
                    },
                    placeholder = {
                        val contentActive by activeState.state
                        val text = title.takeUnless { expanded || contentActive } ?: searchFieldHint
                        Text(text, overflow = TextOverflow.Ellipsis, maxLines = 1)
                    },
                    // Fork: no menu button on the collapsed bar, the left drawer opens with a swipe; the room goes to
                    // the buttons on the right
                    leadingIcon = if (expanded) {
                        {
                            IconButton(onClick = { hideSearchView() }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
                            }
                        }
                    } else {
                        null
                    },
                    trailingIcon = {
                        if (expanded) {
                            Row {
                                expandedTrailingIcon()
                                AnimatedContent(targetState = searchFieldState.text.isNotEmpty()) { hasText ->
                                    if (hasText) {
                                        IconButton(onClick = { searchFieldState.clearText() }, shapes = IconButtonDefaults.shapes()) {
                                            Icon(Icons.Default.Close, contentDescription = null)
                                        }
                                    } else {
                                        IconButton(onClick = { navigate(ImageSearchScreenDestination) }, shapes = IconButtonDefaults.shapes()) {
                                            Icon(Icons.Default.ImageSearch, contentDescription = null)
                                        }
                                    }
                                }
                            }
                        } else {
                            Row {
                                if (onClearSearch != null) {
                                    IconButton(
                                        onClick = {
                                            searchFieldState.clearText()
                                            onClearSearch()
                                        },
                                        shapes = IconButtonDefaults.shapes(),
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
                                    }
                                }
                                trailingIcon()
                            }
                        }
                    },
                )
            },
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            // Fork: collapsed, the field draws the glass, the bar nothing; expanded, the suggestions keep their color
            colors = if (glass != null) {
                SearchBarDefaults.colors(containerColor = Color.Transparent, dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            } else {
                SearchBarDefaults.colors()
            },
        ) {
            activeState.Anchor()
            // Fork: the cards and the chips are glass refracting the blurred glass behind the search
            CompositionLocalProvider(LocalTileGlass provides tileGlass) {
                if (glass != null && filter != null) {
                    // Fork: over the glass, a selected chip is a light in the glass
                    val colors = MaterialTheme.colorScheme
                    MaterialTheme(colorScheme = colors.copy(secondaryContainer = glassSelectedColor(), onSecondaryContainer = glassOnSelectedColor())) {
                        filter()
                    }
                } else {
                    filter?.invoke()
                }
                // Fork: two staggered columns, each suggestion goes to the shorter one (like the Mihon fork's history)
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalItemSpacing = 8.dp,
                ) {
                    // Workaround for prepending before the first item
                    item(span = StaggeredGridItemSpan.FullLine) {}
                    items(mSuggestionList, key = { it.keyword.hashCode() * 31 + it.canDelete.hashCode() }) {
                        SearchSuggestionCell(
                            suggestion = it,
                            onDelete = { deleteKeyword(it.keyword) },
                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Fork: the blurred glass behind the expanded search. It grows out of the field of the collapsed search bar as
 * [progress] goes to 1 and shrinks back into it, its lit edge fading as it fills the screen. It reads [progress] here,
 * so only this glass follows the animation frame by frame, not the whole search.
 */
@Composable
private fun ExpandedSearchGlass(
    expanded: Boolean,
    progress: Animatable<Float, AnimationVector1D>,
    glass: FloatingGlass,
    backdrop: LayerBackdrop,
    fieldMaxWidth: Dp,
    tint: Color,
    expandedTint: Color,
) {
    val fraction = progress.value
    if (!expanded && fraction == 0f) return
    val searchBarInsets = SearchBarDefaults.windowInsets
    val navigationBars = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
    val shape = RoundedCornerShape(SearchBarDefaults.InputFieldHeight / 2 * (1 - fraction))
    // The lit edge of the floating search bar, at night; by day the bar has none
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Box(
        Modifier
            .layout { measurable, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                // Where the field of the collapsed search bar lies, at the top of the screen
                val navigationLeft = navigationBars.getLeft(this, layoutDirection)
                val available = width - navigationLeft - navigationBars.getRight(this, layoutDirection)
                val fieldWidth = minOf(fieldMaxWidth.roundToPx(), available)
                val fieldLeft = navigationLeft + (available - fieldWidth) / 2
                val fieldTop = searchBarInsets.getTop(this) + SearchBarVerticalPadding.roundToPx()
                val left = lerp(fieldLeft, 0, fraction)
                val top = lerp(fieldTop, 0, fraction)
                val right = lerp(fieldLeft + fieldWidth, width, fraction)
                val bottom = lerp(fieldTop + SearchBarDefaults.InputFieldHeight.roundToPx(), height, fraction)
                val placeable = measurable.measure(Constraints.fixed(right - left, bottom - top))
                layout(width, height) { placeable.place(left, top) }
            }
            .layerBackdrop(backdrop)
            .floatingGlass(glass, shape, lerp(tint, expandedTint, fraction), GlassStyle.Backdrop)
            .thenIf(dark && fraction < 1f) { border(1.5.dp, Color.White.copy(alpha = 0.5f * (1 - fraction)), shape) },
    )
}

fun wrapTagKeyword(keyword: String, translate: Boolean = false): String = run {
    val tag = keyword.substringAfter(':')
    val prefix = keyword.dropLast(tag.length + 1)
    if (translate) {
        val namespacePrefix = TagNamespace.from(prefix)?.prefix
        val newPrefix = EhTagDatabase.getTranslation(tag = prefix) ?: prefix
        val newTag = EhTagDatabase.getTranslation(namespacePrefix, tag) ?: tag
        "$newPrefix：$newTag"
    } else if (keyword.contains(' ')) {
        "$prefix:\"$tag$\""
    } else {
        "$keyword$"
    }
}

private val TagTerminators = charArrayOf('"', '$')
private val WhitespaceRegex = Regex("\\s+")
private val SearchBarHorizontalPadding = 16.dp
// Fork: the animation of the Material search bar (SearchBar.kt), for the glass growing with it
private val SearchBarVerticalPadding = 8.dp
private val ExpandSpec = tween<Float>(durationMillis = 600, delayMillis = 100, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f))
private val CollapseSpec = tween<Float>(durationMillis = 350, delayMillis = 100, easing = CubicBezierEasing(0f, 1f, 0f, 1f))

// Fork: how much of the bar color lies over the glass of the search bar and the sync progress
const val GLASS_BAR_TINT = 0.2f

private val M3SearchBarMaxWidth = 720.dp
