package com.hippo.ehviewer.restore

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.chapters.ChapterTitles
import com.hippo.ehviewer.client.EhFilter
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.wnacg.isWnacg

// Fork: a search of E-Hentai also looks on PandaChika, which keeps galleries E-Hentai removed: E-Hentai never lists
// those. Its galleries go between those of E-Hentai by posted time, but not one E-Hentai listed too (same gid or same
// title). See PandaChaikaMerger.

/** A search of E-Hentai as PandaChika can do it: words of the title, tags, an uploader. */
data class PandaChaikaQuery(val title: String?, val tags: List<String>, val uploader: String?) {
    suspend fun search(page: Int) = PandaChaikaApi.search(title, tags, uploader, page)

    /**
     * The searches on PandaChika for this one. Words E-Hentai finds in the titles and in the tags, PandaChika in the
     * titles only: one more search, of a tag named so ("mind control" is "female:mind control" and the others).
     */
    fun searches(): List<PandaChaikaQuery> {
        val words = title ?: return listOf(this)
        if (':' in words || words.split(' ').size > MAX_TAG_WORDS) return listOf(this)
        return listOf(this, copy(title = null, tags = tags + words.lowercase().replace(' ', '_')))
    }

    companion object {
        // The words of a tag at most, for searches()
        private const val MAX_TAG_WORDS = 4

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
            val keyword = lub.keyword?.trim()?.ifEmpty { null }
            val language = lub.language.takeIf { it in GalleryInfo.S_LANG_TAGS.indices }?.let { GalleryInfo.S_LANG_TAGS[it] }
            if (keyword == null) {
                // Only a language: the galleries in it
                return if (lub.mode == ListUrlBuilder.MODE_NORMAL && language != null) PandaChaikaQuery(null, listOf(language), null) else null
            }
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
 * Puts the galleries PandaChika has for a search between the pages of E-Hentai, see [BackupMerger]. [sources] are the
 * searches on PandaChika (PandaChaikaQuery.searches), each fetched as far as needed.
 */
class PandaChaikaMerger(
    accept: suspend (BaseGalleryInfo) -> Boolean,
    sources: List<suspend (page: Int) -> PandaChaikaApi.GalleryPage>,
    onFound: (Collection<PandaChaikaApi.Gallery>) -> Unit = RestoredGalleries::found,
    wnacgTitles: () -> List<String> = { emptyList() },
) : BackupMerger<PandaChaikaApi.Gallery>(
    accept = accept,
    sources = sources.map { fetch -> { page: Int -> fetch(page).toBackupPage() } },
    onFound = onFound,
    maxPages = MAX_PAGES,
    // Fork: a work 紳士漫畫 has, in any language, merged before or fetched for a later page ([wnacgTitles]): not again
    // (its galleries are mostly in Chinese, those of PandaChika hardly ever)
    moreEhKeys = { info -> if (info.isWnacg) listOfNotNull(info.title, info.titleJpn).mapNotNull(::workKey) else emptyList() },
    moreKeys = { title -> listOfNotNull(workKey(title)) },
    hiddenKeys = { wnacgTitles().mapNotNullTo(HashSet(), ::workKey) },
) {
    constructor(query: PandaChaikaQuery, accept: suspend (BaseGalleryInfo) -> Boolean, wnacgTitles: () -> List<String>) :
        this(accept, query.searches().map { search -> { page: Int -> search.search(page) } }, wnacgTitles = wnacgTitles)

    companion object {
        /** Pages of 100 galleries fetched at most for a search. */
        const val MAX_PAGES = 10

        /** For the search of [lub] when on in the settings, with its categories, rating, pages and the filters. */
        fun of(lub: ListUrlBuilder, hideFavorited: () -> Boolean, wnacgTitles: () -> List<String> = { emptyList() }): PandaChaikaMerger? {
            if (!Settings.pandaChaikaInSearch.value) return null
            val query = PandaChaikaQuery.of(lub) ?: return null
            return PandaChaikaMerger(query, searchAccept(lub, hideFavorited), wnacgTitles)
        }
    }
}

/** The work of [title], whatever its language and translators: its title without what is in brackets. */
private fun workKey(title: String) = ChapterTitles.core(title).ifEmpty { null }?.let { "work|$it" }

/** The categories a search of [lub] shows: those picked, or Non-H only without an account (as E-Hentai does). */
fun searchCategories(lub: ListUrlBuilder): Int {
    val signedIn = Settings.hasSignedIn.value
    val category = if (!signedIn && lub.category <= 0) EhUtils.NON_H else lub.category
    return if (lub.mode == ListUrlBuilder.MODE_NORMAL) category else -1
}

/**
 * Whether a gallery of another site shows in the search of [lub]: in its categories, rating and pages, not hidden by
 * the filters, and not favorited when those are hidden.
 */
fun searchAccept(lub: ListUrlBuilder, hideFavorited: () -> Boolean): suspend (BaseGalleryInfo) -> Boolean {
    val category = searchCategories(lub)
    val normal = lub.mode == ListUrlBuilder.MODE_NORMAL
    val minRating = if (normal) lub.minRating else -1
    val pageFrom = if (normal) lub.pageFrom else -1
    val pageTo = if (normal) lub.pageTo else -1
    return accept@{ info ->
        if (category > 0 && (info.category and category) == 0) return@accept false
        if (minRating > 0 && info.rating < minRating) return@accept false
        if (pageFrom > 0 && info.pages < pageFrom) return@accept false
        if (pageTo > 0 && info.pages > pageTo) return@accept false
        val filtered = with(EhFilter) { filterTitle(info) || filterUploader(info) || filterTag(info) || filterTagNamespace(info) }
        if (filtered) return@accept false
        if (EhDB.containLocalFavorites(info.gid)) info.favoriteSlot = GalleryInfo.LOCAL_FAVORITED
        !(hideFavorited() && info.favoriteSlot != GalleryInfo.NOT_FAVORITED)
    }
}

private fun PandaChaikaApi.GalleryPage.toBackupPage() = BackupPage(
    items = galleries.mapNotNull { gallery -> gallery.toGalleryInfo()?.let { BackupItem(gallery, it, gallery.posted * 1000) } },
    postedTimes = galleries.map { it.posted * 1000 },
    hasNext = hasNext,
)
