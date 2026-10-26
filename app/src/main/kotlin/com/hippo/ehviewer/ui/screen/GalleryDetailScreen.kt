package com.hippo.ehviewer.ui.screen

import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.collection.SieveCache
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NewLabel
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastJoinToString
import androidx.lifecycle.viewModelScope
import com.ehviewer.core.data.model.findBaseInfo
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.GalleryTagGroup
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.ui.component.GlassEdge
import com.ehviewer.core.ui.component.GlassStyle
import com.ehviewer.core.ui.component.edgeGlass
import com.ehviewer.core.ui.util.LocalWindowSizeClass
import com.ehviewer.core.ui.util.isExpanded
import com.ehviewer.core.ui.util.launchInVM
import com.ehviewer.core.ui.util.rememberInVM
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.fillInfo
import com.hippo.ehviewer.client.exception.GalleryUnavailableException
import com.hippo.ehviewer.coil.justDownload
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.ktbuilder.executeIn
import com.hippo.ehviewer.ktbuilder.imageRequest
import com.hippo.ehviewer.restore.RestoredGalleries
import com.hippo.ehviewer.restore.restoredDetail
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.ehviewer.sync.MihonSearch
import com.hippo.ehviewer.sync.OneDriveClient
import com.hippo.ehviewer.sync.OneDriveSyncState
import com.hippo.ehviewer.ui.AutoLocalFavorite
import com.hippo.ehviewer.ui.LocalPageBackdrop
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.glassContent
import com.hippo.ehviewer.ui.main.GalleryDetailErrorTip
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.main.OneDriveSyncIcon
import com.hippo.ehviewer.ui.main.PreviewRefetcher
import com.hippo.ehviewer.ui.main.showPreviewRefresh
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.openBrowser
import com.hippo.ehviewer.ui.rememberLiquidGlass
import com.hippo.ehviewer.ui.tools.BarAction
import com.hippo.ehviewer.ui.tools.BarActions
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.ui.tools.awaitSelectAction
import com.hippo.ehviewer.ui.tools.awaitSelectTags
import com.hippo.ehviewer.ui.tools.mouseDragScroll
import com.hippo.ehviewer.util.AppHelper
import com.hippo.ehviewer.util.awaitActivityResult
import com.hippo.ehviewer.util.bgWork
import com.hippo.ehviewer.util.displayString
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import moe.tarsin.coroutines.runSuspendCatching
import moe.tarsin.snackbar
import moe.tarsin.tip

typealias VoteTag = suspend GalleryDetail.(String, Int) -> Unit

val detailCache = SieveCache<Long, GalleryDetail>(25)

@Serializable
sealed interface GalleryDetailScreenArgs

@Serializable
data class GalleryInfoArgs(
    val galleryInfo: BaseGalleryInfo,
) : GalleryDetailScreenArgs

@Serializable
data class TokenArgs(
    val gid: Long,
    val token: String,
    val page: Int = 0,
) : GalleryDetailScreenArgs

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.GalleryDetailScreen(args: GalleryDetailScreenArgs, navigator: DestinationsNavigator) = Screen(navigator, recordsGlass = true) {
    val (gid, token) = remember(args) {
        when (args) {
            is GalleryInfoArgs -> with(args.galleryInfo) { gid to token }
            is TokenArgs -> args.gid to args.token
        }
    }
    val galleryDetailUrl = remember(gid, token) { EhUrl.getGalleryDetailUrl(gid, token) }
    contextOf<MainActivity>().ProvideAssistContent(galleryDetailUrl)

    var galleryInfo by rememberInVM {
        val casted = args as? GalleryInfoArgs
        mutableStateOf<GalleryInfo?>(casted?.galleryInfo)
    }

    var getDetailError by rememberSaveable { mutableStateOf("") }
    val noBackup = stringResource(R.string.pandachaika_no_backup)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyGridState()

    (galleryInfo as? GalleryDetail)?.apply {
        rememberInVM(this) {
            if (Settings.preloadThumbAggressively.value) {
                previewList.forEach {
                    imageRequest(it) { justDownload() }.executeIn(viewModelScope)
                }
            }
        }
    }

    if (galleryInfo !is GalleryDetail && getDetailError.isBlank()) {
        LaunchedEffect(Unit) {
            val galleryDetail = detailCache[gid]
                ?: runSuspendCatching {
                    withIOContext {
                        // Fork: a gallery E-Hentai removed opens from its backup on PandaChika, see restore/
                        try {
                            EhEngine.getGalleryDetail(galleryDetailUrl).also { RestoredGalleries.dismiss(it.gid) }
                        } catch (e: GalleryUnavailableException) {
                            restoredDetail(gid, token, galleryInfo)
                                ?: throw GalleryUnavailableException(e.message.orEmpty() + "\n" + noBackup)
                        }
                    }
                }.onSuccess { galleryDetail ->
                    detailCache[galleryDetail.gid] = galleryDetail
                }.onFailure { e ->
                    galleryInfo?.let { EhDB.putHistoryInfo(it) }
                    getDetailError = e.displayString()
                }.getOrNull()
            galleryDetail?.let {
                EhDB.putHistoryInfo(it)
                galleryInfo = it
                // Fork: in no favorites, it goes to the local favorites
                AutoLocalFavorite.onOpened(it, (args as? GalleryInfoArgs)?.galleryInfo)
            }
        }
    }

    val voteTag: VoteTag = { tag, vote ->
        runSuspendCatching {
            EhEngine.voteTag(apiUid, apiKey, gid, token, tag, vote)
        }.onSuccess { result ->
            val new = copy(tagGroups = result).apply { fillInfo() }
            detailCache[gid] = new
            galleryInfo = new
            tip(R.string.tag_vote_successfully)
        }.onFailure { e ->
            tip(e.displayString())
        }
    }

    val signInFirst = stringResource(R.string.sign_in_first)
    val addTag = stringResource(id = R.string.action_add_tag)
    val refresh = stringResource(id = R.string.refresh)
    val clearCache = stringResource(id = R.string.clear_image_cache)
    val cacheCleared = stringResource(R.string.image_cache_cleared)
    val openInBrowser = stringResource(id = R.string.open_in_other_app)
    val searchInMihon = stringResource(id = R.string.search_in_mihon)
    val mihonNotInstalled = stringResource(id = R.string.mihon_not_installed)
    val exportArchive = stringResource(id = R.string.export_as_archive)
    val exportSuccess = stringResource(id = R.string.export_as_archive_success)
    val exportFailed = stringResource(id = R.string.export_as_archive_failed)
    val windowSizeClass = LocalWindowSizeClass.current
    val share = stringResource(id = R.string.share)
    val oneDriveAccount by OneDriveSyncState.accountId.collectAsState()
    val syncIconShown = OneDriveClient.isSupported && oneDriveAccount != null
    // Fork: with liquid glass, the top bar refracts the details below it, see LiquidGlass.kt
    val backdrop = LocalPageBackdrop.current
    // Only once the details scroll below it: at the top, a glass over nothing stood out
    val scrolled by remember { derivedStateOf { scrollBehavior.state.collapsedFraction > 0f || scrollBehavior.state.overlappedFraction > 0f } }
    val glass = rememberLiquidGlass(backdrop).takeIf { scrolled }
    val barColor = MaterialTheme.colorScheme.surfaceContainer
    // Fork: fresh links for previews that fail to load and "Refresh previews", see PreviewRefetcher
    val previewRefetcher = (galleryInfo as? GalleryDetail)?.takeIf { it.previewList.isNotEmpty() }?.let { detail ->
        rememberInVM(detail.previewList) { PreviewRefetcher(detail.gid, detail.token, detail.previewList.size, viewModelScope) }
    }
    val refreshPreviews = stringResource(id = R.string.refresh_previews)
    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                // Fork: only its bottom edge shows, the top and the sides melt into the screen
                modifier = Modifier.edgeGlass(glass, GlassEdge.Bottom, barColor.copy(alpha = GLASS_BAR_TINT), GlassStyle.Panel, BAR_GLASS_CORNER),
                // Fork: over the glass, the buttons in the color of the title, white at night and black by day: the paler
                // color of the Material buttons got lost over the pages showing through
                colors = if (backdrop != null) {
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    TopAppBarDefaults.topAppBarColors()
                },
                title = {
                    galleryInfo?.let {
                        Text(
                            text = EhUtils.getSuitableTitle(it),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { NavigationIcon() },
                scrollBehavior = scrollBehavior,
                actions = {
                    // Fork: the actions as a list, so that over liquid glass the overflow menu is glass too, see BarActions
                    val shareAction = BarAction(share, Icons.Default.Share) {
                        AppHelper.share(contextOf<MainActivity>(), galleryDetailUrl)
                        // In case the link is copied to the clipboard
                        Settings.clipboardTextHashCode = galleryDetailUrl.hashCode()
                    }
                    val actions = buildList {
                        // Fork: with a OneDrive account, the sync state (a ring with the progress while syncing)
                        // takes the place of the share button, as in the search bar; sharing moves to the menu
                        if (!syncIconShown) add(shareAction)
                        add(
                            BarAction(addTag, Icons.Default.NewLabel) {
                                val detail = galleryInfo as? GalleryDetail ?: return@BarAction
                                launchIO {
                                    if (detail.apiUid < 0) {
                                        snackbar(signInFirst)
                                    } else {
                                        val tags = awaitSelectTags()
                                        if (tags.isNotEmpty()) {
                                            val text = tags.fastJoinToString(",")
                                            detail.voteTag(text, 1)
                                        }
                                    }
                                }
                            },
                        )
                        add(
                            BarAction(refresh, Icons.Default.Refresh) {
                                // Invalidate cache
                                detailCache.remove(gid)

                                // Trigger recompose
                                galleryInfo = galleryInfo?.findBaseInfo()
                                getDetailError = ""
                            },
                        )
                        // Fork: fetches the links of the previews and their images again, step by step, see PreviewRefreshDialog
                        if (previewRefetcher != null) add(BarAction(refreshPreviews) { launchIO { showPreviewRefresh(previewRefetcher) } })
                        if (syncIconShown) add(shareAction)
                        add(
                            BarAction(clearCache) {
                                val gd = galleryInfo as? GalleryDetail ?: return@BarAction
                                launchIO {
                                    awaitConfirmationOrCancel(
                                        confirmText = R.string.clear_all,
                                        title = R.string.clear_image_cache,
                                    ) {
                                        Text(text = stringResource(id = R.string.clear_image_cache_confirm))
                                    }
                                    SpiderDen(gd).clearCache()
                                    snackbar(cacheCleared)
                                }
                            },
                        )
                        add(BarAction(openInBrowser) { openBrowser(galleryDetailUrl) })
                        // Fork: a global search in Mihon of the title or of the artist, as written in Japanese and in
                        // the default (romanized) title: sources list one or the other
                        add(
                            BarAction(searchInMihon) {
                                val info = galleryInfo ?: return@BarAction
                                val titles = listOfNotNull(info.titleJpn, info.title).filter { it.isNotBlank() }
                                val tagArtist = (info as? GalleryDetail)?.tagGroups?.artistName()
                                val queries = (titles.map { MihonSearch.coreTitle(it) } + titles.mapNotNull { MihonSearch.artist(it) } + listOfNotNull(tagArtist))
                                    .filter { it.isNotBlank() }
                                    .distinctBy { it.lowercase() }
                                val activity = contextOf<MainActivity>()
                                launchIO {
                                    awaitSelectAction(title = R.string.search_in_mihon) {
                                        queries.forEach { query ->
                                            onSelect(query) {
                                                if (!MihonSearch.search(activity, query)) snackbar(mihonNotInstalled)
                                            }
                                        }
                                    }()
                                }
                            },
                        )
                        add(
                            BarAction(exportArchive) {
                                launchIO {
                                    val downloadInfo = DownloadManager.getDownloadInfo(gid)
                                    val canExport = downloadInfo?.state == DownloadInfo.STATE_FINISH
                                    if (!canExport) {
                                        awaitConfirmationOrCancel(
                                            showCancelButton = false,
                                            text = { Text(text = stringResource(id = R.string.download_gallery_first)) },
                                        )
                                    } else {
                                        val info = galleryInfo!!
                                        val uri = awaitActivityResult(
                                            CreateDocument("application/vnd.comicbook+zip"),
                                            EhUtils.getSuitableTitle(info) + ".cbz",
                                        )
                                        val dirname = downloadInfo.dirname
                                        if (uri != null && dirname != null) {
                                            val file = uri.toOkioPath()
                                            val msg = runCatching {
                                                bgWork {
                                                    withIOContext {
                                                        SpiderDen(info, dirname).exportAsCbz(file)
                                                    }
                                                }
                                                exportSuccess
                                            }.getOrElse {
                                                logcat(it)
                                                file.delete()
                                                exportFailed
                                            }
                                            snackbar(message = msg)
                                        }
                                    }
                                }
                            },
                        )
                    }
                    BarActions(
                        actions = actions,
                        maxItemCount = if (windowSizeClass.isExpanded) 4 else 2,
                        glass = backdrop != null,
                        leading = if (syncIconShown) ({ OneDriveSyncIcon() }) else null,
                    )
                },
            )
        },
    ) {
        val gi = galleryInfo
        if (gi != null) {
            if (args is TokenArgs && args.page != 0) {
                val from = stringResource(id = R.string.read_from, args.page)
                val read = stringResource(id = R.string.read)
                launchInVM {
                    val result = snackbar(from, read, true)
                    if (result == SnackbarResult.ActionPerformed) {
                        navToReader(gi.findBaseInfo(), args.page)
                    }
                }
            }
            // Fork: a mouse drags the details too, see mouseDragScroll. On a box around them: the details pass their
            // modifier on to some of their rows as well
            Box(modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection).mouseDragScroll(gridState)) {
                GalleryDetailContent(
                    galleryInfo = gi,
                    contentPadding = it,
                    getDetailError = getDetailError,
                    onRetry = { getDetailError = "" },
                    voteTag = voteTag,
                    // Fork: recorded for the glass of the top bar
                    modifier = Modifier.glassContent(backdrop).expandTopBarOnWheelAtTop(gridState, scrollBehavior),
                    state = gridState,
                    previewRefetcher = previewRefetcher,
                )
            }
        } else if (getDetailError.isNotBlank()) {
            GalleryDetailErrorTip(error = getDetailError, onClick = { getDetailError = "" })
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularWavyProgressIndicator()
            }
        }
    }
}

/**
 * Fork: a mouse wheel turned up at the top of the details expands the top bar. A drag goes on into the bar once the
 * details reach the top; the wheel stops there, the details having nothing left to scroll, and the bar stayed collapsed.
 */
private fun Modifier.expandTopBarOnWheelAtTop(state: LazyGridState, behavior: TopAppBarScrollBehavior) = pointerInput(state, behavior) {
    coroutineScope {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val up = event.type == PointerEventType.Scroll && event.changes.any { it.scrollDelta.y < 0f }
                if (up && !state.canScrollBackward && behavior.state.heightOffset < 0f) {
                    launch {
                        behavior.state.contentOffset = 0f
                        animate(behavior.state.heightOffset, 0f) { value, _ -> behavior.state.heightOffset = value }
                    }
                }
            }
        }
    }
}

// Fork: the first artist tag, for a search in Mihon
private fun List<GalleryTagGroup>.artistName() = find { it.namespace == TagNamespace.Artist }?.tags?.firstOrNull()?.text

// Fork: the lower corners of the glass of the top bar
private val BAR_GLASS_CORNER = 24.dp
