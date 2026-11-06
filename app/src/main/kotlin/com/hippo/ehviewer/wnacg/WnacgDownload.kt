package com.hippo.ehviewer.wnacg

import arrow.fx.coroutines.parMap
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.list
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.moveTo
import com.ehviewer.core.files.read
import com.ehviewer.core.files.write
import com.hippo.ehviewer.chapters.Wnacg
import com.hippo.ehviewer.download.downloadDir
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.io.asOutputStream
import kotlinx.io.readByteArray

/**
 * Fork: the download of an album of 紳士漫畫: its images, saved one by one in a folder "<gid>.wnacg" (kept when stopped,
 * to go on from there), then put in "<gid>.zip" in the download folder. The download list then counts it as downloaded
 * and the reader reads the archive (DownloadManager.archiveFile).
 */
object WnacgDownload {
    private const val CONCURRENCY = 3

    /**
     * Downloads [info], telling [onPages] its page count once known, [onPage] each image saved (index, done in all)
     * and [onProgress] the bytes of an image (index, in all, done, just read).
     */
    suspend fun download(
        info: DownloadInfo,
        onPages: suspend (Int) -> Unit,
        onPage: suspend (index: Int, done: Int) -> Unit,
        onProgress: suspend (index: Int, total: Long, done: Long, read: Int) -> Unit,
    ) {
        val aid = info.wnacgAid
        val urls = Wnacg.images(aid)
        onPages(urls.size)
        val dir = checkNotNull(info.downloadDir) { "No download folder" }
        dir.mkdirs()
        val images = dir / "${info.gid}.wnacg"
        images.mkdirs()
        val names = urls.mapIndexed { index, url -> "%04d.%s".format(index + 1, wnacgImageExtension(url)) }
        val done = AtomicInteger(names.count { (images / it).exists() })
        urls.indices.filterNot { (images / names[it]).exists() }.parMap(concurrency = CONCURRENCY) { index ->
            wnacgSaveImage(aid, urls[index], images / names[index]) { total, received, read -> onProgress(index, total, received, read) }
            onPage(index, done.incrementAndGet())
        }
        val temp = dir / "${info.gid}.zip.part"
        try {
            temp.write {
                ZipOutputStream(asOutputStream()).use { zip ->
                    // Images do not shrink: stored as they are, only framed
                    zip.setLevel(Deflater.NO_COMPRESSION)
                    names.forEach { name ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write((images / name).read { readByteArray() })
                        zip.closeEntry()
                    }
                }
            }
            temp moveTo (dir / "${info.gid}.zip")
        } finally {
            if (temp.exists()) temp.delete()
        }
        images.list().forEach { it.delete() }
        images.delete()
    }
}
