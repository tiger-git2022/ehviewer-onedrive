package com.hippo.ehviewer.coil

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.paging.compose.LazyPagingItems
import coil3.decode.BlackholeDecoder
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.SizeResolver
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.model.V2GalleryPreview
import com.hippo.ehviewer.chapters.Wnacg
import com.hippo.ehviewer.client.getThumbKey
import com.hippo.ehviewer.client.getV2PreviewKey
import com.hippo.ehviewer.client.thumbUrl
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.ktbuilder.execute
import com.hippo.ehviewer.wnacg.WNACG_TOKEN
import com.hippo.ehviewer.wnacg.isWnacg

// Load in original size so the memory cache can be reused for preload requests
fun ImageRequest.Builder.ehUrl(info: GalleryInfo) = apply {
    val key = info.thumbKey!!
    data(info.thumbUrl)
    size(SizeResolver.ORIGINAL)
    val downloadInfo = (info as? DownloadInfo) ?: DownloadManager.getDownloadInfo(info.gid)
    if (downloadInfo != null) {
        downloadInfo(downloadInfo)
    }
    memoryCacheKey(key)
    diskCacheKey(key)
    // Fork: the images of 紳士漫畫 load from its page, see wnacg/
    if (isWnacg(info.gid)) wnacgReferer(-info.gid)
}

// Load in original size so the memory cache can be reused for preload requests
fun ImageRequest.Builder.ehPreview(preview: GalleryPreview) = apply {
    with(preview) {
        val key = if (this is V2GalleryPreview) getV2PreviewKey(url) else getThumbKey(url)
        data(url)
        size(SizeResolver.ORIGINAL)
        memoryCacheKey(key)
        diskCacheKey(key)
        if (pToken == WNACG_TOKEN) wnacgReferer(null)
    }
}

/** Fork: an image of 紳士漫畫, loaded as from the page of album [aid] (or from the site when not known). */
fun ImageRequest.Builder.wnacgReferer(aid: Long?) = httpHeaders(
    NetworkHeaders.Builder().set("Referer", aid?.let(Wnacg::albumUrl) ?: "${Wnacg.BASE}/").build(),
)

fun ImageRequest.Builder.justDownload() = apply {
    memoryCachePolicy(CachePolicy.DISABLED)
    decoderFactory(BlackholeDecoder.Factory())
}

@Composable
inline fun <T : Any> PrefetchAround(data: LazyPagingItems<T>, index: Int, distance: Int, crossinline f: (T) -> ImageRequest) {
    data.peek((index - distance).coerceAtLeast(0))?.let { fetchBefore ->
        LaunchedEffect(fetchBefore) {
            f(fetchBefore).execute()
        }
    }
    data.peek((index + distance).coerceAtMost(data.itemCount - 1))?.let { fetchAhead ->
        LaunchedEffect(fetchAhead) {
            f(fetchAhead).execute()
        }
    }
}
