package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.LOCAL_FAVORITED
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.ehviewer.core.ui.component.FastScrollLazyVerticalGrid
import com.ehviewer.core.ui.component.FastScrollLazyVerticalStaggeredGrid
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.anthology.AnthologyChapterItem
import com.hippo.ehviewer.anthology.AnthologyChapters
import com.hippo.ehviewer.anthology.AnthologyMatchState
import com.hippo.ehviewer.anthology.AnthologyMatches
import com.hippo.ehviewer.anthology.FavoriteFolders
import com.hippo.ehviewer.chapters.ChapterSource
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.ktbuilder.execute
import com.hippo.ehviewer.ktbuilder.imageRequest
import com.hippo.ehviewer.sync.FavoritesIndexSync
import com.hippo.ehviewer.todownload.ToDownload
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.doGalleryInfoAction
import com.hippo.ehviewer.ui.main.GalleryDetailErrorTip
import com.hippo.ehviewer.ui.main.GalleryInfoGridItem
import com.hippo.ehviewer.ui.main.GalleryInfoListItem
import com.hippo.ehviewer.ui.main.GlassToggleButton
import com.hippo.ehviewer.ui.main.ShapeLoadingIndicator
import com.hippo.ehviewer.ui.tools.mouseDragScroll
import com.hippo.ehviewer.util.FavouriteStatusRouter
import com.hippo.ehviewer.util.displayString
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.math.roundToInt
import moe.tarsin.coroutines.runSuspendCatching
import moe.tarsin.navigate

private val MatchedBlue = Color(0xFF4A86E8)

/**
 * Fork: presents the TOC entries of an anthology ([gid], [token]) as a search-results-style list of standalone virtual
 * chapter galleries ([AnthologyChapters]). Each chapter has its own sliced cover, page count, artist tags, favorite /
 * `ToDownload` state, and thumbnail-only detail page, while checked progress is remembered so the user can pause and
 * resume inspecting a 600-page anthology at any time.
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.AnthologyChaptersScreen(
    gid: Long,
    token: String,
    navigator: DestinationsNavigator,
) = Screen(navigator, recordsGlass = true) {
    val context = LocalContext.current
    val searchFieldState = rememberTextFieldState()
    var searchBarExpanded by rememberSaveable { mutableStateOf(false) }
    var searchBarOffsetY by rememberSaveable { mutableIntStateOf(0) }
    var keyword by rememberSaveable { mutableStateOf("") }

    var parentDetail by remember(gid) { mutableStateOf(detailCache[gid]) }
    var contents by remember(gid) {
        mutableStateOf(parentDetail?.let { AnthologyMatches.contentsOf(it.gid, it.pages, it.comments.comments) })
    }
    var loading by remember(gid) { mutableStateOf(contents == null) }
    var loadError by rememberSaveable(gid) { mutableStateOf("") }

    LaunchedEffect(gid, token, loadError) {
        withIOContext { AnthologyChapters.ensureLoaded() }
        if (loadError.isNotEmpty()) return@LaunchedEffect
        if (contents != null && parentDetail != null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        runSuspendCatching {
            withIOContext {
                val detail = parentDetail ?: EhEngine.getGalleryDetail(
                    EhUrl.getGalleryDetailUrl(gid, token, allComment = true),
                ).also { detailCache[it.gid] = it }
                parentDetail = detail
                AnthologyChapters.ensureContents(detail, forceAllComments = true)
            }
        }.onSuccess { parsed ->
            contents = parsed
            loading = false
        }.onFailure { e ->
            loadError = e.displayString()
            loading = false
        }
    }

    LaunchedEffect(gid, contents) {
        contents?.let { AnthologyMatches.start(gid, it) }
    }

    val matchStates by AnthologyMatches.states.collectAsState()
    val matchState = matchStates[gid]
    val checkedMap by AnthologyChapters.checkedByParent.collectAsState()
    val checkedSet = checkedMap[gid].orEmpty()
    val onlyUnchecked by AnthologyChapters.onlyUnchecked.collectAsState()
    val toDownload by ToDownload.gids.collectAsState()
    val cloudVersion by FavoritesIndexSync.cloudVersion.collectAsState()

    var favRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        FavouriteStatusRouter.globalFlow.collect { favRevision++ }
    }

    val candidateChapterGids = remember(gid, contents) {
        contents?.entries?.mapNotNull { AnthologyChapters.encodeGid(gid, it.first, it.last) }.orEmpty()
    }
    val localFavoritedSet by produceState(emptySet<Long>(), candidateChapterGids, favRevision, toDownload) {
        value = withIOContext {
            candidateChapterGids.filterTo(HashSet()) { EhDB.containLocalFavorites(it) }
        }
    }

    val allItems = remember(parentDetail, contents, localFavoritedSet, toDownload) {
        val d = parentDetail
        val c = contents
        if (d != null && c != null) AnthologyChapters.buildItems(d, c, localFavoritedSet, toDownload) else emptyList()
    }

    val externalSourceGids = remember(matchState, candidateChapterGids) {
        val selfSet = candidateChapterGids.toSet()
        (matchState as? AnthologyMatchState.Done)?.result?.matches?.values?.flatten()
            ?.filter { it.gid !in selfSet }
            ?.map { it.gid }
            ?.distinct()
            .orEmpty()
    }
    val externalFolders by produceState(emptyMap<Long, List<String>>(), externalSourceGids, cloudVersion, toDownload, favRevision) {
        value = if (externalSourceGids.isEmpty()) emptyMap() else FavoriteFolders.lookup(externalSourceGids)
    }

    fun externalFavoritesOf(item: AnthologyChapterItem): List<ChapterSource> {
        val matches = (matchState as? AnthologyMatchState.Done)?.result?.matches?.get(item.entry.key).orEmpty()
        return matches.filter { it.gid != item.slice.gid }
    }

    fun isItemFavorited(item: AnthologyChapterItem): Boolean =
        item.slice.gid in localFavoritedSet || externalFavoritesOf(item).isNotEmpty()

    val favoritedCount = remember(allItems, localFavoritedSet, matchState) {
        allItems.count(::isItemFavorited)
    }
    val checkedCount = remember(allItems, checkedSet) {
        allItems.count { it.slice.gid in checkedSet }
    }

    val shownItems = remember(allItems, keyword, onlyUnchecked, checkedSet, localFavoritedSet, matchState) {
        allItems.filter { item ->
            (!onlyUnchecked || (item.slice.gid !in checkedSet && !isItemFavorited(item))) &&
                AnthologyChapters.matchesFilter(item, keyword)
        }
    }

    val nextUnchecked = remember(allItems, checkedSet, localFavoritedSet, matchState) {
        allItems.firstOrNull { it.slice.gid !in checkedSet && !isItemFavorited(it) }
    }

    val suggestionProvider = remember(allItems) {
        SuggestionProvider { text ->
            val q = text.trim()
            allItems
                .filter { q.isEmpty() || AnthologyChapters.matchesFilter(it, q) }
                .take(40)
                .map { item ->
                    val artist = item.entry.names.firstOrNull { !it.latin }?.text
                        ?: item.entry.names.firstOrNull()?.text
                        ?: item.entry.title
                        ?: "P.${item.slice.first}"
                    val sub = buildString {
                        append("#${item.index + 1} · P.${item.slice.first}–${item.slice.last}")
                        item.entry.title?.let { append(" · ").append(it) }
                    }
                    object : Suggestion() {
                        override val keyword: String = artist
                        override val hint: String = sub
                        override fun onClick() {
                            searchFieldState.setTextAndPlaceCursorAtEnd(artist)
                        }
                    }
                }
        }
    }

    val title = if (onlyUnchecked) {
        stringResource(R.string.anthology_chapters_only_unchecked_title, shownItems.size)
    } else {
        stringResource(R.string.anthology_chapters_title, allItems.size)
    }
    val searchHint = stringResource(R.string.anthology_chapters_search_hint)

    val listMode by Settings.listMode.collectAsState()
    val detailGridState = rememberLazyGridState()
    val staggeredGridState = rememberLazyStaggeredGridState()

    fun openChapter(item: AnthologyChapterItem) {
        AnthologyChapters.markChecked(item.slice.gid)
        navigate(item.info.asDst())
    }

    fun jumpToChapter(target: AnthologyChapterItem) {
        val idxInShown = shownItems.indexOfFirst { it.slice.gid == target.slice.gid }
        if (idxInShown >= 0) {
            launch {
                // +1 accounts for the summary header item at index 0
                if (listMode == 0) {
                    detailGridState.animateScrollToItem(idxInShown + 1)
                } else {
                    staggeredGridState.animateScrollToItem(idxInShown + 1)
                }
            }
        } else {
            openChapter(target)
        }
    }

    SearchBarScreen(
        onApplySearch = { keyword = it },
        expanded = searchBarExpanded,
        onExpandedChange = { searchBarExpanded = it },
        title = title,
        searchFieldHint = searchHint,
        searchFieldState = searchFieldState,
        suggestionProvider = suggestionProvider,
        localSearch = true,
        searchBarOffsetY = { searchBarOffsetY },
        onClearSearch = if (keyword.isNotEmpty()) {
            {
                keyword = ""
                searchFieldState.setTextAndPlaceCursorAtEnd("")
            }
        } else {
            null
        },
        trailingIcon = {
            GlassToggleButton(
                checked = onlyUnchecked,
                onCheckedChange = { AnthologyChapters.setOnlyUnchecked(it) },
            ) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = stringResource(R.string.anthology_chapters_only_unchecked),
                )
            }
            var menuExpanded by remember { mutableStateOf(false) }
            IconButton(onClick = { menuExpanded = !menuExpanded }, shapes = IconButtonDefaults.shapes()) {
                Icon(imageVector = Icons.Default.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                if (allItems.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.anthology_chapters_mark_all_checked)) },
                        leadingIcon = { Icon(Icons.Default.DoneAll, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            AnthologyChapters.markAllChecked(gid, allItems.map { it.slice.gid })
                        },
                    )
                    if (checkedSet.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.anthology_chapters_reset_checked)) },
                            onClick = {
                                menuExpanded = false
                                AnthologyChapters.resetChecked(gid)
                            },
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.anthology_chapters_open_parent)) },
                    onClick = {
                        menuExpanded = false
                        navigator.popBackStack()
                    },
                )
            }
        },
    ) { paddingValues ->
        val density = LocalDensity.current
        val searchBarConnection = remember(paddingValues) {
            val topPaddingPx = with(density) { paddingValues.calculateTopPadding().roundToPx() }
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    searchBarOffsetY = (searchBarOffsetY + consumed.y).roundToInt().coerceIn(-topPaddingPx, 0)
                    return Offset.Zero
                }
            }
        }
        val marginH = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_h)
        val marginV = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_v)
        val cardHeight by collectListThumbSizeAsState()
        val showPages by Settings.showGalleryPages.collectAsState()
        val showProgress by Settings.showReadingProgress.collectAsState()

        when {
            loading -> Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                ShapeLoadingIndicator()
            }
            loadError.isNotBlank() -> Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                GalleryDetailErrorTip(error = loadError, onClick = { loadError = "" })
            }
            allItems.isEmpty() -> Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.anthology_chapters_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            listMode == 0 -> {
                val columnWidth by collectDetailSizeAsState()
                FastScrollLazyVerticalGrid(
                    columns = GridCells.Adaptive(columnWidth),
                    state = detailGridState,
                    modifier = Modifier.nestedScroll(searchBarConnection).fillMaxSize().mouseDragScroll(detailGridState),
                    contentPadding = paddingValues + PaddingValues(marginH, marginV),
                    verticalArrangement = Arrangement.spacedBy(dimensionResource(com.hippo.ehviewer.R.dimen.gallery_list_interval)),
                    horizontalArrangement = Arrangement.spacedBy(dimensionResource(com.hippo.ehviewer.R.dimen.gallery_list_interval)),
                ) {
                    item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                        ChaptersSummaryHeader(
                            totalCount = allItems.size,
                            checkedCount = checkedCount,
                            favoritedCount = favoritedCount,
                            nextUnchecked = if (checkedCount > 0) nextUnchecked else null,
                            onResume = { nextUnchecked?.let(::jumpToChapter) },
                        )
                    }
                    if (shownItems.isEmpty()) {
                        item(key = "empty_filtered", span = { GridItemSpan(maxLineSpan) }) {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.anthology_chapters_filtered_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    itemsIndexed(shownItems, key = { _, item -> item.slice.gid }) { idx, item ->
                        val isChecked = item.slice.gid in checkedSet
                        val extFavs = externalFavoritesOf(item)
                        PrefetchChapterCover(shownItems, idx, context)
                        ChapterListCard(
                            item = item,
                            isChecked = isChecked,
                            externalFavorites = extFavs,
                            externalFolders = externalFolders,
                            cardHeight = cardHeight,
                            showPages = showPages,
                            showProgress = showProgress,
                            onClick = { openChapter(item) },
                            onLongClick = { launch { doGalleryInfoAction(item.info) } },
                            onOpenExternal = { src ->
                                navigate(BaseGalleryInfo(gid = src.gid, token = src.token, title = src.title, thumbKey = src.thumbKey).asDst())
                            },
                        )
                    }
                }
            }
            else -> {
                val gridInterval = dimensionResource(com.hippo.ehviewer.R.dimen.gallery_grid_interval)
                val thumbColumns by Settings.thumbColumns.collectAsState()
                FastScrollLazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(thumbColumns),
                    state = staggeredGridState,
                    modifier = Modifier.nestedScroll(searchBarConnection).fillMaxSize().mouseDragScroll(staggeredGridState),
                    contentPadding = paddingValues + PaddingValues(marginH, marginV),
                    verticalItemSpacing = gridInterval,
                    horizontalArrangement = Arrangement.spacedBy(gridInterval),
                ) {
                    item(key = "header", span = StaggeredGridItemSpan.FullLine) {
                        ChaptersSummaryHeader(
                            totalCount = allItems.size,
                            checkedCount = checkedCount,
                            favoritedCount = favoritedCount,
                            nextUnchecked = if (checkedCount > 0) nextUnchecked else null,
                            onResume = { nextUnchecked?.let(::jumpToChapter) },
                        )
                    }
                    itemsIndexed(shownItems, key = { _, item -> item.slice.gid }) { idx, item ->
                        val isChecked = item.slice.gid in checkedSet
                        PrefetchChapterCover(shownItems, idx, context)
                        Box(modifier = if (isChecked && item.info.favoriteSlot == NOT_FAVORITED) Modifier.alpha(0.68f) else Modifier) {
                            GalleryInfoGridItem(
                                onClick = { openChapter(item) },
                                onLongClick = { launch { doGalleryInfoAction(item.info) } },
                                info = item.info,
                                showPages = showPages,
                                showProgress = showProgress,
                            )
                            if (isChecked) {
                                CheckedPill(modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrefetchChapterCover(items: List<AnthologyChapterItem>, index: Int, context: android.content.Context) {
    items.getOrNull(index + 4)?.let { ahead ->
        LaunchedEffect(ahead.slice.gid) {
            with(context) { imageRequest(ahead.info).execute() }
        }
    }
}

@Composable
private fun ChaptersSummaryHeader(
    totalCount: Int,
    checkedCount: Int,
    favoritedCount: Int,
    nextUnchecked: AnthologyChapterItem?,
    onResume: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text(
            text = stringResource(R.string.anthology_chapters_summary, totalCount, checkedCount, favoritedCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (nextUnchecked != null) {
            val label = nextUnchecked.entry.names.firstOrNull { !it.latin }?.text
                ?: nextUnchecked.entry.names.firstOrNull()?.text
                ?: nextUnchecked.entry.title
                ?: "P.${nextUnchecked.slice.first}"
            Spacer(Modifier.height(4.dp))
            AssistChip(
                onClick = onResume,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
                label = {
                    Text(
                        text = stringResource(R.string.anthology_chapters_resume, nextUnchecked.index + 1, label),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
context(_: androidx.compose.animation.SharedTransitionScope, _: com.ehviewer.core.ui.util.TransitionsVisibilityScope)
private fun ChapterListCard(
    item: AnthologyChapterItem,
    isChecked: Boolean,
    externalFavorites: List<ChapterSource>,
    externalFolders: Map<Long, List<String>>,
    cardHeight: androidx.compose.ui.unit.Dp,
    showPages: Boolean,
    showProgress: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenExternal: (ChapterSource) -> Unit,
) {
    val isSelfFavorited = item.info.favoriteSlot == LOCAL_FAVORITED
    Column {
        Box(modifier = if (isChecked && !isSelfFavorited && externalFavorites.isEmpty()) Modifier.alpha(0.72f) else Modifier) {
            GalleryInfoListItem(
                onClick = onClick,
                onLongClick = onLongClick,
                info = item.info,
                showPages = showPages,
                showProgress = showProgress,
                modifier = Modifier.height(cardHeight),
            )
            if (isChecked) {
                CheckedPill(modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
            }
        }
        if (externalFavorites.isNotEmpty()) {
            val first = externalFavorites.first()
            val folderText = externalFavorites.asSequence()
                .flatMap { src -> externalFolders[src.gid]?.asSequence() ?: sequenceOf(src.favoriteFolder).filterNotNull() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("、")
                .ifEmpty { stringResource(R.string.local_favorites) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 3.dp, start = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MatchedBlue.copy(alpha = 0.16f))
                    .clickable { onOpenExternal(first) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = stringResource(R.string.anthology_chapters_matched_favorite, folderText) + " · " + first.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CheckedPill(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.anthology_chapters_checked_badge),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
