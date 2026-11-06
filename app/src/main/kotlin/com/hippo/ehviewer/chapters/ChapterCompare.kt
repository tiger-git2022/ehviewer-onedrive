package com.hippo.ehviewer.chapters

import android.graphics.Bitmap
import coil3.BitmapImage
import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.model.V2GalleryPreview
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.FavListUrlBuilder
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.exception.GalleryUnavailableException
import com.hippo.ehviewer.ktbuilder.execute
import com.hippo.ehviewer.ktbuilder.imageRequest
import com.hippo.ehviewer.wnacg.isWnacg
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splitties.init.appCtx

/**
 * Fork: a favorite that could not be compared: gone from E-H with no other upload of it to compare, or not fetched.
 * [coverPage] (from 1) is the page of the book its cover looks like: only shown, the chapter does not take pages from
 * there, its length in the book is not known (the ads and the pages of the translators at its end).
 */
@Serializable
data class UncomparedChapter(val source: ChapterSource, val gone: Boolean, val coverPage: Int? = null)

/** Fork: the favorites found in a tankoubon of [pages] pages, see [ChapterCompare]. */
@Serializable
data class ChapterCompareResult(
    val gid: Long,
    val pages: Int,
    val blocks: List<ChapterBlock>,
    val uncompared: List<UncomparedChapter> = emptyList(),
    /** 紳士漫畫 asked for its check, so the favorites gone from E-H were not compared there. */
    val wnacgBlocked: Boolean = false,
    val time: Long = System.currentTimeMillis(),
) {
    val favoritedPages get() = blocks.filter { it.source != null }.sumOf { it.size }
    val favoritedCount get() = blocks.mapNotNull { it.source?.gid }.distinct().size
}

/** Fork: how far a compare is. The fraction counts the thumbnails, of the book and of each favorite. */
data class ChapterCompareProgress(
    val stage: Stage,
    val done: Int = 0,
    val total: Int = 0,
    /** The favorite compared now, from 1, of [count]; its title. */
    val index: Int = 0,
    val count: Int = 0,
    val title: String? = null,
    val overallDone: Int = 0,
    val overallTotal: Int = 0,
    /** The blocks found so far. */
    val blocks: List<ChapterBlock> = emptyList(),
) {
    enum class Stage { Finding, Book, Chapter }

    val fraction: Float? get() = if (overallTotal > 0) (overallDone.toFloat() / overallTotal).coerceIn(0f, 1f) else null
}

sealed interface ChapterCompareState {
    data class Running(val progress: ChapterCompareProgress) : ChapterCompareState
    data class Done(val result: ChapterCompareResult) : ChapterCompareState
    data class Failed(val message: String) : ChapterCompareState
}

/**
 * Fork: which pages of a tankoubon the user already has as favorites of single chapters. The favorites of the same
 * artist (all the cloud slots, searched on the site, and the local ones) are compared by their thumbnails with the
 * book, see [ChapterMatcher]. A favorite gone from E-H is compared by another upload of it still there, in any
 * language, or by its album on 紳士漫畫 ([Wnacg]); else the page its cover looks like is told.
 *
 * It runs on when the sheet is closed; the result is kept in a file per gallery.
 */
object ChapterCompare {
    private const val TAG = "ChapterCompare"
    private const val MAX_FAVORITE_PAGES = 10
    private const val MAX_CANDIDATES = 60
    private const val MAX_OTHER_UPLOADS = 3
    private const val PREVIEW_PAGE_GAP = 400L
    private const val MAX_WNACG_PAGES = 400
    private const val WNACG_REFERER = "https://www.wnacg.com/"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    private val resultDir get() = File(appCtx.filesDir, "chapter_compare")
    private val hashDir get() = File(appCtx.cacheDir, "chapter_hashes")
    private val jobs = HashMap<Long, Job>()

    private val mutableStates = MutableStateFlow<Map<Long, ChapterCompareState>>(emptyMap())
    val states: StateFlow<Map<Long, ChapterCompareState>> = mutableStates.asStateFlow()

    /** A tankoubon: those are compared. */
    fun isTankoubon(detail: GalleryDetail) = detail.tagGroups.any { group ->
        group.namespace == TagNamespace.Other && group.tags.any { it.text.equals("tankoubon", ignoreCase = true) }
    }

    /** The result kept for [gid], loaded into [states] once. */
    fun load(gid: Long) {
        if (gid in mutableStates.value) return
        val file = File(resultDir, "$gid.json")
        val result = runCatching { json.decodeFromString(ChapterCompareResult.serializer(), file.readText()) }.getOrNull() ?: return
        mutableStates.update { if (gid in it) it else it + (gid to ChapterCompareState.Done(result)) }
    }

    /** Compares [detail] with the favorites, again when [force]; nothing when it runs already. */
    fun start(detail: GalleryDetail, force: Boolean = false) {
        val gid = detail.gid
        synchronized(jobs) {
            if (jobs[gid]?.isActive == true) return
            if (!force && mutableStates.value[gid] is ChapterCompareState.Done) return
            set(gid, ChapterCompareState.Running(ChapterCompareProgress(ChapterCompareProgress.Stage.Finding)))
            jobs[gid] = scope.launch {
                try {
                    val result = compare(detail)
                    runCatching {
                        resultDir.mkdirs()
                        File(resultDir, "$gid.json").writeText(json.encodeToString(ChapterCompareResult.serializer(), result))
                    }.onFailure { logcat(TAG, it) }
                    set(gid, ChapterCompareState.Done(result))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat(TAG, e)
                    set(gid, ChapterCompareState.Failed(e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    private fun set(gid: Long, state: ChapterCompareState) = mutableStates.update { it + (gid to state) }

    private class Candidate(val info: GalleryInfo) {
        val source get() = ChapterSource(info.gid, info.token, info.title ?: info.titleJpn ?: info.gid.toString())
    }

    private suspend fun compare(detail: GalleryDetail): ChapterCompareResult {
        val gid = detail.gid
        val pages = detail.pages
        val candidates = findCandidates(detail)
        // Each favorite counts its pages; one of an unknown size counts as one preview page
        val chapterPages = candidates.sumOf { it.info.pages.takeIf { p -> p > 0 } ?: 20 }
        val overallTotal = pages + chapterPages
        var overallDone = 0
        val found = ArrayList<Pair<IntRange, ChapterSource>>()
        val uncompared = ArrayList<UncomparedChapter>()
        val wnacg = WnacgRun()
        fun report(progress: ChapterCompareProgress) = set(
            gid,
            ChapterCompareState.Running(progress.copy(overallDone = overallDone, overallTotal = overallTotal, blocks = if (found.isEmpty()) emptyList() else ChapterBlocks.build(pages, found))),
        )

        report(ChapterCompareProgress(ChapterCompareProgress.Stage.Book, 0, pages))
        val book = galleryHashes(detail.gid, detail.token, pages) { done ->
            overallDone = done
            report(ChapterCompareProgress(ChapterCompareProgress.Stage.Book, done, pages))
        }
        overallDone = pages
        candidates.forEachIndexed { i, candidate ->
            val info = candidate.info
            val size = info.pages.takeIf { it > 0 } ?: 20
            val base = overallDone
            fun step(done: Int, total: Int = size) {
                overallDone = base + done.coerceAtMost(size)
                report(ChapterCompareProgress(ChapterCompareProgress.Stage.Chapter, done, total, i + 1, candidates.size, candidate.source.title))
            }
            step(0)
            val source = candidate.source
            try {
                val chapter = galleryHashes(info.gid, info.token, info.pages) { step(it) }
                ChapterMatcher.locate(book, chapter).forEach { found += it to source }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GalleryUnavailableException) {
                compareGone(book, info, source, found, wnacg) { step(it) }?.let { uncompared += it }
            } catch (e: Throwable) {
                logcat(TAG, e)
                uncompared += UncomparedChapter(source, gone = false)
            }
            overallDone = base + size
        }
        val blocks = ChapterBlocks.build(pages, found)
        // A favorite that is the book itself in another edition is found too: as it should, the pages are seen
        return ChapterCompareResult(gid, pages, blocks, uncompared, wnacgBlocked = wnacg.blocked)
    }

    /**
     * Places [info], gone from E-H, by another upload of it still there: the same title in this or another language
     * (the Japanese raw, an English translation…). Null when there is none, with the page its cover looks like.
     */
    private suspend fun compareGone(
        book: List<LongArray?>,
        info: GalleryInfo,
        source: ChapterSource,
        found: MutableList<Pair<IntRange, ChapterSource>>,
        wnacg: WnacgRun,
        onPages: (Int) -> Unit,
    ): UncomparedChapter? {
        for (other in otherUploads(info)) {
            val ranges = runCatching { ChapterMatcher.locate(book, galleryHashes(other.gid, other.token, other.pages, onPages)) }
                .onFailure { logcat(TAG, it) }.getOrNull()
            if (!ranges.isNullOrEmpty()) {
                ranges.forEach { found += it to source }
                return null
            }
        }
        // Its album on 紳士漫畫, where many Chinese translations gone from E-H still are
        if (!wnacg.blocked) {
            try {
                for (album in wnacgAlbums(info)) {
                    val ranges = try {
                        ChapterMatcher.locate(book, wnacgHashes(album, onPages))
                    } catch (e: Wnacg.BlockedException) {
                        throw e
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logcat(TAG, e)
                        null
                    }
                    if (!ranges.isNullOrEmpty()) {
                        ranges.forEach { found += it to source }
                        return null
                    }
                }
            } catch (_: Wnacg.BlockedException) {
                // Not asked again for the other favorites of this compare
                wnacg.blocked = true
            }
        }
        // Its cover, which E-H still has: where it looks like a page of the book, told but not taken
        val cover = runCatching { coverHash(info) }.onFailure { logcat(TAG, it) }.getOrNull()
        val page = cover?.let { ChapterMatcher.locateCover(book, it) }
        return UncomparedChapter(source, gone = true, coverPage = page?.plus(1))
    }

    /** Whether 紳士漫畫 asked for its check during a compare. */
    private class WnacgRun {
        var blocked = false
    }

    /** Other galleries on E-H of the title of [info], the language and the translators left out; a few at most. */
    private suspend fun otherUploads(info: GalleryInfo): List<GalleryInfo> {
        val titles = listOfNotNull(info.title, info.titleJpn).map(ChapterTitles::core).filter { it.isNotBlank() }.distinct()
        val found = LinkedHashMap<Long, GalleryInfo>()
        titles.forEach { title ->
            runCatching {
                EhEngine.getGalleryList(ListUrlBuilder(keyword = title).build()).galleryInfoList.forEach { other ->
                    val same = listOfNotNull(other.title, other.titleJpn).any { ChapterTitles.core(it) in titles }
                    if (same && other.gid != info.gid) found.putIfAbsent(other.gid, other)
                }
            }.onFailure { logcat(TAG, it) }
        }
        return found.values.take(MAX_OTHER_UPLOADS)
    }

    /** Albums on 紳士漫畫 of the title of [info], the language and the translators left out; a few at most. */
    private suspend fun wnacgAlbums(info: GalleryInfo): List<Wnacg.Album> {
        val titles = listOfNotNull(info.titleJpn, info.title).map(ChapterTitles::core).filter { it.isNotBlank() }.distinct()
        val found = LinkedHashMap<Long, Wnacg.Album>()
        titles.forEach { title ->
            try {
                Wnacg.search(title).filter { ChapterTitles.core(it.title) in titles }.forEach { found.putIfAbsent(it.aid, it) }
            } catch (e: Wnacg.BlockedException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(TAG, e)
            }
        }
        return found.values.take(MAX_OTHER_UPLOADS)
    }

    /** The hashes of the thumbnails of [album], by page from 0. */
    private suspend fun wnacgHashes(album: Wnacg.Album, onPages: (Int) -> Unit): List<LongArray?> {
        val pages = album.pages.coerceAtMost(MAX_WNACG_PAGES)
        val hashes = ArrayList<LongArray?>()
        var index = 1
        while (pages <= 0 || hashes.size < pages) {
            if (index > 1) delay(PREVIEW_PAGE_GAP)
            val thumbnails = Wnacg.thumbnails(album.aid, index)
            if (thumbnails.isEmpty()) break
            thumbnails.forEach { (page, url) ->
                while (hashes.size < page) hashes += null
                hashes[page - 1] = try {
                    urlHash(url, WNACG_REFERER)
                } catch (e: Wnacg.BlockedException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat(TAG, e)
                    null
                }
            }
            onPages(hashes.size)
            index++
            if (pages <= 0 && index > MAX_WNACG_PAGES / 12) break
        }
        return hashes
    }

    private suspend fun urlHash(url: String, referer: String): LongArray? {
        val request = with(appCtx) {
            imageRequest {
                data(url)
                httpHeaders(NetworkHeaders.Builder().set("Referer", referer).build())
                allowHardware(false)
                memoryCachePolicy(CachePolicy.DISABLED)
            }
        }
        val result = request.execute()
        val error = (result as? ErrorResult)?.throwable
        if (error is HttpException && Wnacg.isBlocked(error.response.code)) throw Wnacg.BlockedException()
        val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap ?: return null
        return withContext(Dispatchers.Default) { hashOf(bitmap, 0, 0, bitmap.width, bitmap.height) }
    }

    /** The favorites of the artists (or groups) of [detail]: on the site and local. */
    private suspend fun findCandidates(detail: GalleryDetail): List<Candidate> {
        val names = names(detail)
        if (names.isEmpty()) return emptyList()
        val found = LinkedHashMap<Long, GalleryInfo>()
        if (Settings.hasSignedIn.value) {
            // One more than taken, the book itself may be among them
            fun full() = found.size > MAX_CANDIDATES
            names.forEach { name ->
                if (full()) return@forEach
                runCatching {
                    val builder = FavListUrlBuilder(FavListUrlBuilder.FAV_CAT_ALL, name)
                    repeat(MAX_FAVORITE_PAGES) {
                        val result = EhEngine.getFavorites(builder.build())
                        result.galleryInfoList.forEach { found.putIfAbsent(it.gid, it) }
                        if (full()) return@runCatching
                        val next = result.next ?: return@runCatching
                        builder.setIndex(next, true)
                    }
                }.onFailure { logcat(TAG, it) }
            }
        }
        runCatching {
            EhDB.getLocalFavoritesWithGallery().forEach { (_, info) ->
                if (names.any { info.matches(it) }) found.putIfAbsent(info.gid, info)
            }
        }.onFailure { logcat(TAG, it) }
        found.remove(detail.gid)
        // Not an album of 紳士漫畫 in the local favorites: it has no previews on E-Hentai
        found.keys.removeAll { isWnacg(it) }
        val list = found.values.take(MAX_CANDIDATES).map { BaseGalleryInfo(gid = it.gid, token = it.token, title = it.title, titleJpn = it.titleJpn, thumbKey = it.thumbKey, pages = it.pages) }
        // The page counts, and the covers of those gone, from the API
        val unknown = list.filter { it.pages <= 0 || it.thumbKey == null }
        if (unknown.isNotEmpty()) runCatching { EhEngine.fillGalleryListByApi(unknown, EhUrl.referer) }.onFailure { logcat(TAG, it) }
        return list.map(::Candidate)
    }

    private fun names(detail: GalleryDetail): List<String> {
        fun tags(namespace: TagNamespace) = detail.tagGroups.filter { it.namespace == namespace }.flatMap { group -> group.tags.map { it.text } }
        return tags(TagNamespace.Artist).ifEmpty { tags(TagNamespace.Group) }.ifEmpty {
            // No tag: the name in the first brackets of the title, "[Circle (Artist)]" taking the artist
            val bracket = Regex("""\[([^\]]+)\]""").find(detail.title.orEmpty())?.groupValues?.get(1)?.trim()
            val inner = bracket?.let { Regex("\\(([^)]+)\\)").find(it)?.groupValues?.get(1)?.trim() } ?: bracket
            listOfNotNull(inner?.takeIf { it.isNotEmpty() })
        }.distinct()
    }

    private fun GalleryInfo.matches(name: String): Boolean {
        val n = name.lowercase()
        return title?.lowercase()?.contains(n) == true || titleJpn?.lowercase()?.contains(n) == true ||
            simpleTags?.any { it.lowercase().substringAfter(':') == n } == true
    }

    /** The hashes of the thumbnails of a gallery, by page from 0, kept in the cache; [onPages] gets how many are done. */
    private suspend fun galleryHashes(gid: Long, token: String, pages: Int, onPages: (Int) -> Unit): List<LongArray?> {
        val file = File(hashDir, "$gid")
        readHashes(file)?.takeIf { pages <= 0 || it.size == pages }?.let {
            onPages(it.size)
            return it
        }
        val first = EhEngine.getPreviewList(EhUrl.getGalleryDetailUrl(gid, token))
        val total = first.total.takeIf { it > 0 } ?: pages
        // No preview on the first page: none to read on the next ones either
        if (first.previews.isEmpty()) return List(total.coerceAtLeast(0)) { null }
        val perPage = first.previews.size
        val hashes = arrayOfNulls<LongArray>(total)
        var done = 0
        val previewPages = (total + perPage - 1) / perPage
        for (index in 0 until previewPages) {
            val previews = if (index == 0) {
                first.previews
            } else {
                // Spaced as a reader turning the preview pages, not to be taken for a crawler
                delay(PREVIEW_PAGE_GAP)
                EhEngine.getPreviewList(EhUrl.getGalleryDetailUrl(gid, token, index)).previews
            }
            hashPreviews(previews).forEach { (position, hash) -> if (position in hashes.indices) hashes[position] = hash }
            done += previews.size
            onPages(done.coerceAtMost(total))
        }
        val list = hashes.toList()
        // Kept only whole: a preview that failed to load is fetched again by the next compare
        if (list.all { it != null }) {
            runCatching {
                hashDir.mkdirs()
                file.writeText(list.joinToString("\n") { ThumbHash.toHex(it!!) })
            }.onFailure { logcat(TAG, it) }
        }
        return list
    }

    private fun readHashes(file: File): List<LongArray?>? = runCatching {
        if (!file.exists()) return null
        file.readLines().map { line -> ThumbHash.fromHex(line) ?: return null }
    }.getOrNull()

    /** The hash of each preview, by its page; a sheet of several previews is fetched once. */
    private suspend fun hashPreviews(previews: List<GalleryPreview>): List<Pair<Int, LongArray?>> = previews.groupBy { it.url }.flatMap { (_, group) ->
        val bitmap = runCatching { loadBitmap(group.first()) }.onFailure { logcat(TAG, it) }.getOrNull()
        group.map { preview ->
            val hash = bitmap?.let {
                withContext(Dispatchers.Default) {
                    if (preview is V2GalleryPreview) hashOf(it, preview.offsetX, 0, preview.clipWidth, preview.clipHeight) else hashOf(it, 0, 0, it.width, it.height)
                }
            }
            preview.position to hash
        }
    }

    private suspend fun loadBitmap(preview: GalleryPreview): Bitmap? {
        val request = with(appCtx) {
            imageRequest(preview) {
                allowHardware(false)
                memoryCachePolicy(CachePolicy.DISABLED)
            }
        }
        return ((request.execute() as? SuccessResult)?.image as? BitmapImage)?.bitmap
    }

    private suspend fun coverHash(info: GalleryInfo): LongArray? {
        info.thumbKey ?: return null
        val request = with(appCtx) {
            imageRequest(info) {
                allowHardware(false)
                memoryCachePolicy(CachePolicy.DISABLED)
            }
        }
        val result = request.execute()
        val error = (result as? ErrorResult)?.throwable
        if (error is HttpException && Wnacg.isBlocked(error.response.code)) throw Wnacg.BlockedException()
        val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap ?: return null
        return withContext(Dispatchers.Default) { hashOf(bitmap, 0, 0, bitmap.width, bitmap.height) }
    }

    private fun hashOf(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): LongArray? {
        val left = x.coerceIn(0, bitmap.width)
        val top = y.coerceIn(0, bitmap.height)
        val w = width.coerceAtMost(bitmap.width - left)
        val h = height.coerceAtMost(bitmap.height - top)
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, left, top, w, h)
        return ThumbHash.of(pixels, w, h)
    }
}
