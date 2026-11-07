package com.hippo.ehviewer.chapters

import android.graphics.Bitmap
import coil3.BitmapImage
import coil3.Image
import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.ehviewer.core.i18n.R
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
import com.hippo.ehviewer.coil.BitmapImageWithExtraInfo
import com.hippo.ehviewer.ktbuilder.execute
import com.hippo.ehviewer.ktbuilder.imageRequest
import com.hippo.ehviewer.wnacg.isWnacg
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.tarsin.coroutines.runSuspendCatching
import splitties.init.appCtx

/**
 * Fork: a favorite that could not be compared: gone from E-H with no other upload of it to compare, or not fetched.
 * [coverPage] (from 1) is the page of the book its cover looks like: only shown, the chapter does not take pages from
 * there, its length in the book is not known (the ads and the pages of the translators at its end).
 */
@Serializable
data class UncomparedChapter(val source: ChapterSource, val gone: Boolean, val coverPage: Int? = null)

/**
 * Fork: a favorite of the artist as long as most of the book or longer: not compared, likely the book itself in another
 * edition or another book, only listed so that it can be opened.
 */
@Serializable
data class WholeFavorite(val source: ChapterSource, val pages: Int, val thumbKey: String? = null)

/** Fork: the favorites found in a tankoubon of [pages] pages, see [ChapterCompare]. */
@Serializable
data class ChapterCompareResult(
    val gid: Long,
    val pages: Int,
    val blocks: List<ChapterBlock>,
    val uncompared: List<UncomparedChapter> = emptyList(),
    /** 紳士漫畫 asked for its check, so the favorites gone from E-H were not compared there. */
    val wnacgBlocked: Boolean = false,
    /** The favorites of the artist compared. */
    val compared: Int = 0,
    /** Those not compared as they are as long as most of the book, the longest first. */
    val whole: List<WholeFavorite> = emptyList(),
    val time: Long = System.currentTimeMillis(),
    /** 0 for the results kept before there was one. */
    val version: Int = 0,
) {
    companion object {
        /** Raised when the results kept are wrong: those of a lower one are compared again. */
        // 1: the thumbnails read at last (v1.15.0-t123); 2: the covers of the favorites kept (v1.15.0-t124);
        // 3: the favorites over the same pages all kept (v1.15.0-t125)
        const val VERSION = 3
    }

    val favoritedPages get() = ChapterBlocks.favoritedPages(pages, blocks)
    val favoritedCount get() = blocks.flatMap { it.sources }.map { it.gid }.distinct().size
}

/** Fork: how far a compare is. The fraction counts the thumbnails, of the book and of each favorite. */
data class ChapterCompareProgress(
    val stage: Stage,
    /** The favorites compared already, of [count]. */
    val finished: Int = 0,
    val count: Int = 0,
    /** The galleries whose thumbnails are read now (the book first, while it is), a few at once. */
    val active: List<Active> = emptyList(),
    val overallDone: Int = 0,
    val overallTotal: Int = 0,
    /** The blocks found so far. */
    val blocks: List<ChapterBlock> = emptyList(),
) {
    enum class Stage { Finding, Comparing }

    /** A gallery read now: [book] for the tankoubon itself, [done] of its [total] thumbnails. */
    data class Active(val title: String, val done: Int, val total: Int, val book: Boolean = false)

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
    private const val MAX_FAVORITE_PAGES = 20
    private const val MAX_CANDIDATES = 200

    /** The share of the book a favorite reaches at most to be taken as one of its chapters. */
    private const val CHAPTER_MAX_SHARE = 0.8f
    private const val MAX_OTHER_UPLOADS = 3
    private const val PREVIEW_PAGE_GAP = 500L

    /** The favorites read at once. */
    private const val PARALLEL_GALLERIES = 5

    /** The preview pages fetched at once, of all the galleries: E-H bans an address loading pages too fast. */
    private val previewGate = Semaphore(5)
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
    // The previews of the books by page of previews ("gid/index"), to show the blocks no favorite is
    private val previewPages = ConcurrentHashMap<String, List<GalleryPreview>>()
    private val previewMutex = Mutex()

    /** The preview of [page] (from 1) of the book [detail]; null when it cannot be fetched. */
    suspend fun previewOf(detail: GalleryDetail, page: Int): GalleryPreview? {
        val perPage = detail.previewList.size.takeIf { it > 0 } ?: return null
        val index = (page - 1) / perPage
        val previews = if (index == 0) {
            detail.previewList
        } else {
            val key = "${detail.gid}/$index"
            previewPages[key] ?: previewMutex.withLock {
                previewPages[key] ?: runSuspendCatching {
                    EhEngine.getPreviewList(EhUrl.getGalleryDetailUrl(detail.gid, detail.token, index)).previews
                }.onFailure { logcat(TAG, it) }.getOrNull()?.also { previewPages[key] = it }
            } ?: return null
        }
        return previews.firstOrNull { it.position == page - 1 }
    }

    fun isTankoubon(detail: GalleryDetail) = detail.tagGroups.any { group ->
        group.namespace == TagNamespace.Other && group.tags.any { it.text.equals("tankoubon", ignoreCase = true) }
    }

    /** The result kept for [gid], loaded into [states] once. */
    fun load(gid: Long) {
        if (gid in mutableStates.value) return
        val file = File(resultDir, "$gid.json")
        val result = runCatching { json.decodeFromString(ChapterCompareResult.serializer(), file.readText()) }.getOrNull() ?: return
        // Kept by an older version (before v1.15.0-t123 no thumbnail was read): compared again
        if (result.version < ChapterCompareResult.VERSION) return
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
        val source get() = ChapterSource(info.gid, info.token, info.title ?: info.titleJpn ?: info.gid.toString(), info.thumbKey)
    }

    private suspend fun compare(detail: GalleryDetail): ChapterCompareResult = coroutineScope {
        val gid = detail.gid
        val pages = detail.pages
        val (candidates, whole) = findCandidates(detail)
        // Each favorite counts its pages; one of an unknown size counts as one preview page
        fun sizeOf(info: GalleryInfo) = info.pages.takeIf { it > 0 } ?: 20
        val overallTotal = pages + candidates.sumOf { sizeOf(it.info) }
        val lock = Any()
        val found = ArrayList<Pair<IntRange, ChapterSource>>()
        val uncompared = ArrayList<UncomparedChapter>()
        val wnacg = WnacgRun()
        // By gallery: how many of its thumbnails are read (the book under its own gid too)
        val doneBy = LinkedHashMap<Long, Int>()
        val active = LinkedHashMap<Long, ChapterCompareProgress.Active>()
        var finished = 0
        fun report() = synchronized(lock) {
            val progress = ChapterCompareProgress(
                stage = ChapterCompareProgress.Stage.Comparing,
                finished = finished,
                count = candidates.size,
                active = active.values.toList(),
                overallDone = doneBy.values.sum(),
                overallTotal = overallTotal,
                blocks = if (found.isEmpty()) emptyList() else ChapterBlocks.build(pages, found),
            )
            set(gid, ChapterCompareState.Running(progress))
        }
        fun step(key: Long, title: String, done: Int, total: Int, book: Boolean = false) {
            synchronized(lock) {
                doneBy[key] = done.coerceAtMost(total)
                active[key] = ChapterCompareProgress.Active(title, done.coerceAtMost(total), total, book)
            }
            report()
        }

        // The book and the favorites are read at once, a few galleries at a time; each is placed once the book is read
        val bookTitle = detail.title ?: detail.titleJpn ?: gid.toString()
        val book = async {
            galleryHashes(gid, detail.token, pages) { step(gid, bookTitle, it, pages, book = true) }.also {
                synchronized(lock) {
                    doneBy[gid] = pages
                    active.remove(gid)
                }
                report()
            }
        }
        val galleries = Semaphore(PARALLEL_GALLERIES)
        candidates.map { candidate ->
            launch {
                galleries.withPermit {
                    val info = candidate.info
                    val size = sizeOf(info)
                    val source = candidate.source
                    // Keyed apart from the book, should the book be among the favorites
                    val key = -info.gid
                    step(key, source.title, 0, size)
                    try {
                        val chapter = galleryHashes(info.gid, info.token, info.pages) { step(key, source.title, it, size) }
                        // None of its thumbnails read: not compared, rather than not in the book
                        if (chapter.none { it != null }) throw IllegalStateException("No thumbnail of ${info.gid} read")
                        val ranges = ChapterMatcher.locate(book.await(), chapter)
                        synchronized(lock) { ranges.forEach { found += it to source } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: GalleryUnavailableException) {
                        val gone = ArrayList<Pair<IntRange, ChapterSource>>()
                        val left = compareGone(book.await(), info, source, gone, wnacg) { step(key, source.title, it, size) }
                        synchronized(lock) {
                            found += gone
                            left?.let { uncompared += it }
                        }
                    } catch (e: Throwable) {
                        logcat(TAG, e)
                        synchronized(lock) { uncompared += UncomparedChapter(source, gone = false) }
                    }
                    synchronized(lock) {
                        doneBy[key] = size
                        active.remove(key)
                        finished++
                    }
                    report()
                }
            }
        }.joinAll()
        if (book.await().none { it != null }) throw IllegalStateException(appCtx.getString(R.string.chapter_compare_no_thumbnails))
        val blocks = synchronized(lock) { ChapterBlocks.build(pages, found) }
        // A favorite that is the book itself in another edition is found too: as it should, the pages are seen
        ChapterCompareResult(gid, pages, blocks, synchronized(lock) { uncompared.sortedBy { it.source.title } }, wnacgBlocked = wnacg.blocked, compared = candidates.size, whole = whole, version = ChapterCompareResult.VERSION)
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
        @Volatile var blocked = false
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
        var perPage = 0
        while (pages <= 0 || hashes.size < pages) {
            if (index > 1) delay(PREVIEW_PAGE_GAP)
            val thumbnails = Wnacg.thumbnails(album.aid, index, perPage)
            if (thumbnails.isEmpty()) break
            if (index == 1) perPage = thumbnails.size
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
        val bitmap = softBitmap((result as? SuccessResult)?.image) ?: return null
        return withContext(Dispatchers.Default) { hashOf(bitmap, 0, 0, bitmap.width, bitmap.height) }
    }

    /** The favorites of the artists (or groups) of [detail], on the site and local: to compare, and those as long as most of it. */
    private suspend fun findCandidates(detail: GalleryDetail): Pair<List<Candidate>, List<WholeFavorite>> {
        val names = names(detail)
        if (names.isEmpty()) return emptyList<Candidate>() to emptyList()
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
        val list = found.values.map { BaseGalleryInfo(gid = it.gid, token = it.token, title = it.title, titleJpn = it.titleJpn, thumbKey = it.thumbKey, pages = it.pages) }
        // The page counts, and the covers of those gone, from the API
        val unknown = list.filter { it.pages <= 0 || it.thumbKey == null }
        if (unknown.isNotEmpty()) runCatching { EhEngine.fillGalleryListByApi(unknown, EhUrl.referer) }.onFailure { logcat(TAG, it) }
        // Not a chapter: as long as most of the book or longer, the book itself in another edition or another book
        val longest = detail.pages * CHAPTER_MAX_SHARE
        // The short ones first, as chapters are: kept when there are too many
        val (chapters, whole) = list.partition { detail.pages <= 0 || it.pages <= 0 || it.pages < longest }
        return chapters.sortedBy { it.pages.takeIf { pages -> pages > 0 } ?: Int.MAX_VALUE }.take(MAX_CANDIDATES).map(::Candidate) to
            whole.sortedByDescending { it.pages }.map { WholeFavorite(Candidate(it).source, it.pages, it.thumbKey) }
    }

    /**
     * The names to find the favorites by: the artist tags (or the group ones) and the name in the first brackets of
     * each title, "[Circle (Artist)]" taking the artist. Both: a tag is often romanized ("igumox") while the titles of
     * the favorites, searched on the site, have the name as written ("井雲くす").
     */
    private fun names(detail: GalleryDetail): List<String> {
        fun tags(namespace: TagNamespace) = detail.tagGroups.filter { it.namespace == namespace }.flatMap { group -> group.tags.map { it.text } }
        val brackets = listOfNotNull(detail.title, detail.titleJpn).mapNotNull { title ->
            val bracket = Regex("""\[([^\]]+)\]""").find(title)?.groupValues?.get(1)?.trim()
            val inner = bracket?.let { Regex("\\(([^)]+)\\)").find(it)?.groupValues?.get(1)?.trim() } ?: bracket
            inner?.takeIf { it.isNotEmpty() }
        }
        return (tags(TagNamespace.Artist).ifEmpty { tags(TagNamespace.Group) } + brackets).distinctBy { it.lowercase() }
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
        // Each fetch spaced as a reader turning pages, not to be taken for a crawler
        val first = previewGate.withPermit { EhEngine.getPreviewList(EhUrl.getGalleryDetailUrl(gid, token)).also { delay(PREVIEW_PAGE_GAP) } }
        val total = first.total.takeIf { it > 0 } ?: pages
        // No preview on the first page: none to read on the next ones either
        if (first.previews.isEmpty()) return List(total.coerceAtLeast(0)) { null }
        val perPage = first.previews.size
        val hashes = arrayOfNulls<LongArray>(total)
        val done = AtomicInteger(0)
        suspend fun read(previews: List<GalleryPreview>) {
            hashPreviews(previews).forEach { (position, hash) -> if (position in hashes.indices) hashes[position] = hash }
            onPages(done.addAndGet(previews.size).coerceAtMost(total))
        }
        read(first.previews)
        val previewPages = (total + perPage - 1) / perPage
        coroutineScope {
            (1 until previewPages).map { index ->
                launch {
                    val previews = previewGate.withPermit {
                        EhEngine.getPreviewList(EhUrl.getGalleryDetailUrl(gid, token, index)).previews.also { delay(PREVIEW_PAGE_GAP) }
                    }
                    read(previews)
                }
            }.joinAll()
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
        return softBitmap((request.execute() as? SuccessResult)?.image)
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
        val bitmap = softBitmap((result as? SuccessResult)?.image) ?: return null
        return withContext(Dispatchers.Default) { hashOf(bitmap, 0, 0, bitmap.width, bitmap.height) }
    }

    /**
     * The pixels of a loaded image. Asked without hardware bitmaps, the loader of the app still wraps the bitmap
     * ([BitmapImageWithExtraInfo]) and copies it to the GPU (HardwareBitmapInterceptor), where its pixels cannot be read:
     * taken as a [BitmapImage] it was null, so every thumbnail had no hash and no favorite was ever found.
     */
    private fun softBitmap(image: Image?): Bitmap? {
        val bitmap = when (image) {
            is BitmapImageWithExtraInfo -> image.image.bitmap
            is BitmapImage -> image.bitmap
            else -> null
        } ?: return null
        return if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
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
