package com.hippo.ehviewer.restore

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhFilter
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.parser.ParserUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.tarsin.coroutines.runSuspendCatching

// Fork: a search of E-Hentai also looks on PandaChika, which keeps galleries E-Hentai removed: E-Hentai never lists
// those. Its galleries go between those of E-Hentai by posted time, but not one E-Hentai listed too (same gid or same
// title). See PandaChaikaMerger.

/** A search of E-Hentai as PandaChika can do it: words of the title, tags, an uploader. */
data class PandaChaikaQuery(val title: String?, val tags: List<String>, val uploader: String?) {
    suspend fun search(page: Int) = PandaChaikaApi.search(title, tags, uploader, page)

    companion object {
        private val NAMESPACES = mapOf(
            "a" to "artist", "artist" to "artist",
            "c" to "character", "char" to "character", "character" to "character",
            "cos" to "cosplayer", "cosplayer" to "cosplayer",
            "f" to "female", "female" to "female",
            "g" to "group", "circle" to "group", "group" to "group",
            "l" to "language", "lang" to "language", "language" to "language",
            "loc" to "location", "location" to "location",
            "m" to "male", "male" to "male",
            "x" to "mixed", "mixed" to "mixed",
            "o" to "other", "other" to "other",
            "p" to "parody", "series" to "parody", "parody" to "parody",
            "r" to "reclass", "reclass" to "reclass",
            "temp" to "temp",
        )

        // Searches PandaChika cannot do at all
        private val UNSUPPORTED = setOf("gid", "torrent", "favnote", "comment", "tag", "uploaduid", "md5", "sha1")

        // A word: -/~ before it, a namespace, then a "quoted" or a bare value
        private val TOKEN = Regex("""([-~])?(?:([A-Za-z]+):)?(?:"([^"]*)"?|(\S+))""")

        /** The words of an E-Hentai search ("f_search"), null when PandaChika cannot search it. */
        fun fromKeyword(keyword: String): PandaChaikaQuery? {
            val words = mutableListOf<String>()
            val tags = mutableListOf<String>()
            var uploader: String? = null
            for (match in TOKEN.findAll(keyword)) {
                val (prefix, ns, quoted, bare) = match.destructured
                val raw = quoted.ifEmpty { bare }
                val exact = raw.endsWith("$")
                val value = raw.trim('$', '*', ' ').trim()
                if (value.isEmpty()) continue
                // Either of several: PandaChika has no "or"
                if (prefix == "~") continue
                val negated = prefix == "-"
                val namespace = ns.lowercase()
                when {
                    namespace in UNSUPPORTED -> return null
                    namespace == "uploader" -> if (!negated) uploader = value
                    namespace in NAMESPACES -> if (',' !in value) tags += "${if (negated) "-" else ""}${NAMESPACES[namespace]}:$value"
                    namespace.isNotEmpty() -> if (!negated) words += "$ns:$value"
                    exact -> if (',' !in value) tags += "${if (negated) "-" else ""}$value"
                    !negated -> words += value
                }
            }
            val title = words.joinToString(" ").ifBlank { null }
            // Only tags left out: about everything
            if (title == null && uploader == null && tags.none { !it.startsWith("-") }) return null
            return PandaChaikaQuery(title, tags, uploader)
        }

        /** The search of [lub], null when PandaChika cannot do it (the front page, the popular, an image...). */
        fun of(lub: ListUrlBuilder): PandaChaikaQuery? {
            val keyword = lub.keyword?.trim()?.ifEmpty { null } ?: return null
            val language = lub.language.takeIf { it in GalleryInfo.S_LANG_TAGS.indices }?.let { GalleryInfo.S_LANG_TAGS[it] }
            val query = when (lub.mode) {
                ListUrlBuilder.MODE_NORMAL -> fromKeyword(keyword)
                ListUrlBuilder.MODE_TAG -> {
                    val ns = keyword.substringBefore(':', "")
                    val value = keyword.substringAfter(':')
                    PandaChaikaQuery(null, listOf(if (ns.isEmpty()) value else "${NAMESPACES[ns] ?: ns}:$value"), null)
                }
                ListUrlBuilder.MODE_UPLOADER -> PandaChaikaQuery(null, emptyList(), keyword)
                else -> null
            } ?: return null
            val hasLanguage = query.tags.any { it.startsWith("language:") }
            return if (language != null && !hasLanguage && "l:" !in keyword && "language:" !in keyword) {
                query.copy(tags = query.tags + language)
            } else {
                query
            }
        }
    }
}

/**
 * Puts the galleries PandaChika has for a search between the pages of E-Hentai. Pages of E-Hentai go newest first: a
 * page gets the galleries of PandaChika posted between its newest and its oldest (between the oldest of the page before
 * and its own oldest when appended), and the last page all those left. A gallery shows once, and not when E-Hentai
 * listed one of the same gid or title. One for each search: a refresh starts another.
 */
class PandaChaikaMerger(
    private val query: PandaChaikaQuery,
    private val accept: suspend (BaseGalleryInfo) -> Boolean,
    private val fetch: suspend (page: Int) -> PandaChaikaApi.GalleryPage = query::search,
    private val onFound: (Collection<PandaChaikaApi.Gallery>) -> Unit = RestoredGalleries::found,
) {
    enum class Load { Refresh, Append, Prepend }

    private class Item(val gallery: PandaChaikaApi.Gallery, val info: BaseGalleryInfo, val posted: Long)

    private val mutex = Mutex()
    private val loaded = mutableListOf<Item>()
    private var nextPage = 1
    private var hasMore = true

    // Posted time (ms) of the oldest gallery fetched so far, shown or not
    private var oldestFetched = Long.MAX_VALUE
    private val shown = mutableSetOf<Long>()
    private val ehGids = mutableSetOf<Long>()
    private val ehTitles = mutableSetOf<String>()

    // The newest a page appended at a key may have, and the oldest a page prepended at a key
    private val upperOfKey = mutableMapOf<String, Long>()
    private val lowerOfKey = mutableMapOf<String, Long>()

    /** [page] of E-Hentai, loaded as [load] at [key], with the galleries of PandaChika for it in between. */
    suspend fun merge(load: Load, key: String?, ehPage: List<BaseGalleryInfo>, prev: String?, next: String?): List<BaseGalleryInfo> = mutex.withLock {
        // One already shown from PandaChika, listed by E-Hentai on a later page: once only, the list keys on the gid
        val page = if (shown.isEmpty()) ehPage else ehPage.filter { it.gid !in shown }
        page.forEach { info ->
            ehGids += info.gid
            info.title?.let { ehTitles += it.normalized() }
            info.titleJpn?.let { ehTitles += it.normalized() }
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
            return page
        }
        val picked = loaded.filter { item ->
            item.posted in lower..upper && item.info.gid !in shown && item.info.gid !in ehGids && !item.sameTitleAsEh()
        }
        if (picked.isEmpty()) return page
        picked.forEach { shown += it.info.gid }
        onFound(picked.map { it.gallery })
        interleave(page, picked)
    }

    private fun Item.sameTitleAsEh() = listOfNotNull(gallery.title, gallery.titleJpn).any { it.normalized() in ehTitles }

    private suspend fun loadUntil(lower: Long) {
        while (hasMore && nextPage <= MAX_PAGES && oldestFetched >= lower) {
            val result = fetch(nextPage)
            nextPage++
            hasMore = result.hasNext && result.galleries.isNotEmpty()
            result.galleries.forEach { gallery ->
                val posted = gallery.posted * 1000
                oldestFetched = minOf(oldestFetched, posted)
                val info = gallery.toGalleryInfo() ?: return@forEach
                if (accept(info)) loaded += Item(gallery, info, posted)
            }
        }
    }

    private fun interleave(page: List<BaseGalleryInfo>, picked: List<Item>): List<BaseGalleryInfo> {
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
        private const val TAG = "PandaChaikaMerger"

        /** Pages of 100 galleries fetched at most for a search. */
        const val MAX_PAGES = 5

        /** For the search of [lub] when on in the settings, with its categories, rating, pages and the filters. */
        fun of(lub: ListUrlBuilder, hideFavorited: () -> Boolean): PandaChaikaMerger? {
            if (!Settings.pandaChaikaInSearch.value) return null
            val query = PandaChaikaQuery.of(lub) ?: return null
            val signedIn = Settings.hasSignedIn.value
            val category = if (!signedIn && lub.category <= 0) EhUtils.NON_H else lub.category
            val normal = lub.mode == ListUrlBuilder.MODE_NORMAL
            val minRating = if (normal) lub.minRating else -1
            val pageFrom = if (normal) lub.pageFrom else -1
            val pageTo = if (normal) lub.pageTo else -1
            return PandaChaikaMerger(
                query,
                accept@{ info ->
                    if (category > 0 && normal && (info.category and category) == 0) return@accept false
                    if (minRating > 0 && info.rating < minRating) return@accept false
                    if (pageFrom > 0 && info.pages < pageFrom) return@accept false
                    if (pageTo > 0 && info.pages > pageTo) return@accept false
                    val filtered = with(EhFilter) { filterTitle(info) || filterUploader(info) || filterTag(info) || filterTagNamespace(info) }
                    if (filtered) return@accept false
                    if (EhDB.containLocalFavorites(info.gid)) info.favoriteSlot = GalleryInfo.LOCAL_FAVORITED
                    !(hideFavorited() && info.favoriteSlot != GalleryInfo.NOT_FAVORITED)
                },
            )
        }
    }
}

private fun String.normalized() = trim().replace(Regex("\\s+"), " ").lowercase()

private fun GalleryInfo.postedMillis() = posted?.let { runCatching { ParserUtils.parseDate(it) }.getOrNull() }
