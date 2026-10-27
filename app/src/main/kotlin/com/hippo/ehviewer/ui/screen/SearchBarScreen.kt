package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.util.lerp
import com.ehviewer.core.database.dao.SearchDao
import com.ehviewer.core.database.model.Search
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.ui.component.FloatingGlass
import com.ehviewer.core.ui.component.GlassStyle
import com.ehviewer.core.ui.component.LocalSearchExit
import com.ehviewer.core.ui.component.LocalTileGlass
import com.ehviewer.core.ui.component.floatingGlass
import com.ehviewer.core.ui.component.searchExitGlassAlpha
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

    // Fork: the tags of the search are chips before the text in the field, see SearchTags.kt. [searchFieldState] keeps
    // the whole search, for the screens setting and reading it; the field edits only the text after the tags
    val tagChips = !localSearch
    val inputState = if (tagChips) remember { TextFieldState(splitSearchTags(searchFieldState.text.toString()).second) } else searchFieldState
    var tags by remember { mutableStateOf(if (tagChips) splitSearchTags(searchFieldState.text.toString()).first else emptyList()) }
    // The last search written from the chips and the text: coming back, it is no search set from outside, even when the
    // text has changed again since
    val writtenQuery = remember { arrayOfNulls<String>(1) }
    // The chips shown: none in a local search, even if the favorites have just switched to one from a cloud category
    val shownTags = if (tagChips) tags else emptyList()
    if (tagChips) {
        // The search set from outside (a tag of a gallery, the history, clearing it): its tags become chips
        LaunchedEffect(Unit) {
            snapshotFlow { searchFieldState.text.toString() }.collect {
                // As it is now, it may have changed again since
                val query = searchFieldState.text.toString()
                if (query != writtenQuery[0] && query != joinSearchTags(tags, inputState.text)) {
                    val (newTags, text) = splitSearchTags(query)
                    tags = newTags
                    inputState.setTextAndPlaceCursorAtEnd(text)
                }
            }
        }
        // Edited in the field: a tag typed out becomes a chip once a space follows it
        LaunchedEffect(Unit) {
            snapshotFlow { tags to inputState.text.toString() }.collect { (current, text) ->
                if (text.isNotEmpty() && text.last().isWhitespace()) {
                    val (typedTags, rest) = splitSearchTags(text)
                    if (typedTags.isNotEmpty()) {
                        tags = current + typedTags
                        inputState.setTextAndPlaceCursorAtEnd(if (rest.isEmpty()) "" else "$rest ")
                        return@collect
                    }
                }
                val query = joinSearchTags(current, text)
                if (query != searchFieldState.text.toString()) {
                    writtenQuery[0] = query
                    searchFieldState.setTextAndPlaceCursorAtEnd(query)
                }
            }
        }
    }

    class TagSuggestion(
        override val hint: String?,
        override val keyword: String,
    ) : Suggestion() {
        override fun onClick() {
            if (tagChips && !keyword.endsWith(':')) {
                // Fork: the tag becomes a chip in the place of what was typed for it; a `-` typed before it excludes it
                val text = inputState.text.toString()
                val index = tagSuggestionIndex(text)
                val kept = text.take(index + 1).trimEnd()
                tags = tags + pickedSearchTag(wrapTagKeyword(keyword), text.substring(index + 1))
                inputState.setTextAndPlaceCursorAtEnd(if (kept.isEmpty()) "" else "$kept ")
                return
            }
            val query = inputState.text.toString()
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
            inputState.setTextAndPlaceCursorAtEnd(keywords)
        }
    }

    class KeywordSuggestion(
        override val keyword: String,
        // Fork: translations of its tags
        override val hint: String? = null,
        // Fork: in the other searches below those with the tags picked, its tags join the chips instead of taking the
        // place of the search, see searchHistoryByTags
        private val addsTags: Boolean = false,
    ) : Suggestion() {
        override val canDelete = true
        override fun onClick() {
            if (addsTags) {
                val (newTags, text) = pickSearchHistory(tags, keyword)
                tags = newTags
                inputState.setTextAndPlaceCursorAtEnd(text)
            } else {
                searchFieldState.setTextAndPlaceCursorAtEnd(keyword)
            }
        }
    }

    // Fork: a heading in the history, see searchHistoryByTags
    class SectionHeader(override val keyword: String) : Suggestion() {
        override fun onClick() = Unit
    }

    fun mergedSuggestionFlow(): Flow<Suggestion> = with(context) {
        flow {
            val query = searchFieldState.text.toString()
            suggestionProvider?.run { providerSuggestions(query).forEach { emit(it) } }
            val translate = Settings.showTagTranslations.value
            fun history(query: String, addsTags: Boolean = false) = KeywordSuggestion(query, if (translate) translateTagQuery(query) else null, addsTags)
            // Fork: the tag being typed is in the text after the chips, maybe with a `-` before it
            val text = inputState.text.toString()
            val chips = if (tagChips) tags else emptyList()
            if (chips.isEmpty()) {
                mSearchDatabase.suggestions(query, 128).forEach { emit(history(it)) }
            } else {
                // Fork: with tags picked, the history having them first (picked, it takes the place of the search), then
                // the rest of it (picked, its tags join the chips): another tag can still be taken from it
                val (withTags, others) = searchHistoryByTags(mSearchDatabase.list(128), chips, text)
                if (withTags.isNotEmpty()) emit(SectionHeader(getString(R.string.search_history_with_tags)))
                withTags.forEach { emit(history(it)) }
                if (others.isNotEmpty()) emit(SectionHeader(getString(R.string.search_history_others)))
                others.forEach { emit(history(it, addsTags = true)) }
            }
            val index = if (localSearch) text.lastIndexOf(' ') else text.lastIndexOfAny(TagTerminators)
            val keyword = text.substring(index + 1).trimStart().let { if (tagChips) it.trimStart('-', '~') else it }
            if (keyword.isNotEmpty()) {
                val tagSuggestions = EhTagDatabase.suggestion(keyword, Settings.showTagTranslations.value).take(50).toList()
                // Fork: not under the other searches
                if (chips.isNotEmpty() && tagSuggestions.isNotEmpty()) emit(SectionHeader(getString(R.string.search_tag_suggestions)))
                tagSuggestions.forEach { emit(TagSuggestion(it.hint, it.tag)) }
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
        // Fork: the chips and the text, as typed this very moment
        val typed = if (tagChips) joinSearchTags(tags, inputState.text) else searchFieldState.text
        val query = closeTagQuotes(typed.trim().replace(WhitespaceRegex, " "))
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
    // Fork: the height of the field, for the glass growing out of it and shrinking into it and for the cards below it:
    // with a larger font it is taller than InputFieldHeight, and the glass jumped to it at the end of the animations
    var fieldHeight by remember { mutableIntStateOf(0) }
    // Fork: liquid glass refracting the list, see LiquidGlass.kt
    val backdrop = LocalPageBackdrop.current
    val glass = rememberLiquidGlass(backdrop)
    val refreshHost = remember { RefreshIndicatorHost() }
    val glassShape = SearchBarDefaults.inputFieldShape
    val glassTint = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = GLASS_BAR_TINT)
    val expandedGlassTint = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f)
    // Fork: the expanded search grows out of the search field like the Material search bar, in step with it
    val expandProgress = remember { Animatable(if (expanded) 1f else 0f) }
    // Closing with liquid glass, the search bar is collapsed at once (a new one, see below): shrinking back, the field
    // showed the hint and its outline until the end. The cards and the chips leave one after another from the bottom up
    // and the blurred glass fades out after them, see SearchExit.kt
    // Counted in the composition that closes the search, not after it: for a frame, the wide field of the old search
    // bar showed the glass of the collapsed one. Plain values, a key only needs to change
    val closes = remember { intArrayOf(0) }
    val wasExpanded = remember { booleanArrayOf(expanded) }
    if (wasExpanded[0] && !expanded && glass != null) closes[0]++
    wasExpanded[0] = expanded
    val searchBarKey = closes[0]
    // Fork: closing, the field shrinks from the whole width of the screen into the floating field with its glass
    // (ClosingFieldGlass), the buttons of the open search fading out over the title and the buttons of the page fading
    // in; the field's own glass waits for it. A new one for each closing, so it starts there in the very composition
    // that closes the search
    val fieldMorph = remember(searchBarKey) { Animatable(if (searchBarKey == 0) 1f else 0f) }
    LaunchedEffect(fieldMorph) { fieldMorph.animateTo(1f, FieldMorphSpec) }
    val fieldMorphed by remember(fieldMorph) { derivedStateOf { fieldMorph.value == 1f } }
    LaunchedEffect(expanded) {
        expandProgress.animateTo(if (expanded) 1f else 0f, if (expanded) ExpandSpec else CollapseSpec)
    }
    // The blurred glass of the expanded search, for its cards and chips to refract
    val frostedBackdrop = rememberLayerBackdrop()
    val tileGlass = rememberLiquidGlass(frostedBackdrop.takeIf { glass != null })
    // Fork: the cards and the chips of the search, in the search bar without liquid glass; with it, over the search bar
    // (searchShown), where they stay while they leave
    val searchContent = @Composable {
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
            // Fork: two staggered columns, each suggestion goes to the shorter one (like the Mihon fork's history).
            // With liquid glass, no line below the field: scrolled, the cards fade out at the top of the list
            val gridState = rememberLazyStaggeredGridState()
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().thenIf(glass != null) { fadingTopEdge(gridState) }.padding(horizontal = 12.dp),
                state = gridState,
                contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalItemSpacing = 8.dp,
            ) {
                // Workaround for prepending before the first item
                item(span = StaggeredGridItemSpan.FullLine) {}
                items(
                    mSuggestionList,
                    key = { it.keyword.hashCode() * 31 + it.canDelete.hashCode() },
                    span = { if (it is SectionHeader) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane },
                ) {
                    if (it is SectionHeader) {
                        Text(
                            text = it.keyword,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.thenIf(animateItems) { animateItem() }.padding(start = 4.dp, top = 4.dp),
                        )
                    } else {
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
    val searchShown by remember { derivedStateOf { expandProgress.value > 0f } }
    val searchExit = remember(expanded) { { if (expanded) 0f else 1f - expandProgress.value } }
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
        // Fork: below the search bar, it stays below the status bar when the search bar scrolls away. Hidden, not taken
        // away, while the search is open: taken away, it could not tell the list that it had gone, and a sync ending in
        // the meantime left an empty gap below the search bar
        if (showSyncProgress) {
            val statusBarTop = WindowInsets.statusBars.getTop(LocalDensity.current)
            OneDriveSyncProgressBanner(
                onHeightChanged = { syncProgressHeight = it },
                glass = glass,
                modifier = Modifier.align(Alignment.TopCenter)
                    .thenIf(expanded) { graphicsLayer { alpha = 0f } }
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
                fieldHeight = fieldHeight,
                tint = glassTint,
                expandedTint = expandedGlassTint,
            )
            if (!expanded) {
                ClosingFieldGlass(
                    progress = fieldMorph,
                    glass = glass,
                    fieldMaxWidth = (maxWidth - SearchBarHorizontalPadding * 2).coerceAtMost(M3SearchBarMaxWidth),
                    fieldHeight = fieldHeight,
                    tint = glassTint,
                    offsetY = searchBarOffsetY,
                ) {
                    // The buttons of the open search where they were, fading out; they take no touches
                    Box(Modifier.align(Alignment.CenterStart).offset(x = SearchBarIconOffsetX)) {
                        IconButton(onClick = {}, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
                        }
                    }
                    Row(Modifier.align(Alignment.CenterEnd).offset(x = -SearchBarIconOffsetX)) {
                        IconButton(onClick = {}, shapes = IconButtonDefaults.shapes()) {
                            Icon(if (searchFieldState.text.isNotEmpty()) Icons.Default.Close else Icons.Default.ImageSearch, contentDescription = null)
                        }
                        // Fork: a checked "汉" refracts the blurred glass of the search, see checkedGlass
                        CompositionLocalProvider(LocalTileGlass provides tileGlass) { expandedTrailingIcon() }
                    }
                }
            }
        }
        // https://issuetracker.google.com/337191298
        // Workaround for can't exit SearchBar due to refocus in non-touch mode
        Box(Modifier.size(1.dp).focusable())
        val activeState = rememberCompositionActiveState()
        // Fork: a new search bar starts collapsed, with no animation, see searchBarKey
        key(searchBarKey) {
            SearchBar(
                modifier = Modifier.align(Alignment.TopCenter).thenIf(!expanded) { offset { IntOffset(0, searchBarOffsetY()) } }
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
                inputField = {
                    InputField(
                        state = inputState,
                        onSearch = {
                            hideSearchView()
                            onApplySearch()
                        },
                        expanded = expanded,
                        onExpandedChange = onExpandedChange,
                        modifier = Modifier.onSizeChanged { fieldHeight = it.height }
                            // Fork: backspace in an empty field removes the last chip
                            .thenIf(tagChips) {
                                onPreviewKeyEvent {
                                    if (it.key == Key.Backspace && it.type == KeyEventType.KeyDown && inputState.text.isEmpty() && shownTags.isNotEmpty()) {
                                        tags = tags.dropLast(1)
                                        true
                                    } else {
                                        false
                                    }
                                }
                            }
                            .then(if (!expanded && !fieldMorphed) {
                            // Fork: closing, the field shrinks from the whole width into the floating field, see
                            // ClosingFieldGlass, its title and buttons fading in
                            val fieldMaxWidth = (maxWidth - SearchBarHorizontalPadding * 2).coerceAtMost(M3SearchBarMaxWidth)
                            val navigationBars = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
                            val screenWidth = maxWidth
                            Modifier.layout { measurable, constraints ->
                                val available = screenWidth.roundToPx() - navigationBars.getLeft(this, layoutDirection) -
                                    navigationBars.getRight(this, layoutDirection)
                                val width = lerp(available, minOf(fieldMaxWidth.roundToPx(), available), FieldMorphEasing.transform(fieldMorph.value))
                                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                                layout(width, placeable.height) { placeable.place(0, 0) }
                            }.graphicsLayer { alpha = fieldContentAlpha(fieldMorph.value) }
                        } else {
                            Modifier.widthIn(max = (maxWidth - SearchBarHorizontalPadding * 2).coerceAtMost(M3SearchBarMaxWidth)).fillMaxWidth()
                                // Fork: the field is the glass while the search bar is collapsed
                                .thenIf(!expanded) { floatingGlass(glass, glassShape, glassTint) }
                        }),
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
                        // Fork: the placeholder and the chips are always given, empty when not shown: the Material text
                        // field decides once, at its first composition, whether it shows them at all (TextFieldImpl
                        // remembers its showPlaceholder and showAffix), so chips picked later stayed invisible
                        placeholder = {
                            if (shownTags.isEmpty()) {
                                val contentActive by activeState.state
                                val text = title.takeUnless { expanded || contentActive } ?: searchFieldHint
                                Text(text, overflow = TextOverflow.Ellipsis, maxLines = 1)
                            }
                        },
                        // Fork: the tags of the search, see SearchTagChips
                        prefix = {
                            if (shownTags.isNotEmpty()) {
                                SearchTagChips(
                                    tags = shownTags,
                                    interactive = expanded,
                                    onRemove = { i -> tags = tags.filterIndexed { j, _ -> j != i } },
                                    onToggleExcluded = { i -> tags = tags.mapIndexed { j, tag -> if (j == i) toggleExcludedTag(tag) else tag } },
                                    minTextWidth = { if (inputState.text.isEmpty()) 8.dp else 96.dp },
                                    glass = glass != null,
                                )
                            }
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
                                // Fork: the image search (or the clear button) before the buttons of the page, which
                                // end the bar
                                Row {
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
                                    // Fork: a checked "汉" refracts the blurred glass of the search, see checkedGlass
                                    CompositionLocalProvider(LocalTileGlass provides tileGlass) { expandedTrailingIcon() }
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
                // Fork: collapsed, the field draws the glass, the bar nothing; expanded, the suggestions keep their color.
                // With liquid glass, no divider: the cards and the chips lie over the search bar, see fadingTopEdge
                colors = if (glass != null) {
                    SearchBarDefaults.colors(containerColor = Color.Transparent, dividerColor = Color.Transparent)
                } else {
                    SearchBarDefaults.colors()
                },
            ) {
                activeState.Anchor()
                if (glass == null) searchContent()
            }
        }
        // Fork: with liquid glass, the cards and the chips of the search lie over the search bar, below its field: the
        // collapsed search bar no longer holds them, they stay while they leave. They come in with the search bar's
        // own fading; leaving, they take no touches
        if (glass != null && (expanded || searchShown)) {
            val top = with(LocalDensity.current) {
                SearchBarDefaults.windowInsets.getTop(this).toDp() + fieldHeight.toDp().coerceAtLeast(SearchBarDefaults.InputFieldHeight)
            } + SearchBarVerticalPadding * 2
            CompositionLocalProvider(LocalSearchExit provides searchExit) {
                Box(
                    Modifier.zIndex(2f).fillMaxSize()
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                        .padding(top = top)
                        .graphicsLayer { alpha = if (expanded) expandProgress.value else 1f },
                ) {
                    Column { searchContent() }
                    if (!expanded) Box(Modifier.matchParentSize().pointerInput(Unit) {})
                }
            }
        }
    }
}

/**
 * Fork: the blurred glass behind the expanded search. It grows out of the field of the collapsed search bar as
 * [progress] goes to 1, its lit edge fading as it fills the screen; closing, it fades out over the whole screen once
 * the cards have mostly left (searchExitGlassAlpha). It reads [progress] here,
 * so only this glass follows the animation frame by frame, not the whole search.
 */
@Composable
private fun ExpandedSearchGlass(
    expanded: Boolean,
    progress: Animatable<Float, AnimationVector1D>,
    glass: FloatingGlass,
    backdrop: LayerBackdrop,
    fieldMaxWidth: Dp,
    fieldHeight: Int,
    tint: Color,
    expandedTint: Color,
) {
    val fraction = progress.value
    if (!expanded && fraction == 0f) return
    // Closing, the glass stays on the whole screen and fades out
    val size = if (expanded) fraction else 1f
    val searchBarInsets = SearchBarDefaults.windowInsets
    val navigationBars = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
    val fieldHeightDp = with(LocalDensity.current) { fieldHeight.toDp().coerceAtLeast(SearchBarDefaults.InputFieldHeight) }
    val shape = RoundedCornerShape(fieldHeightDp / 2 * (1 - size))
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
                val left = lerp(fieldLeft, 0, size)
                val top = lerp(fieldTop, 0, size)
                val right = lerp(fieldLeft + fieldWidth, width, size)
                val bottom = lerp(fieldTop + maxOf(fieldHeight, SearchBarDefaults.InputFieldHeight.roundToPx()), height, size)
                val placeable = measurable.measure(Constraints.fixed(right - left, bottom - top))
                layout(width, height) { placeable.place(left, top) }
            }
            .thenIf(!expanded) { graphicsLayer { alpha = searchExitGlassAlpha(1f - fraction) } }
            .layerBackdrop(backdrop)
            .floatingGlass(glass, shape, lerp(tint, expandedTint, size), GlassStyle.Backdrop)
            .thenIf(dark && size < 1f) { border(1.5.dp, Color.White.copy(alpha = 0.5f * (1 - size)), shape) },
    )
}

/**
 * Fork: closing the search, the glass of the field shrinks from the whole width of the screen into the floating field
 * as [progress] goes to 1, in step with the field, its corners rounding and its glass coming in; gone at 1, where the
 * field draws its own. [previous], the buttons of the open search, fade out on it.
 */
@Composable
private fun ClosingFieldGlass(
    progress: Animatable<Float, AnimationVector1D>,
    glass: FloatingGlass,
    fieldMaxWidth: Dp,
    fieldHeight: Int,
    tint: Color,
    offsetY: () -> Int,
    previous: @Composable BoxScope.() -> Unit,
) {
    // Even in time; the size follows it eased, as the field does
    val time = progress.value
    if (time == 1f) return
    val fraction = FieldMorphEasing.transform(time)
    val searchBarInsets = SearchBarDefaults.windowInsets
    val navigationBars = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
    val fieldHeightDp = with(LocalDensity.current) { fieldHeight.toDp().coerceAtLeast(SearchBarDefaults.InputFieldHeight) }
    val shape = RoundedCornerShape(fieldHeightDp / 2 * fraction)
    Box(
        Modifier
            .layout { measurable, constraints ->
                val width = constraints.maxWidth
                // Where the field of the collapsed search bar lies, at the top of the screen
                val navigationLeft = navigationBars.getLeft(this, layoutDirection)
                val available = width - navigationLeft - navigationBars.getRight(this, layoutDirection)
                val fieldWidth = minOf(fieldMaxWidth.roundToPx(), available)
                val fieldLeft = navigationLeft + (available - fieldWidth) / 2
                val top = searchBarInsets.getTop(this) + SearchBarVerticalPadding.roundToPx() + offsetY()
                val left = lerp(navigationLeft, fieldLeft, fraction)
                val right = lerp(navigationLeft + available, fieldLeft + fieldWidth, fraction)
                val placeable = measurable.measure(Constraints.fixed(right - left, maxOf(fieldHeight, SearchBarDefaults.InputFieldHeight.roundToPx())))
                layout(width, constraints.maxHeight) { placeable.place(left, top) }
            },
    ) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = (time / 0.3f).coerceAtMost(1f) }.floatingGlass(glass, shape, tint))
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = 1f - (time / 0.35f).coerceAtMost(1f) }, content = previous)
        }
        Box(Modifier.matchParentSize().pointerInput(Unit) {})
    }
}

/**
 * Fork: the top of the suggestions fades out once they are scrolled, over [SearchTopFade]; the cards no longer end
 * on a hard line below the chips. It grows with the first [SearchTopFade] of scrolling, so nothing jumps.
 */
private fun Modifier.fadingTopEdge(state: LazyStaggeredGridState) = graphicsLayer {
    compositingStrategy = if (state.canScrollBackward) CompositingStrategy.Offscreen else CompositingStrategy.Auto
}.drawWithContent {
    drawContent()
    val fade = SearchTopFade.toPx()
    val scrolled = if (state.firstVisibleItemIndex <= 1) state.firstVisibleItemScrollOffset.toFloat() else fade
    val height = scrolled.coerceAtMost(fade)
    if (state.canScrollBackward && height >= 1f) {
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = height), blendMode = BlendMode.DstIn)
    }
}

/** Fork: the title and the buttons of the field closing the search, coming in after those of the open search left. */
private fun fieldContentAlpha(progress: Float) = ((progress - 0.15f) / 0.45f).coerceIn(0f, 1f)

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
// Fork: the opening animation of the Material search bar (SearchBar.kt), for the glass growing with it
private val SearchBarVerticalPadding = 8.dp
private val SearchBarIconOffsetX = 4.dp
private val SearchTopFade = 24.dp
private val ExpandSpec = tween<Float>(durationMillis = 600, delayMillis = 100, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f))
// Closing, the cards leave and the blurred glass fades out after them, see SearchExit.kt: even in time, they ease
private val CollapseSpec = tween<Float>(durationMillis = 400, easing = LinearEasing)
// Closing, the field shrinks into the floating field (FieldMorphEasing, quick at first and settling) while the buttons
// cross-fade: even in time
private val FieldMorphSpec = tween<Float>(durationMillis = 400, easing = LinearEasing)
private val FieldMorphEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

// Fork: how much of the bar color lies over the glass of the search bar and the sync progress
const val GLASS_BAR_TINT = 0.2f

private val M3SearchBarMaxWidth = 720.dp
