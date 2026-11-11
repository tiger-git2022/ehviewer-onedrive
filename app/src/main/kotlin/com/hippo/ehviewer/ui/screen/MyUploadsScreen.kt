package com.hippo.ehviewer.ui.screen

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.network.EhCookieStore
import com.ehviewer.core.ui.component.CrystalCard
import com.ehviewer.core.ui.component.FastScrollLazyColumn
import com.ehviewer.core.util.launch
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.exception.NotLoggedInException
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.myuploads.MyUpload
import com.hippo.ehviewer.myuploads.MyUploads
import com.hippo.ehviewer.myuploads.MyUploadsWork
import com.hippo.ehviewer.myuploads.UploadComment
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.doGalleryInfoAction
import com.hippo.ehviewer.ui.main.GalleryInfoListItem
import com.hippo.ehviewer.ui.main.GlassToggleButton
import com.hippo.ehviewer.ui.main.LocalRefreshIndicatorHost
import com.hippo.ehviewer.ui.main.RefreshIndicator
import com.hippo.ehviewer.ui.tools.EhModalBottomSheet
import com.hippo.ehviewer.ui.tools.dialog
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import moe.tarsin.navigate
import moe.tarsin.snackbar
import moe.tarsin.string

/** The latest comments shown on a card besides the new ones. */
private const val LATEST_SHOWN = 3

/** The page is read again while it stays open. */
private const val AUTO_REFRESH_MILLIS = 60 * 60 * 1000L

/**
 * Fork: 「我的上传」 in the drawer, the galleries published from this account with their comments on the cards, the new
 * ones marked; the comment button on the search bar keeps only those with new comments. See [MyUploads].
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.MyUploadsScreen(navigator: DestinationsNavigator) = Screen(navigator, recordsGlass = true) {
    val title = stringResource(id = R.string.my_uploads)
    val hint = stringResource(R.string.search_bar_hint, title)
    var searchBarExpanded by rememberSaveable { mutableStateOf(false) }
    var searchBarOffsetY by remember { mutableIntStateOf(0) }
    var keyword by rememberSaveable { mutableStateOf("") }
    DrawerHandle(!searchBarExpanded)

    val data by MyUploads.data.collectAsState()
    val progress by MyUploads.progress.collectAsState()
    var refreshing by remember { mutableStateOf(false) }

    suspend fun refresh(all: Boolean) {
        val result = runCatching { MyUploads.refresh(all) }
        MyUploadsWork.schedule()
        result.onFailure { e ->
            val message = if (e is NotLoggedInException) string(R.string.my_uploads_need_sign_in) else e.message ?: e.toString()
            snackbar(string(R.string.my_uploads_failed, message))
        }
    }

    // All the comments of a gallery in a sheet of glass, with a box to reply below them
    fun showComments(upload: MyUpload, reply: Boolean) = launch {
        dialog<Unit> { cont ->
            EhModalBottomSheet(onDismissRequest = { cont.cancel() }) {
                UploadCommentsSheet(gid = upload.gid, reply = reply)
            }
        }
    }

    // Read again on opening, then hourly while open; only the galleries due
    LaunchedEffect(Unit) {
        while (true) {
            if (EhCookieStore.hasSignedIn()) refresh(false)
            delay(AUTO_REFRESH_MILLIS)
        }
    }

    val shown = remember(data, keyword) {
        data.items.filter { upload ->
            (!data.onlyNew || MyUploads.newComments(upload, data.seen).isNotEmpty()) &&
                (keyword.isBlank() || EhUtils.getSuitableTitle(upload.info).contains(keyword.trim(), ignoreCase = true))
        }
    }

    SearchBarScreen(
        onApplySearch = { keyword = it },
        expanded = searchBarExpanded,
        onExpandedChange = { searchBarExpanded = it },
        onClearSearch = if (keyword.isNotEmpty()) {
            { keyword = "" }
        } else {
            null
        },
        title = if (data.onlyNew) stringResource(R.string.my_uploads_only_new_title) else title,
        searchFieldHint = hint,
        searchBarOffsetY = { searchBarOffsetY },
        trailingIcon = {
            // Like hiding the favorited: a lens of glass while on
            GlassToggleButton(
                checked = data.onlyNew,
                onCheckedChange = { launch { MyUploads.setOnlyNew(it) } },
            ) {
                Icon(imageVector = Icons.AutoMirrored.Default.Comment, contentDescription = stringResource(R.string.my_uploads_only_new))
            }
            var expanded by remember { mutableStateOf(false) }
            IconButton(onClick = { expanded = !expanded }, shapes = IconButtonDefaults.shapes()) {
                Icon(imageVector = Icons.Default.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.my_uploads_refresh_all)) },
                    onClick = {
                        expanded = false
                        launch { refresh(true) }
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.my_uploads_mark_all_read)) },
                    onClick = {
                        expanded = false
                        launch { MyUploads.markAllRead() }
                    },
                )
            }
        },
    ) { paddingValues ->
        val density = LocalDensity.current
        val searchBarConnection = remember {
            val topPaddingPx = with(density) { paddingValues.calculateTopPadding().roundToPx() }
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    searchBarOffsetY = (searchBarOffsetY + consumed.y).roundToInt().coerceIn(-topPaddingPx, 0)
                    return Offset.Zero
                }
            }
        }
        val marginH = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_h)
        val cardHeight by collectListThumbSizeAsState()
        val showPages by Settings.showGalleryPages.collectAsState()
        val showProgress by Settings.showReadingProgress.collectAsState()
        val refreshState = rememberPullToRefreshState()
        Box(
            modifier = Modifier.fillMaxSize().pullToRefresh(isRefreshing = refreshing, state = refreshState) {
                refreshing = true
                launch {
                    refresh(false)
                    refreshing = false
                }
            },
        ) {
            FastScrollLazyColumn(
                modifier = Modifier.nestedScroll(searchBarConnection).fillMaxSize(),
                contentPadding = paddingValues,
                verticalArrangement = Arrangement.spacedBy(dimensionResource(com.hippo.ehviewer.R.dimen.gallery_list_interval)),
            ) {
                item(key = "summary") {
                    Text(
                        text = summary(data, shown.size, progress),
                        modifier = Modifier.padding(horizontal = marginH + 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (data.onlyNew) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(shown, key = { it.gid }) { upload ->
                    Column(modifier = Modifier.padding(horizontal = marginH)) {
                        GalleryInfoListItem(
                            onClick = { navigate(upload.info.asDst()) },
                            onLongClick = { launch { doGalleryInfoAction(upload.info) } },
                            info = upload.info,
                            showPages = showPages,
                            showProgress = showProgress,
                            modifier = Modifier.height(cardHeight),
                        )
                        UploadComments(
                            upload = upload,
                            newComments = MyUploads.newComments(upload, data.seen),
                            onAllComments = { showComments(upload, reply = false) },
                            onReply = { showComments(upload, reply = true) },
                            onMarkRead = { launch { MyUploads.markRead(upload.gid) } },
                        )
                    }
                }
            }
            // With liquid glass, the page draws the indicator over the content the glass refracts, see RefreshIndicator
            val host = LocalRefreshIndicatorHost.current
            if (host != null) {
                val top = paddingValues.calculateTopPadding()
                SideEffect {
                    host.state = refreshState
                    host.isRefreshing = refreshing
                    host.top = top
                    host.offsetY = { searchBarOffsetY }
                }
                DisposableEffect(host) {
                    onDispose { if (host.state === refreshState) host.state = null }
                }
            } else {
                RefreshIndicator(
                    state = refreshState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = paddingValues.calculateTopPadding()),
                    offsetY = { searchBarOffsetY },
                )
            }
        }
    }
}

@Composable
private fun summary(data: com.hippo.ehviewer.myuploads.MyUploadsData, shownCount: Int, progress: com.hippo.ehviewer.myuploads.MyUploadsProgress?): String {
    val withNew = data.items.count { MyUploads.newComments(it, data.seen).isNotEmpty() }
    val newTotal = MyUploads.newCount(data)
    val head = when {
        data.items.isEmpty() && progress == null -> stringResource(R.string.my_uploads_empty)
        data.items.isEmpty() -> ""
        data.onlyNew -> stringResource(R.string.my_uploads_summary_only_new, shownCount, data.items.size - shownCount)
        withNew > 0 -> stringResource(R.string.my_uploads_summary_new, data.items.size, withNew, newTotal)
        else -> stringResource(R.string.my_uploads_summary, data.items.size)
    }
    val tail = when {
        progress == null -> if (data.listFetched > 0) {
            stringResource(R.string.my_uploads_updated_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(data.listFetched)))
        } else {
            ""
        }
        progress.total == 0 -> stringResource(R.string.my_uploads_reading_list)
        else -> stringResource(R.string.my_uploads_checking, progress.done, progress.total)
    }
    return listOf(head, tail).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** The comments under the card of [upload]: all the new ones, and the latest ones besides. */
@Composable
private fun UploadComments(
    upload: MyUpload,
    newComments: List<UploadComment>,
    onAllComments: () -> Unit,
    onReply: () -> Unit,
    onMarkRead: () -> Unit,
) {
    val newIds = remember(newComments) { newComments.mapTo(HashSet()) { it.id } }
    val shown = remember(upload.comments, newIds) {
        upload.comments.filterIndexed { index, c -> c.id in newIds || index < LATEST_SHOWN }
    }
    CrystalCard(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val info = upload.info
            val stats = buildList {
                if (info.rating > 0f) add("★%.2f (%d)".format(info.rating, upload.ratingCount))
                if (upload.favorites > 0) add("♥${upload.favorites}")
                add(
                    when {
                        upload.fetched == 0L -> stringResource(R.string.my_uploads_comments_not_read)
                        upload.comments.isEmpty() -> stringResource(R.string.my_uploads_no_comments)
                        else -> stringResource(R.string.my_uploads_comment_count, upload.comments.size)
                    },
                )
            }.joinToString(" · ")
            Text(
                text = stats,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (newComments.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.my_uploads_new_count, newComments.size),
                    modifier = Modifier.background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        shown.forEach { comment -> CommentRow(comment, isNew = comment.id in newIds) }
        if (upload.comments.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onAllComments) { Text(text = stringResource(R.string.my_uploads_all_comments)) }
                TextButton(onClick = onReply) { Text(text = stringResource(R.string.my_uploads_reply)) }
                if (newComments.isNotEmpty()) {
                    TextButton(onClick = onMarkRead) { Text(text = stringResource(R.string.my_uploads_mark_read)) }
                }
            }
        } else if (upload.fetched != 0L) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onReply) { Text(text = stringResource(R.string.my_uploads_reply)) }
            }
        }
    }
}

@Composable
private fun CommentRow(comment: UploadComment, isNew: Boolean, maxLines: Int = 4) {
    val error = MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .then(if (isNew) Modifier.background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)) else Modifier),
    ) {
        Box(modifier = Modifier.width(4.dp).fillMaxHeightIfNew(isNew, error))
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = comment.user,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isNew) {
                    Text(text = stringResource(R.string.my_uploads_new), style = MaterialTheme.typography.labelMedium, color = error)
                }
                Text(
                    text = DateUtils.getRelativeTimeSpanString(comment.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(modifier = Modifier.weight(1f))
                Text(
                    text = if (comment.score > 0) "+${comment.score}" else "${comment.score}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = comment.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The bar on the left of a new comment, as high as the comment. */
private fun Modifier.fillMaxHeightIfNew(isNew: Boolean, color: androidx.compose.ui.graphics.Color) = if (isNew) {
    fillMaxHeight().background(color)
} else {
    this
}

/** All the comments of the gallery [gid], the new ones marked, and a box to reply; [reply] puts the cursor in it. */
@Composable
private fun UploadCommentsSheet(gid: Long, reply: Boolean) {
    val data by MyUploads.data.collectAsState()
    val upload = data.items.find { it.gid == gid } ?: return
    // The ones new when opened stay marked while it is open
    val newIds = remember { MyUploads.newComments(upload, data.seen).mapTo(HashSet()) { it.id } }
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (reply) runCatching { focusRequester.requestFocus() } }
    val tooShort = stringResource(R.string.my_uploads_reply_too_short)
    val sent = stringResource(R.string.my_uploads_reply_sent)
    val failed = stringResource(R.string.comment_failed)
    Column(modifier = Modifier.fillMaxWidth().imePadding()) {
        Text(
            text = EhUtils.getSuitableTitle(upload.info),
            modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (upload.comments.isEmpty()) stringResource(R.string.my_uploads_no_comments) else stringResource(R.string.my_uploads_comment_count, upload.comments.size),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
            items(upload.comments, key = { it.id }) { comment ->
                CommentRow(comment, isNew = comment.id in newIds, maxLines = Int.MAX_VALUE)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    status = null
                },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text(text = stringResource(R.string.my_uploads_reply_hint)) },
                supportingText = status?.let { { Text(text = it) } },
                maxLines = 5,
                enabled = !sending,
            )
            IconButton(
                onClick = {
                    val content = text.trim()
                    if (content.length < 10) {
                        status = tooShort
                        return@IconButton
                    }
                    sending = true
                    scope.launch {
                        runCatching { MyUploads.postComment(gid, content) }.onSuccess {
                            text = ""
                            status = sent
                        }.onFailure {
                            status = failed + "：" + (it.message ?: it.toString())
                        }
                        sending = false
                    }
                },
                enabled = !sending && text.isNotBlank(),
            ) {
                Icon(imageVector = Icons.AutoMirrored.Default.Send, contentDescription = stringResource(R.string.my_uploads_reply))
            }
        }
    }
}
