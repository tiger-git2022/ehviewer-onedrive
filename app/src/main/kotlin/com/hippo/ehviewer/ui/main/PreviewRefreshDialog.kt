package com.hippo.ehviewer.ui.main

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryPreview
import com.hippo.ehviewer.EhApplication.Companion.thumbCache
import com.hippo.ehviewer.coil.justDownload
import com.hippo.ehviewer.coil.read
import com.hippo.ehviewer.ktbuilder.execute
import com.hippo.ehviewer.ktbuilder.imageRequest
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.EhAlertDialog
import com.hippo.ehviewer.ui.tools.dialog
import com.hippo.ehviewer.util.FileUtils
import java.util.Locale
import kotlin.coroutines.resume
import moe.tarsin.coroutines.runSuspendCatching

/*
 * Fork: "Refresh previews" in the menu of a gallery. Fetches the links of the preview pages shown so far
 * again and downloads their images, skipping the cache, step by step with the time each step took and why
 * a step failed, so a slow or unreachable H@H client tells apart from a missing connection. The previews
 * then load again with the new links.
 */

private sealed interface StepState {
    data object Running : StepState
    data class Done(val detail: String) : StepState
    data class Failed(val reason: String) : StepState
}

private class Step(title: String, val subtitle: String? = null) {
    var title by mutableStateOf(title)
    var state by mutableStateOf<StepState>(StepState.Running)
}

context(_: DialogState)
suspend fun showPreviewRefresh(refetcher: PreviewRefetcher): Unit = dialog { cont ->
    val context = LocalContext.current
    val steps = remember { mutableStateListOf<Step>() }
    var finished by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        refreshPreviews(context, refetcher) { step ->
            steps += step
            listState.animateScrollToItem(steps.lastIndex)
        }
        finished = true
    }
    EhAlertDialog(
        onDismissRequest = { if (cont.isActive) cont.resume(Unit) },
        confirmButton = {
            TextButton(onClick = { if (cont.isActive) cont.resume(Unit) }, shapes = ButtonDefaults.shapes()) {
                Text(text = stringResource(id = if (finished) R.string.preview_refresh_close else android.R.string.cancel))
            }
        },
        title = { Text(text = stringResource(id = R.string.refresh_previews)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(steps) { StepRow(it) }
                }
                if (finished) {
                    val failed = steps.count { it.state is StepState.Failed }
                    Text(
                        text = if (failed == 0) {
                            stringResource(R.string.preview_refresh_all_ok)
                        } else {
                            stringResource(R.string.preview_refresh_some_failed, failed)
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        },
    )
}

@Composable
private fun StepRow(step: Step) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (step.state) {
            StepState.Running -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            is StepState.Done -> Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            is StepState.Failed -> Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = step.title, style = MaterialTheme.typography.bodyMedium)
            step.subtitle?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (val state = step.state) {
                StepState.Running -> Unit
                is StepState.Done -> Text(text = state.detail, style = MaterialTheme.typography.bodySmall)
                is StepState.Failed -> Text(text = state.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private suspend fun refreshPreviews(context: Context, refetcher: PreviewRefetcher, add: suspend (Step) -> Unit) {
    for (page in refetcher.shownPages()) {
        val linksStep = Step(context.getString(R.string.preview_refresh_links, page + 1))
        add(linksStep)
        val start = SystemClock.elapsedRealtime()
        val previews = runSuspendCatching { refetcher.fetchPage(page, force = true) }.getOrElse {
            linksStep.state = StepState.Failed(previewFailureReason(context, it))
            continue
        }
        linksStep.state = StepState.Done(seconds(context, SystemClock.elapsedRealtime() - start))
        downloadImages(context, page, previews, add)
        refetcher.reloadAll()
    }
}

private suspend fun downloadImages(context: Context, page: Int, previews: List<GalleryPreview>, add: suspend (Step) -> Unit) {
    // Previews of a page share one image (a sprite) or have one each
    val images = previews.distinctBy { it.url }
    val hosts = images.mapNotNull { Uri.parse(it.url).host }.distinct().joinToString()
    val step = Step(context.getString(R.string.preview_refresh_images, page + 1, 0, images.size), hosts.ifEmpty { null })
    add(step)
    var done = 0
    var tried = 0
    var bytes = 0L
    var firstError: String? = null
    val start = SystemClock.elapsedRealtime()
    images.forEach { preview ->
        val request = with(context) {
            imageRequest(preview) {
                justDownload()
                // From the network, not the cache: that is what this checks
                diskCachePolicy(CachePolicy.WRITE_ONLY)
            }
        }
        val result = request.execute()
        if (result is ErrorResult) {
            if (firstError == null) firstError = previewFailureReason(context, result.throwable)
        } else {
            done++
            request.diskCacheKey?.let { key ->
                bytes += thumbCache.read(key) { thumbCache.fileSystem.metadata(data).size ?: 0L } ?: 0L
            }
        }
        step.title = context.getString(R.string.preview_refresh_images, page + 1, ++tried, images.size)
    }
    val time = seconds(context, SystemClock.elapsedRealtime() - start)
    step.state = if (firstError == null) {
        StepState.Done(context.getString(R.string.preview_refresh_images_done, done, FileUtils.humanReadableByteCount(bytes), time))
    } else {
        StepState.Failed(context.getString(R.string.preview_refresh_images_failed, images.size - done, images.size, firstError, time))
    }
}

private fun seconds(context: Context, millis: Long) = context.getString(R.string.preview_refresh_seconds, String.format(Locale.ROOT, "%.1f", millis / 1000f))
