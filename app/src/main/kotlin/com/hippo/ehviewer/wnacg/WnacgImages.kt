package com.hippo.ehviewer.wnacg

import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.moveTo
import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.chapters.Wnacg
import com.hippo.ehviewer.spider.timeoutBySpeed
import com.hippo.ehviewer.util.copyTo
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import okio.Path

// Fork: the images of an album of 紳士漫畫, for its reader and its download, see WnacgPageLoader and WnacgDownload

/** The extension of the image at [url] ("jpg"...), "jpg" when it tells none. */
fun wnacgImageExtension(url: String) = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "jpg")
    .lowercase().takeIf { it.length in 3..4 } ?: "jpg"

/**
 * Saves the image at [url] of album [aid] as [target], through a file next to it, telling [onProgress] the bytes in
 * all (Long.MAX_VALUE when unknown), done and just read.
 */
suspend fun wnacgSaveImage(aid: Long, url: String, target: Path, onProgress: suspend (Long, Long, Int) -> Unit = { _, _, _ -> }) {
    val temp = target.parent!! / "${target.name}.part"
    try {
        timeoutBySpeed(
            url,
            { builder ->
                ktorClient.prepareGet(url) {
                    builder()
                    header("Referer", Wnacg.albumUrl(aid))
                }
            },
            onProgress,
            { response -> response.bodyAsChannel().copyTo(temp) },
        )
        if (target.exists()) target.delete()
        temp moveTo target
    } finally {
        if (temp.exists()) temp.delete()
    }
}
