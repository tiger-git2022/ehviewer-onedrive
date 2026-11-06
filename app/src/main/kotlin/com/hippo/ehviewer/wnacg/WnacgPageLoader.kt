package com.hippo.ehviewer.wnacg

import arrow.autoCloseScope
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.chapters.Wnacg
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.util.displayString
import com.hippo.ehviewer.util.hasAds
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.tarsin.kt.install
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

/**
 * Fork: reads album [info] of 紳士漫畫 online, see WnacgGalleries.kt: its images, listed by the site, are saved one by
 * one in the cache as they are asked for or fetched ahead, and cleared when the reader closes.
 */
suspend inline fun <T> useWnacgPageLoader(
    info: GalleryInfo,
    startPage: Int,
    crossinline block: suspend (PageLoader) -> T,
) = autoCloseScope {
    coroutineScope {
        val aid = info.wnacgAid
        val urls = Wnacg.images(aid)
        val dir = install(
            { (appCtx.cacheDir.toOkioPath() / "wnacg" / "$aid").apply { mkdirs() } },
            { dir, _ -> runCatching { dir.delete() } },
        )
        val loader = install(WnacgPageLoader(this, info, startPage, aid, urls, dir))
        block(loader)
    }
}

class WnacgPageLoader(
    scope: kotlinx.coroutines.CoroutineScope,
    private val galleryInfo: GalleryInfo,
    startPage: Int,
    private val aid: Long,
    private val urls: List<String>,
    private val dir: Path,
) : PageLoader(scope, galleryInfo, startPage, urls.size, galleryInfo.hasAds) {
    private val fetches = ConcurrentHashMap<Int, Job>()

    // Pages asked for while being fetched: shown once fetched
    private val wanted = ConcurrentHashMap.newKeySet<Int>()
    private val semaphore = Semaphore(3)

    override val title by lazy { EhUtils.getSuitableTitle(galleryInfo) }

    private fun fileOf(index: Int) = dir / "${index + 1}.${wnacgImageExtension(urls[index])}"

    override fun getImageExtension(index: Int) = wnacgImageExtension(urls[index])

    override fun save(index: Int, file: Path) = runCatching {
        val source = fileOf(index)
        source.exists() && run {
            source sendTo file
            true
        }
    }.getOrElse {
        logcat(it)
        false
    }

    override fun openSource(index: Int) = object : PathSource {
        override val source = fileOf(index)
        override val type = getImageExtension(index)
        override fun close() = Unit
    }

    override fun prefetchPages(pages: List<Int>, bounds: IntRange) = pages.forEach { fetch(it, force = false) }

    override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
        wanted += index
        fetch(index, force)
    }

    private fun fetch(index: Int, force: Boolean) {
        if (index !in urls.indices) return
        val file = fileOf(index)
        if (!force && file.exists()) {
            if (wanted.remove(index)) notifySourceReady(index)
            return
        }
        if (fetches[index]?.isActive == true) {
            if (!force) return
            fetches[index]?.cancel()
        }
        fetches[index] = scope.launch(Dispatchers.IO) {
            try {
                semaphore.withPermit {
                    wnacgSaveImage(aid, urls[index], file) { total, done, _ ->
                        if (index in wanted && total in 1 until Long.MAX_VALUE) notifyPagePercent(index, done.toFloat() / total)
                    }
                }
                if (wanted.remove(index)) notifySourceReady(index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(e)
                if (wanted.remove(index)) notifyPageFailed(index, e.displayString())
            }
        }
    }
}
