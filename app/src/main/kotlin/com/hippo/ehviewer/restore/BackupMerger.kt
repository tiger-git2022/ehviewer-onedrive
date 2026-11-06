package com.hippo.ehviewer.restore

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.client.parser.ParserUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.tarsin.coroutines.runSuspendCatching

// Fork: a search of E-Hentai also looks on other sites (PandaChika, 紳士漫畫), whose galleries go between those of
// E-Hentai by posted time, but not one E-Hentai listed too. See PandaChaikaMerger and wnacg/WnacgSearch.kt.

/** A gallery of another site, as a gallery of a list, posted at [posted] (ms). */
class BackupItem<T>(val source: T, val info: BaseGalleryInfo, val posted: Long)

/** A page of a search on another site: the galleries it lists, the posted times of all of them, and if more come. */
class BackupPage<T>(val items: List<BackupItem<T>>, val postedTimes: List<Long>, val hasNext: Boolean)

/**
 * Puts the galleries another site has for a search between the pages of E-Hentai. Pages of E-Hentai go newest first: a
 * page gets the galleries of the other site posted between its newest and its oldest (between the oldest of the page
 * before and its own oldest when appended), and the last page all those left. A gallery shows once, and not when
 * E-Hentai listed one of the same gid or title. One for each search: a refresh starts another. [sources] are the
 * searches on the other site, each fetched as far as needed, [maxPages] pages at most.
 *
 * [titleKeys] tells which titles are the same (the title as is by default), with the language of the gallery;
 * [moreTitles] can tell more titles of galleries of E-Hentai, asked only when a gallery of the other site would show.
 * [onError] is told when the other site failed: the page of E-Hentai then shows alone. [onEhPage] is told each page of
 * E-Hentai before the other site is asked for its galleries. [moreEhKeys] and [moreKeys] tell more keys of a gallery
 * listed and of one of the other site: one of the other site with a key of one listed, or of [hiddenKeys], does not
 * show. [samePages] tells if galleries of a same key and of these pages are the same (any are by default): one of a
 * title and pages E-Hentai listed does not show.
 */
open class BackupMerger<T>(
    private val accept: suspend (BaseGalleryInfo) -> Boolean,
    sources: List<suspend (page: Int) -> BackupPage<T>>,
    private val onFound: (Collection<T>) -> Unit,
    private val maxPages: Int,
    private val titleKeys: (title: String, language: String?) -> List<String> = { title, _ -> listOf(title.normalized()) },
    private val moreTitles: suspend (List<BaseGalleryInfo>) -> Map<Long, List<String>> = { emptyMap() },
    private val onError: (Throwable) -> Unit = {},
    private val onEhPage: suspend (List<BaseGalleryInfo>) -> Unit = {},
    private val moreEhKeys: (BaseGalleryInfo) -> List<String> = { emptyList() },
    private val moreKeys: (title: String) -> List<String> = { emptyList() },
    private val hiddenKeys: () -> Set<String> = { emptySet() },
    private val samePages: (Int, Int) -> Boolean = { _, _ -> true },
) {
    enum class Load { Refresh, Append, Prepend }

    private class Item<T>(val source: T, val info: BaseGalleryInfo, val posted: Long)

    private class Source<T>(val fetch: suspend (page: Int) -> BackupPage<T>) {
        var nextPage = 1
        var hasMore = true

        // Posted time (ms) of the oldest gallery fetched so far, shown or not
        var oldestFetched = Long.MAX_VALUE
    }

    private val sources = sources.map { Source(it) }
    private val mutex = Mutex()
    private val loaded = mutableListOf<Item<T>>()
    private val loadedGids = mutableSetOf<Long>()
    private val shown = mutableSetOf<Long>()
    private val ehGids = mutableSetOf<Long>()
    // The keys of the titles listed by E-Hentai, with the pages of the galleries of each
    private val ehTitles = mutableMapOf<String, MutableSet<Int>>()
    private val askedTitles = mutableSetOf<Long>()

    // The newest a page appended at a key may have, and the oldest a page prepended at a key
    private val upperOfKey = mutableMapOf<String, Long>()
    private val lowerOfKey = mutableMapOf<String, Long>()

    /** [page] of E-Hentai, loaded as [load] at [key], with the galleries of the other site for it in between. */
    suspend fun merge(load: Load, key: String?, ehPage: List<BaseGalleryInfo>, prev: String?, next: String?): List<BaseGalleryInfo> = mutex.withLock {
        // One already shown from the other site, listed by E-Hentai on a later page: once only, the list keys on the gid
        val page = if (shown.isEmpty()) ehPage else ehPage.filter { it.gid !in shown }
        runSuspendCatching { onEhPage(page) }.onFailure { logcat(TAG, it) }
        page.forEach { info ->
            ehGids += info.gid
            info.title?.let { addEhKeys(titleKeys(it, info.simpleLanguage), info.pages) }
            info.titleJpn?.let { addEhKeys(titleKeys(it, info.simpleLanguage), info.pages) }
            addEhKeys(moreEhKeys(info), info.pages)
        }
        val times = page.mapNotNull { it.postedMillis() }
        val upper = when {
            load == Load.Append -> key?.let(upperOfKey::get) ?: times.maxOrNull()
            prev == null -> Long.MAX_VALUE
            else -> times.maxOrNull()
        } ?: return page
        val lower = when {
            next == null -> Long.MIN_VALUE
            load == Load.Prepend -> key?.let(lowerOfKey::get) ?: times.minOrNull()
            else -> times.minOrNull()
        } ?: return page
        next?.let { upperOfKey[it] = lower }
        prev?.let { lowerOfKey[it] = upper }
        if (upper < lower) return page
        runSuspendCatching { loadUntil(lower) }.onFailure {
            logcat(TAG, it)
            onError(it)
            return page
        }
        val inRange = { item: Item<T> -> item.posted in lower..upper && item.info.gid !in shown && item.info.gid !in ehGids }
        if (loaded.none(inRange)) return page
        askMoreTitles(page)
        val hidden = hiddenKeys()
        val picked = loaded.filter { inRange(it) && !it.sameTitleAsEh(hidden) }
        if (picked.isEmpty()) return page
        picked.forEach { shown += it.info.gid }
        onFound(picked.map { it.source })
        interleave(page, picked)
    }

    private fun addEhKeys(keys: List<String>, pages: Int) = keys.forEach { ehTitles.getOrPut(it) { mutableSetOf() } += pages }

    /** The titles of the galleries of the other site fetched so far, shown or not. */
    fun fetchedTitles(): List<String> = fetched

    // A copy for [fetchedTitles], asked from other merges while this one adds to [loaded]
    @Volatile
    private var fetched: List<String> = emptyList()

    private suspend fun askMoreTitles(page: List<BaseGalleryInfo>) {
        val asked = page.filter { it.gid !in askedTitles }
        if (asked.isEmpty()) return
        askedTitles += asked.map { it.gid }
        val byGid = asked.associateBy { it.gid }
        runSuspendCatching { moreTitles(asked) }.onFailure { logcat(TAG, it) }.getOrNull()?.forEach { (gid, titles) ->
            val info = byGid[gid]
            titles.forEach { addEhKeys(titleKeys(it, info?.simpleLanguage), info?.pages ?: 0) }
        }
    }

    private fun Item<T>.sameTitleAsEh(hidden: Set<String>) = listOfNotNull(info.title, info.titleJpn).any { title ->
        val listed = { key: String -> ehTitles[key]?.any { samePages(it, info.pages) } == true }
        titleKeys(title, info.simpleLanguage).any(listed) || moreKeys(title).any { listed(it) || it in hidden }
    }

    private suspend fun loadUntil(lower: Long) = sources.forEach { source ->
        with(source) {
            while (hasMore && nextPage <= maxPages && oldestFetched >= lower) {
                val result = fetch(nextPage)
                nextPage++
                hasMore = result.hasNext && result.postedTimes.isNotEmpty()
                result.postedTimes.forEach { oldestFetched = minOf(oldestFetched, it) }
                result.items.forEach { item ->
                    // Listed by several searches: once
                    if (item.info.gid !in loadedGids && accept(item.info)) {
                        loadedGids += item.info.gid
                        loaded += Item(item.source, item.info, item.posted)
                        item.info.title?.let { fetched = fetched + it }
                    }
                }
            }
        }
    }

    private fun interleave(page: List<BaseGalleryInfo>, picked: List<Item<T>>): List<BaseGalleryInfo> {
        val pending = ArrayDeque(picked.sortedByDescending { it.posted })
        val merged = ArrayList<BaseGalleryInfo>(page.size + picked.size)
        page.forEach { info ->
            val posted = info.postedMillis()
            if (posted != null) {
                while (pending.isNotEmpty() && pending.first().posted > posted) merged += pending.removeFirst().info
            }
            merged += info
        }
        pending.forEach { merged += it.info }
        return merged
    }

    companion object {
        private const val TAG = "BackupMerger"
    }
}

/** A title in lower case, its spaces as one. */
fun String.normalized() = trim().replace(Regex("\\s+"), " ").lowercase()

private fun GalleryInfo.postedMillis() = posted?.let { runCatching { ParserUtils.parseDate(it) }.getOrNull() }
