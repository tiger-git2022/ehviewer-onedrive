package com.hippo.ehviewer.ui.screen

import android.content.Context
import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.currentRecomposeScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.ui.component.FAB_ANIMATE_TIME
import com.ehviewer.core.ui.component.FabLayout
import com.ehviewer.core.ui.component.ProvideSideSheetContent
import com.ehviewer.core.ui.icons.EhIcons
import com.ehviewer.core.ui.icons.filled.GoTo
import com.ehviewer.core.ui.util.asyncState
import com.ehviewer.core.ui.util.takeAndClear
import com.ehviewer.core.ui.util.thenIf
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.mapToLongArray
import com.ehviewer.core.util.onEachLatest
import com.ehviewer.core.util.withUIContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.data.FavListUrlBuilder
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.sync.FavoritesIndexSync
import com.hippo.ehviewer.sync.NO_CLOUD_SLOT
import com.hippo.ehviewer.todownload.ToDownload
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.awaitSelectDate
import com.hippo.ehviewer.ui.main.CHINESE_LANGUAGE_FILTER
import com.hippo.ehviewer.ui.main.ChineseOnlyButton
import com.hippo.ehviewer.ui.main.GalleryInfoGridItem
import com.hippo.ehviewer.ui.main.GalleryInfoListItem
import com.hippo.ehviewer.ui.main.GalleryList
import com.hippo.ehviewer.ui.main.OneDriveSyncIcon
import com.hippo.ehviewer.ui.startDownload
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.ui.tools.awaitSelectItem
import com.hippo.ehviewer.wnacg.isWnacg
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import moe.tarsin.coroutines.runSwallowingWithUI
import moe.tarsin.navigate

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.FavouritesScreen(navigator: DestinationsNavigator, viewModel: FavoritesViewModel = viewModel()) = Screen(navigator, recordsGlass = true) {
    // Immutables
    val localFavName = stringResource(R.string.local_favorites)
    val toDownloadName = stringResource(R.string.to_download)
    val cloudFavName = stringResource(R.string.cloud_favorites)
    val animateItems by Settings.animateItems.collectAsState()
    val hasSignedIn by Settings.hasSignedIn.collectAsState()

    // Meta State
    var urlBuilder by viewModel.urlBuilder
    var searchBarExpanded by rememberSaveable { mutableStateOf(false) }
    var searchBarOffsetY by remember { mutableIntStateOf(0) }
    var fabExpanded by remember { mutableStateOf(false) }
    var fabHidden by remember { mutableStateOf(false) }

    // Derived State
    val keyword = urlBuilder.keyword
    val favCatName = remember(urlBuilder) {
        when (val favCat = urlBuilder.favCat) {
            in 0..9 -> Settings.favCat[favCat]
            FavListUrlBuilder.FAV_CAT_LOCAL -> localFavName.also { searchBarOffsetY = 0 }
            FavListUrlBuilder.FAV_CAT_TO_DOWNLOAD -> toDownloadName.also { searchBarOffsetY = 0 }
            else -> cloudFavName
        }
    }
    val title = if (keyword.isNullOrBlank()) {
        stringResource(R.string.favorites_title, favCatName)
    } else {
        stringResource(R.string.favorites_title_2, favCatName, keyword)
    }
    val density = LocalDensity.current
    val searchBarHint = stringResource(R.string.search_bar_hint, favCatName)
    val data = viewModel.data.collectAsLazyPagingItems()

    fun refresh(newUrlBuilder: FavListUrlBuilder = urlBuilder.copy(jumpTo = null, prev = null, next = null)) {
        urlBuilder = newUrlBuilder
        data.refresh()
    }

    // Fork: "汉" (the language filter shared with the gallery list) filters the favorites, which reload when the ones
    // shown used the other setting: toggled here, or on the gallery list meanwhile. The state reads the setting once
    // stored, as the pager does
    val languageFilter by Settings.languageFilter.collectAsState()
    LaunchedEffect(languageFilter) {
        val applied = viewModel.chineseOnlyApplied
        if (applied != null && applied != (languageFilter == CHINESE_LANGUAGE_FILTER)) refresh()
    }

    // Fork: 「待下载」 shown reloads when it changed, here, on another page or by a sync, see ToDownload
    LaunchedEffect(Unit) {
        ToDownload.gids.drop(1).collect { if (urlBuilder.isToDownload) data.refresh() }
    }

    ProvideSideSheetContent { sheetState ->
        val localFavCount by viewModel.localFavCount.collectAsState(0)
        val toDownloadCount by viewModel.toDownloadCount.collectAsState(0)
        TopAppBar(
            title = { Text(text = stringResource(id = R.string.collections)) },
            windowInsets = WindowInsets(),
            colors = topBarOnDrawerColor(),
        )
        val scope = currentRecomposeScope
        LaunchedEffect(Unit) {
            Settings.favChangesFlow.collect {
                scope.invalidate()
            }
        }
        // Fork: (category, name, count); 「待下载」 under the local favorites, a part of them, see ToDownload
        val faves = buildList {
            add(Triple(FavListUrlBuilder.FAV_CAT_LOCAL, localFavName, localFavCount))
            add(Triple(FavListUrlBuilder.FAV_CAT_TO_DOWNLOAD, toDownloadName, toDownloadCount))
            if (hasSignedIn) {
                add(Triple(FavListUrlBuilder.FAV_CAT_ALL, cloudFavName, Settings.favCloudCount))
                Settings.favCat.forEachIndexed { slot, name -> add(Triple(slot, name, Settings.favCount[slot])) }
            }
        }
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom)),
        ) {
            faves.forEach { (newCat, name, count) ->
                ListItem(
                    trailingContent = { Text(text = count.toString(), style = MaterialTheme.typography.bodyLarge) },
                    modifier = Modifier.clip(CardDefaults.shape).clickable {
                        refresh(FavListUrlBuilder(newCat))
                        Settings.recentFavCat = newCat
                        fabHidden = false
                        launch { sheetState.close() }
                    },
                    colors = listItemOnDrawerColor(urlBuilder.favCat == newCat),
                    content = {
                        if (newCat == FavListUrlBuilder.FAV_CAT_TO_DOWNLOAD) {
                            Text(text = "↳ $name", modifier = Modifier.padding(start = 16.dp))
                        } else {
                            Text(text = name)
                        }
                    },
                )
            }
        }
    }

    val checkedInfoMap = remember { mutableStateMapOf<Long, BaseGalleryInfo>() }
    val selectMode = checkedInfoMap.isNotEmpty()
    DrawerHandle(!selectMode && !searchBarExpanded)

    SearchBarScreen(
        onApplySearch = { refresh(FavListUrlBuilder(urlBuilder.favCat, it)) },
        expanded = searchBarExpanded,
        onExpandedChange = {
            searchBarExpanded = it
            fabHidden = it
            if (it) checkedInfoMap.clear()
        },
        title = title,
        searchFieldHint = searchBarHint,
        localSearch = urlBuilder.isLocal,
        searchBarOffsetY = { searchBarOffsetY },
        // Fork
        showSyncProgress = true,
        onClearSearch = if (!urlBuilder.keyword.isNullOrEmpty()) {
            { refresh(FavListUrlBuilder(urlBuilder.favCat)) }
        } else {
            null
        },
        trailingIcon = {
            // Fork: OneDrive sync state; a sync from here reloads the local favorites it merged
            OneDriveSyncIcon(onSynced = { if (urlBuilder.isLocal) refresh() })
            // Fork: "汉" as on the gallery list, in place of the collections button (a swipe from the right edge away)
            // and the avatar (the image limits are in Settings → EH); it reloads once the setting is stored (see above)
            ChineseOnlyButton(
                checked = languageFilter == CHINESE_LANGUAGE_FILTER,
                onCheckedChange = { on -> Settings.languageFilter.value = if (on) CHINESE_LANGUAGE_FILTER else -1 },
            )
        },
    ) { contentPadding ->
        val listMode by Settings.listMode.collectAsState()
        val height by collectListThumbSizeAsState()
        val showPages by Settings.showGalleryPages.collectAsState()
        val showProgress by Settings.showReadingProgress.collectAsState()
        val searchBarConnection = remember {
            val slop = ViewConfiguration.get(contextOf<Context>()).scaledTouchSlop
            val topPaddingPx = with(density) { contentPadding.calculateTopPadding().roundToPx() }
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    val dy = -consumed.y
                    if (dy >= slop) {
                        fabHidden = true
                    } else if (dy <= -slop / 2) {
                        fabHidden = false
                    }
                    searchBarOffsetY = (searchBarOffsetY - dy).roundToInt().coerceIn(-topPaddingPx, 0)
                    return Offset.Zero // We never consume it
                }
            }
        }
        GalleryList(
            data = data,
            contentModifier = Modifier.nestedScroll(searchBarConnection),
            contentPadding = contentPadding,
            listMode = listMode,
            detailItemContent = { info ->
                val checked = info.gid in checkedInfoMap
                CheckableItem(
                    checked = checked,
                    modifier = Modifier.thenIf(animateItems) { animateItem() },
                ) { interactionSource ->
                    GalleryInfoListItem(
                        onClick = {
                            if (selectMode) {
                                if (checked) {
                                    checkedInfoMap.remove(info.gid)
                                } else {
                                    checkedInfoMap[info.gid] = info
                                }
                            } else {
                                navigate(info.asDst())
                            }
                        },
                        onLongClick = {
                            checkedInfoMap[info.gid] = info
                        },
                        info = info,
                        showPages = showPages,
                        showProgress = showProgress,
                        modifier = Modifier.height(height),
                        isInFavScene = true,
                        interactionSource = interactionSource,
                    )
                }
            },
            thumbItemContent = { info ->
                val checked = info.gid in checkedInfoMap
                CheckableItem(
                    checked = checked,
                    modifier = Modifier.thenIf(animateItems) { animateItem() },
                ) { interactionSource ->
                    GalleryInfoGridItem(
                        onClick = {
                            if (selectMode) {
                                if (checked) {
                                    checkedInfoMap.remove(info.gid)
                                } else {
                                    checkedInfoMap[info.gid] = info
                                }
                            } else {
                                navigate(info.asDst())
                            }
                        },
                        onLongClick = {
                            checkedInfoMap[info.gid] = info
                        },
                        info = info,
                        showPages = showPages,
                        showProgress = showProgress,
                        showFavoriteStatus = false,
                        interactionSource = interactionSource,
                    )
                }
            },
            searchBarOffsetY = { searchBarOffsetY },
            scrollToTopOnRefresh = !urlBuilder.isLocal,
            onRefresh = { refresh() },
            onLoading = { searchBarOffsetY = 0 },
        )
    }

    val hideFab by asyncState(
        produce = { fabHidden },
        transform = {
            onEachLatest { hide ->
                if (!hide) delay(FAB_ANIMATE_TIME.toLong())
            }
        },
    )

    FabLayout(
        hidden = hideFab && !selectMode,
        expanded = fabExpanded || selectMode,
        onExpandChanged = {
            fabExpanded = it
            checkedInfoMap.clear()
        },
        autoCancel = !selectMode,
    ) {
        if (!selectMode) {
            if (urlBuilder.favCat == FavListUrlBuilder.FAV_CAT_LOCAL) {
                onClick(Icons.Default.Shuffle) {
                    EhDB.randomLocalFav()?.let { info ->
                        withUIContext { navigate(info.asDst()) }
                    }
                }
            }
            onClick(EhIcons.Default.GoTo) {
                val date = awaitSelectDate()
                refresh(urlBuilder.copy(jumpTo = date, prev = null, next = "2"))
            }
            onClick(Icons.Default.Refresh) {
                refresh()
            }
            onClick(Icons.AutoMirrored.Default.LastPage) {
                refresh(urlBuilder.copy(jumpTo = null, prev = "1-0", next = null))
            }
        } else {
            onClick(Icons.Default.DoneAll, autoClose = false) {
                val info = data.itemSnapshotList.items.associateBy { it.gid }
                checkedInfoMap.putAll(info)
            }
            onClick(Icons.Default.Download) {
                val info = checkedInfoMap.takeAndClear()
                runSwallowingWithUI {
                    startDownload(false, *info.toTypedArray())
                }
            }
            onClick(Icons.Default.Delete) {
                val info = checkedInfoMap.takeAndClear()
                val srcCat = urlBuilder.favCat
                // Fork: out of 「待下载」 only, they stay in the local favorites
                if (srcCat == FavListUrlBuilder.FAV_CAT_TO_DOWNLOAD) {
                    awaitConfirmationOrCancel(title = R.string.to_download_remove) {
                        Text(text = stringResource(R.string.to_download_remove_message, info.size))
                    }
                    runSwallowingWithUI { ToDownload.remove(info.map { it.gid }) }
                    data.refresh()
                    return@onClick
                }
                awaitConfirmationOrCancel(title = R.string.delete_favorites_dialog_title) {
                    Text(text = stringResource(R.string.delete_favorites_dialog_message, info.size))
                }
                runSwallowingWithUI {
                    if (srcCat == FavListUrlBuilder.FAV_CAT_LOCAL) { // Delete local fav
                        EhDB.removeLocalFavorites(info)
                    } else {
                        val delList = info.mapToLongArray(BaseGalleryInfo::gid)
                        EhEngine.modifyFavorites(delList, srcCat, -1)
                        FavoritesIndexSync.onMoved(delList.asList(), NO_CLOUD_SLOT)
                    }
                }
                // We refresh anyway as cloud data maybe partially modified
                data.refresh()
            }
            onClick(Icons.AutoMirrored.Default.DriveFileMove) {
                // First is local favorite, then 「待下载」 (fork), the other 10 is cloud favorite
                val items = buildList {
                    add(localFavName)
                    add(toDownloadName)
                    if (hasSignedIn) {
                        addAll(Settings.favCat)
                    }
                }
                val index = awaitSelectItem(items, R.string.move_favorites_dialog_title)
                val srcCat = urlBuilder.favCat
                val dstCat = when (index) {
                    0 -> FavListUrlBuilder.FAV_CAT_LOCAL
                    1 -> FavListUrlBuilder.FAV_CAT_TO_DOWNLOAD
                    else -> index - 2
                }
                val info = checkedInfoMap.takeAndClear()
                if (srcCat != dstCat) {
                    runSwallowingWithUI {
                        if (dstCat == FavListUrlBuilder.FAV_CAT_TO_DOWNLOAD) {
                            // Fork: into 「待下载」, and the local favorites; a cloud favorite stays there too, as moved to local
                            ToDownload.add(info)
                        } else if (srcCat == FavListUrlBuilder.FAV_CAT_TO_DOWNLOAD && dstCat == FavListUrlBuilder.FAV_CAT_LOCAL) {
                            // Fork: out of 「待下载」, still a local favorite
                            ToDownload.remove(info.map { it.gid })
                        } else if (FavListUrlBuilder(srcCat).isLocal) {
                            // Move from local to cloud. Fork: not an album of 紳士漫畫, E-Hentai has none of them
                            val moved = info.filterNot { it.isWnacg }
                            if (moved.isNotEmpty()) {
                                val galleryList = moved.map { it.gid to it.token }
                                EhEngine.addFavorites(galleryList, dstCat)
                                FavoritesIndexSync.onAdded(moved, dstCat)
                                EhDB.removeLocalFavorites(moved)
                            }
                        } else if (dstCat == FavListUrlBuilder.FAV_CAT_LOCAL) {
                            // Move from cloud to local
                            EhDB.putLocalFavorites(info)
                        } else {
                            // Move from cloud to cloud
                            val gidArray = info.mapToLongArray(BaseGalleryInfo::gid)
                            EhEngine.modifyFavorites(gidArray, srcCat, dstCat)
                            FavoritesIndexSync.onMoved(gidArray.asList(), dstCat)
                        }
                    }
                    // We refresh anyway as cloud data maybe partially modified
                    data.refresh()
                }
            }
        }
    }
}
