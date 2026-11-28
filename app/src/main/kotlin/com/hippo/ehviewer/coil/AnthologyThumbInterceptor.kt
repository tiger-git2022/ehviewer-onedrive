package com.hippo.ehviewer.coil

import android.graphics.Bitmap
import coil3.BitmapImage
import coil3.decode.DataSource
import coil3.imageLoader
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.ehviewer.core.files.toUri
import com.ehviewer.core.files.write
import com.ehviewer.core.model.V2GalleryPreview
import com.hippo.ehviewer.EhApplication.Companion.thumbCache
import com.hippo.ehviewer.anthology.AnthologyChapters
import com.hippo.ehviewer.ktbuilder.imageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.asOutputStream

/**
 * Fork: loads a virtual chapter's cover (`ehv-chapter-thumb://<parentGid>/<token>/<page>.jpg`) by fetching its parent
 * gallery's preview sheet once, cropping out that chapter's first page when it is a V2 sprite sheet, caching the
 * standalone JPEG in [thumbCache], and handing the cached file on to Coil's regular decode/memory-cache pipeline.
 */
object AnthologyThumbInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val raw = chain.request.data as? String ?: return chain.proceed()
        val ref = AnthologyChapters.parseThumbKey(raw) ?: return chain.proceed()
        val req = chain.request
        val cacheKey = req.diskCacheKey ?: raw

        thumbCache.read(cacheKey) {
            val fileReq = req.newBuilder().data(data.toUri()).build()
            chain.withRequest(fileReq).proceed()
        }?.let { result ->
            if (result is SuccessResult) return result.copy(request = req)
        }

        val preview = AnthologyChapters.previewOf(ref.parentGid, ref.parentToken, ref.page)
            ?: return ErrorResult(req.error(), req, IllegalStateException("No preview for ${ref.parentGid} page ${ref.page}"))

        val subReq = with(req.context) {
            imageRequest(preview) {
                allowHardware(false)
                memoryCachePolicy(CachePolicy.DISABLED)
            }
        }
        val subResult = req.context.imageLoader.execute(subReq)
        if (subResult !is SuccessResult) {
            val throwable = (subResult as? ErrorResult)?.throwable ?: IllegalStateException("Preview load failed")
            return ErrorResult(req.error(), req, throwable)
        }

        val srcBitmap = when (val img = subResult.image) {
            is BitmapImageWithExtraInfo -> img.image.bitmap
            is BitmapImage -> img.bitmap
            else -> null
        }?.let { bmp ->
            if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        } ?: return ErrorResult(req.error(), req, IllegalStateException("Unsupported preview image"))

        withContext(Dispatchers.Default) {
            val cropped = if (preview is V2GalleryPreview) {
                val left = preview.offsetX.coerceIn(0, (srcBitmap.width - 1).coerceAtLeast(0))
                val width = preview.clipWidth.coerceIn(1, (srcBitmap.width - left).coerceAtLeast(1))
                val height = preview.clipHeight.coerceIn(1, srcBitmap.height.coerceAtLeast(1))
                Bitmap.createBitmap(srcBitmap, left, 0, width, height)
            } else {
                srcBitmap
            }
            try {
                thumbCache.edit(cacheKey) {
                    data.write {
                        cropped.compress(Bitmap.CompressFormat.JPEG, 92, asOutputStream())
                    }
                }
            } finally {
                if (cropped !== srcBitmap) cropped.recycle()
            }
        }

        thumbCache.read(cacheKey) {
            val fileReq = req.newBuilder().data(data.toUri()).build()
            chain.withRequest(fileReq).proceed()
        }?.let { result ->
            if (result is SuccessResult) return result.copy(request = req, dataSource = DataSource.NETWORK)
        }
        return ErrorResult(req.error(), req, IllegalStateException("Failed to read cached chapter cover"))
    }
}
