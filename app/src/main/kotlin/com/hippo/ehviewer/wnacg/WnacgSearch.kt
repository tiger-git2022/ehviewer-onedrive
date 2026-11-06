package com.hippo.ehviewer.wnacg

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.chapters.ChapterTitles
import com.hippo.ehviewer.chapters.Wnacg
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.restore.BackupItem
import com.hippo.ehviewer.restore.BackupMerger
import com.hippo.ehviewer.restore.BackupPage
import com.hippo.ehviewer.restore.normalized
import com.hippo.ehviewer.restore.searchAccept
import com.hippo.ehviewer.restore.searchCategories

// Fork: a search of E-Hentai also looks on 紳士漫畫, see WnacgGalleries.kt. Its albums go between the galleries of
// E-Hentai by the time they were put up, but not one of a title E-Hentai listed in the same language.

/**
 * A search of E-Hentai as 紳士漫畫 can do it: words of the titles. Its tags are its own (in Chinese), so of the tags of
 * E-Hentai only the names of artists, groups, parodies and characters go, as words, which its titles have.
 * [language] is "ZH", "EN" when the search asks for one, null for all.
 */
data class WnacgQuery(val words: String, val language: String?) {
    companion object {
        // Namespaces whose tags are names, which titles have
        private val NAMES = setOf(
            "a", "artist", "g", "group", "circle", "p", "parody", "series", "c", "char", "character", "cos", "cosplayer",
        )
        private val LANGUAGES = setOf("l", "lang", "language")

        // A word: -/~ before it, a namespace, then a "quoted" or a bare value
        private val TOKEN = Regex("""([-~])?(?:([A-Za-z]+):)?(?:"([^"]*)"?|(\S+))""")

        /** The language of a tag of E-Hentai the site has, "" for one it has not. */
        private fun languageOf(value: String) = when (value.lowercase()) {
            "chinese" -> "ZH"
            "english" -> "EN"
            "translated", "rewrite" -> null
            else -> ""
        }

        /** The words of an E-Hentai search ("f_search"), null when 紳士漫畫 cannot search it. */
        fun fromKeyword(keyword: String): WnacgQuery? {
            val words = mutableListOf<String>()
            var language: String? = null
            for (match in TOKEN.findAll(keyword)) {
                val (prefix, ns, quoted, bare) = match.destructured
                val value = quoted.ifEmpty { bare }.trim('$', '*', ' ').trim()
                if (value.isEmpty()) continue
                // Left out, or either of several: the site cannot
                if (prefix.isNotEmpty()) continue
                val namespace = ns.lowercase()
                when {
                    namespace.isEmpty() -> words += value
                    namespace in NAMES -> words += value
                    namespace in LANGUAGES -> when (val lang = languageOf(value)) {
                        null -> Unit
                        "" -> return null
                        else -> language = lang
                    }
                    // Any other tag: the site has no way to ask for it
                    else -> return null
                }
            }
            val text = words.joinToString(" ").ifBlank { return null }
            return WnacgQuery(text, language)
        }

        /** The search of [lub], null when 紳士漫畫 cannot do it (the front page, the popular, a tag...). */
        fun of(lub: ListUrlBuilder): WnacgQuery? {
            val keyword = lub.keyword?.trim()?.ifEmpty { null } ?: return null
            val query = when (lub.mode) {
                ListUrlBuilder.MODE_NORMAL -> fromKeyword(keyword)
                ListUrlBuilder.MODE_TAG -> {
                    val ns = keyword.substringBefore(':', "").lowercase()
                    val value = keyword.substringAfter(':').trim('$', '"', ' ')
                    if (ns in NAMES && value.isNotEmpty()) WnacgQuery(value, null) else null
                }
                else -> null
            } ?: return null
            val language = lub.language.takeIf { it in GalleryInfo.S_LANG_TAGS.indices }
                ?.let { languageOf(GalleryInfo.S_LANG_TAGS[it].substringAfter(':')) ?: "" }
            return when {
                language == null -> query
                language.isEmpty() -> null
                query.language != null && query.language != language -> null
                else -> query.copy(language = language)
            }
        }

        /** Whether an album of [language] ("ZH", "EN", "JA", null when not told) shows in a search of [wanted]. */
        fun languageMatches(wanted: String?, language: String?) = when (wanted) {
            null -> true
            // Most albums of the site not told are in Chinese
            "ZH" -> language == "ZH" || language == null
            else -> language == wanted
        }
    }
}

/**
 * The titles that are the same for a search: the title as is, and its core (without what is in brackets) in its
 * language, so that a Chinese translation shows when E-Hentai listed only the Japanese one.
 */
fun wnacgTitleKeys(title: String, language: String?): List<String> = listOf(title.normalized(), "${ChapterTitles.core(title)}|${language.orEmpty()}")

/** The searches on 紳士漫畫 a page of it fetches at most. */
private const val WNACG_MAX_PAGES = 5

/**
 * For the search of [lub] when on in the settings, with its categories, rating, pages and the filters. [onBlocked] is
 * told when the site asks for its check (see ui/WnacgCheck.kt): the results then have none of its albums.
 */
fun wnacgMerger(lub: ListUrlBuilder, hideFavorited: () -> Boolean, onBlocked: () -> Unit = {}): BackupMerger<Wnacg.Album>? {
    if (!Settings.wnacgInSearch.value) return null
    val query = WnacgQuery.of(lub) ?: return null
    val categories = searchCategories(lub)
    if (categories > 0 && categories and WNACG_CATEGORIES == 0) return null
    // No rating on the site: a search of a rating has none of its albums
    if (lub.mode == ListUrlBuilder.MODE_NORMAL && lub.minRating > 0) return null
    val accept = searchAccept(lub, hideFavorited)
    return BackupMerger(
        accept = { info -> accept(info) },
        sources = listOf { page ->
            val result = Wnacg.searchPage(query.words, page)
            BackupPage(
                items = result.albums.filter { WnacgQuery.languageMatches(query.language, wnacgLanguage(it.category)) }
                    .map { BackupItem(it, it.toGalleryInfo(), it.created) },
                postedTimes = result.albums.map { it.created }.filter { it > 0 },
                hasNext = result.hasNext,
            )
        },
        onFound = {},
        maxPages = WNACG_MAX_PAGES,
        titleKeys = ::wnacgTitleKeys,
        moreTitles = ::japaneseTitles,
        onError = { if (it is Wnacg.BlockedException) onBlocked() },
    )
}

/**
 * The Japanese titles of the galleries of E-Hentai in [page] whose list told only one: the titles of the site are
 * mostly those. Asked to the API of E-Hentai, on copies, not to change the list.
 */
private suspend fun japaneseTitles(page: List<BaseGalleryInfo>): Map<Long, List<String>> {
    val asked = page.filter { it.titleJpn == null && !it.isWnacg && it.token.isNotBlank() }
        .map { BaseGalleryInfo(gid = it.gid, token = it.token) }
    if (asked.isEmpty()) return emptyMap()
    EhEngine.fillGalleryListByApi(asked, EhUrl.referer)
    return asked.associate { it.gid to listOfNotNull(it.title, it.titleJpn?.ifBlank { null }) }
}
