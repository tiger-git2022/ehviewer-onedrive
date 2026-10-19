package com.hippo.ehviewer.ui.main

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.CrystalCard
import com.ehviewer.core.ui.component.RatingWidgetReuse
import com.ehviewer.core.ui.util.TransitionsVisibilityScope
import com.ehviewer.core.ui.util.listThumbGenerator
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.util.FileUtils

@Composable
context(_: SharedTransitionScope, _: TransitionsVisibilityScope)
fun DownloadCard(
    onClick: () -> Unit,
    onThumbClick: () -> Unit,
    onLongClick: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    info: DownloadInfo,
    selectMode: Boolean,
    showProgress: Boolean,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) = CrystalCard(modifier = modifier, onClick = onClick, onLongClick = onLongClick, interactionSource = interactionSource) {
    Row {
        val thumb = remember {
            movableContentOf<DownloadInfo> {
                with(listThumbGenerator) {
                    EhAsyncCropThumb(
                        key = it,
                        modifier = Modifier.aspectRatio(DEFAULT_RATIO).fillMaxSize(),
                    )
                }
            }
        }
        if (selectMode) {
            Card {
                thumb(info)
            }
        } else {
            Card(onClick = onThumbClick) {
                thumb(info)
            }
        }
        val stateFailed = stringResource(R.string.download_state_failed)
        val stateFailed2 = stringResource(R.string.download_state_failed_2, info.legacy)
        val downloadState by DownloadManager.collectDownloadState(info.gid)
        Column(modifier = Modifier.padding(start = 8.dp, top = 2.dp, end = 4.dp)) {
            // Fork: laid out like the gallery list (GalleryInfoListItem): a title of up to three lines in the space left,
            // then one small line for the rating, the uploader and the state and a smaller category tag, at the bottom
            Text(
                text = EhUtils.getSuitableTitle(info),
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 20.sp),
            )
            if (downloadState != DownloadInfo.STATE_DOWNLOAD) {
                val stateText = when (downloadState) {
                    DownloadInfo.STATE_NONE -> stringResource(R.string.download_state_none)
                    DownloadInfo.STATE_WAIT -> stringResource(R.string.download_state_wait)
                    DownloadInfo.STATE_FAILED -> if (info.legacy <= 0) stateFailed else stateFailed2
                    DownloadInfo.STATE_FINISH -> {
                        // Fork: those in its CBZ, which may have been changed since the download
                        val pages = rememberDownloadPages(info)
                        if (showProgress) {
                            val readProgress by remember { EhDB.getReadProgressFlow(info.gid) }.collectAsState(0)
                            if (readProgress > 0) "${readProgress + 1}/${pages}P" else "${pages}P"
                        } else {
                            "${pages}P"
                        }
                    }
                    else -> null // The item has been removed and this will be disposed soon
                }
                ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RatingWidgetReuse(rating = info.rating, ratingSize = 12.dp, ratingInterval = 0.dp)
                        Text(
                            text = info.uploader.orEmpty(),
                            modifier = Modifier.weight(1f).alpha(if (info.disowned) 0.5f else 1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(text = stateText.orEmpty(), maxLines = 1)
                    }
                }
            } else {
                var total by remember { mutableIntStateOf(info.total) }
                var finished by remember { mutableIntStateOf(info.finished) }
                var speed by remember { mutableLongStateOf(info.speed) }
                DownloadManager.updatedDownloadInfo(info) {
                    total = this.total
                    finished = this.finished
                    speed = this.speed
                }
                ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                    if (total <= 0 || finished < 0) {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        Row {
                            Text(text = "$finished/$total")
                            Spacer(modifier = Modifier.weight(1f))
                            Text(text = FileUtils.humanReadableByteCount(speed.coerceAtLeast(0)) + "/S")
                        }
                        LinearWavyProgressIndicator(
                            progress = { finished.toFloat() / total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val categoryColor = EhUtils.getCategoryColor(info.category)
                val categoryText = EhUtils.getCategory(info.category).uppercase()
                Text(
                    text = categoryText,
                    modifier = Modifier.clip(ShapeDefaults.Small).background(categoryColor).padding(vertical = 1.dp, horizontal = 6.dp),
                    color = EhUtils.getCategoryTextColor(categoryColor),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
                // Fork: the size, to decide whether to download it
                DownloadSizeText(info = info, state = downloadState, modifier = Modifier.padding(start = 8.dp))
                Spacer(modifier = Modifier.weight(1f))
                val running = downloadState == DownloadInfo.STATE_WAIT || downloadState == DownloadInfo.STATE_DOWNLOAD
                val icon = when (downloadState) {
                    DownloadInfo.STATE_WAIT, DownloadInfo.STATE_DOWNLOAD -> Icons.Default.Pause
                    DownloadInfo.STATE_FINISH -> Icons.Default.DownloadDone
                    else -> Icons.Default.PlayArrow
                }
                // Fork: a 32dp button, so the row is not much taller than the tag and the title keeps its room
                if (selectMode) {
                    Box(modifier = Modifier.offset(4.dp).size(32.dp), contentAlignment = Alignment.Center) {
                        Icon(imageVector = icon, contentDescription = null)
                    }
                } else {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        IconButton(
                            onClick = if (running) onStop else onStart,
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier.offset(4.dp).size(32.dp),
                        ) {
                            Icon(imageVector = icon, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}
