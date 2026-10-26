package com.hippo.ehviewer.restore

import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.moveTo
import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.download.downloadDir
import com.hippo.ehviewer.spider.timeoutBySpeed
import com.hippo.ehviewer.util.copyTo
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel

/**
 * Fork: the download of a gallery restored from PandaChika: its archive, saved as "<gid>.zip" in the download folder.
 * The download list then counts it as downloaded and the reader reads the archive (DownloadManager.archiveFile).
 */
object PandaChaikaDownload {
    /** Downloads the archive of [info], telling [onProgress] the bytes in all (Long.MAX_VALUE when unknown), done, read. */
    suspend fun download(info: DownloadInfo, onProgress: suspend (total: Long, done: Long, read: Int) -> Unit) {
        val gallery = RestoredGalleries[info.gid] ?: PandaChaikaApi.gallery(info.gid)
        val url = checkNotNull(gallery?.archiveUrl) { "PandaChika has no archive of ${info.gid}" }
        val dir = checkNotNull(info.downloadDir) { "No download folder" }
        dir.mkdirs()
        val temp = dir / "${info.gid}.zip.part"
        val target = dir / "${info.gid}.zip"
        try {
            timeoutBySpeed(
                url,
                { builder -> ktorClient.prepareGet(url) { builder() } },
                onProgress,
                { response -> response.bodyAsChannel().copyTo(temp) },
            )
            temp moveTo target
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
