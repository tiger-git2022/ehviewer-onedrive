package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Card
import androidx.compose.material3.ShapeDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.ui.util.thenIf
import com.hippo.ehviewer.ktbuilder.imageRequest
import com.hippo.ehviewer.restore.RestoredBadge
import com.hippo.ehviewer.ui.tools.shouldCrop
import java.util.concurrent.ConcurrentHashMap

// Fork: remembers each gallery's resolved crop decision so recomposing on back navigation never flashes Fit -> Crop
private val thumbCropCache = ConcurrentHashMap<Long, Boolean>()

private fun initialContentScale(key: GalleryInfo): ContentScale {
    val crop = thumbCropCache[key.gid] ?: key.shouldCrop
    return if (crop) ContentScale.Crop else ContentScale.Fit
}

private fun recordThumbCrop(gid: Long, crop: Boolean) {
    if (thumbCropCache.size > 1024) thumbCropCache.clear()
    thumbCropCache[gid] = crop
}

@Composable
@NonRestartableComposable
fun requestOf(model: GalleryInfo) = with(LocalContext.current) {
    remember(model) { imageRequest(model) }
}

@Composable
fun EhAsyncCropThumb(
    key: GalleryInfo,
    modifier: Modifier = Modifier,
) = Box(modifier = Modifier.clip(ShapeDefaults.Medium)) {
    var contentScale by remember(key.gid) { mutableStateOf(initialContentScale(key)) }
    val request = requestOf(key)
    Image(
        // https://github.com/coil-kt/coil/issues/2959
        painter = rememberAsyncImagePainter(
            model = request,
            onSuccess = {
                val crop = it.result.image.shouldCrop
                recordThumbCrop(key.gid, crop)
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit
            },
        ),
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
    )
    // Fork: a gallery restored from PandaChika (the download list)
    RestoredBadge(key.gid, Modifier.align(Alignment.TopEnd))
}

@Composable
fun EhThumbCard(
    key: GalleryInfo,
    modifier: Modifier = Modifier,
) = Card(modifier = modifier) {
    Box(modifier = Modifier.clip(ShapeDefaults.Medium)) {
        var contentScale by remember(key.gid) { mutableStateOf(initialContentScale(key)) }
        val request = requestOf(key)
        val painter = rememberAsyncImagePainter(
            model = request,
            onSuccess = {
                val crop = it.result.image.shouldCrop
                recordThumbCrop(key.gid, crop)
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit
            },
        )
        val state by painter.state.collectAsState()
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.thenIf(state !is AsyncImagePainter.State.Success) {
                // Keep applying this when state is `Loading` to avoid cutting off the ripple
                clickable { if (state is AsyncImagePainter.State.Error) painter.restart() }
            }.fillMaxSize(),
            contentScale = contentScale,
        )
        // Fork: a gallery restored from PandaChika
        RestoredBadge(key.gid, Modifier.align(Alignment.TopEnd))
    }
}
