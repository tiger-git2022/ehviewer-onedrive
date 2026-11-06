package com.hippo.ehviewer.wnacg

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.TagNamespace
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.chapters.ChapterTitles
import com.hippo.ehviewer.chapters.Wnacg
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.restore.BackupItem
import com.hippo.ehviewer.restore.BackupMerger
import com.hippo.ehviewer.restore.BackupPage
import com.hippo.ehviewer.restore.normalized
import com.hippo.ehviewer.restore.searchAccept
import com.hippo.ehviewer.restore.searchCategories
import moe.tarsin.coroutines.runSuspendCatching

// Fork: a search of E-Hentai also looks on 紳士漫畫, see WnacgGalleries.kt. Its albums go between the galleries of
// E-Hentai by the time they were put up, but not one of a title E-Hentai listed in the same language.

/**
 * A search of E-Hentai as 紳士漫畫 can do it: words of the titles. Its tags are its own (in Chinese), so of the tags of
 * E-Hentai only the names of artists, groups, parodies and characters go, as words, which its titles have.
 * [language] is "ZH", "EN" when the search asks for one, null for all. [unwritten] is the one name of an artist, a group
 * or a cosplayer the tag database did not tell, in [words] as the tag: see [guessWrittenName].
 */
data class WnacgQuery(val words: String, val language: String?, val unwritten: WnacgName? = null) {
    /** The query with [name] for [unwritten]. */
    fun withName(name: String): WnacgQuery = unwritten?.let {
        copy(words = words.substring(0, it.index) + name + words.substring(it.index + it.value.length), unwritten = null)
    } ?: this

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

        // Namespaces whose names its titles have as written (Japanese for most), which the tag database tells
        private val WRITTEN = mapOf(
            "a" to TagNamespace.Artist, "artist" to TagNamespace.Artist,
            "g" to TagNamespace.Group, "group" to TagNamespace.Group, "circle" to TagNamespace.Group,
            "cos" to TagNamespace.Cosplayer, "cosplayer" to TagNamespace.Cosplayer,
        )

        /**
         * The name of an artist, a group or a cosplayer as the titles have it: "山下クロヲ" for `a:"yamashita kurowo$"`
         * when the tag database has it, as the tag otherwise. Parodies and characters stay as they are, the database
         * having their Chinese names, which the Japanese titles do not.
         */
        fun writtenName(namespace: String, value: String): String {
            val ns = WRITTEN[namespace.lowercase()] ?: return value
            if (!EhTagDatabase.initialized) return value
            return runCatching { EhTagDatabase.getTranslation(ns.prefix, value.lowercase()) }.getOrNull() ?: value
        }

        /**
         * The words of an E-Hentai search ("f_search"), null when 紳士漫畫 cannot search it. [nameOf] tells how the
         * titles write a name of a tag.
         */
        fun fromKeyword(keyword: String, nameOf: (namespace: String, value: String) -> String = ::writtenName): WnacgQuery? {
            val words = mutableListOf<String>()
            val unwritten = mutableListOf<WnacgName>()
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
                    namespace in NAMES -> {
                        val name = nameOf(namespace, value)
                        if (name == value && namespace in WRITTEN) {
                            unwritten += WnacgName(namespace, value, words.sumOf { it.length + 1 })
                        }
                        words += name
                    }
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
            return WnacgQuery(text, language, unwritten.singleOrNull())
        }

        /** The search of [lub], null when 紳士漫畫 cannot do it (the front page, the popular, a tag...). */
        fun of(lub: ListUrlBuilder, nameOf: (namespace: String, value: String) -> String = ::writtenName): WnacgQuery? {
            val keyword = lub.keyword?.trim()?.ifEmpty { null } ?: return null
            val query = when (lub.mode) {
                ListUrlBuilder.MODE_NORMAL -> fromKeyword(keyword, nameOf)
                ListUrlBuilder.MODE_TAG -> {
                    val ns = keyword.substringBefore(':', "").lowercase()
                    val value = keyword.substringAfter(':').trim('$', '"', ' ')
                    if (ns in NAMES && value.isNotEmpty()) {
                        val name = nameOf(ns, value)
                        WnacgQuery(name, null, WnacgName(ns, value).takeIf { name == value && ns in WRITTEN })
                    } else {
                        null
                    }
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
 * A name of a tag of E-Hentai: its namespace ("a", "group"...) and its value, in latin letters, at [index] of the words
 * of its query.
 */
data class WnacgName(val namespace: String, val value: String, val index: Int = 0)

// The first [...] of a title, after an event in (...): "[サークル (作者)]" or "[作者]"
private val TITLE_BRACKET = Regex("""^\s*(?:\([^)]*\)\s*)?\[([^\]]+)\]""")
private val BRACKET_PARTS = Regex("""^(.*?)\s*[(（]([^)）]*)[)）]\s*$""")
private val NAME_SEPARATORS = Regex("""\s*[、,，/&＆]\s*""")

/**
 * The name of [name] as the titles of E-Hentai of its search write it, from their first brackets: the one most of them
 * have, not in latin letters only. "山下クロヲ" for the artist `yamashita kurowo` from "[くろほんやさん (山下クロヲ)] …";
 * a group is the part before the brackets. Null when the titles tell none.
 */
fun guessWrittenName(name: WnacgName, titles: List<String>): String? {
    val artist = name.namespace.lowercase() !in setOf("g", "group", "circle")
    val named = titles.map { title ->
        val bracket = TITLE_BRACKET.find(title)?.groupValues?.get(1)?.trim() ?: return@map emptySet()
        val parts = BRACKET_PARTS.find(bracket)
        when {
            parts == null -> setOf(bracket)
            artist -> parts.groupValues[2].split(NAME_SEPARATORS).toSet()
            else -> setOf(parts.groupValues[1])
        }.map { it.trim() }.filter { it.isNotEmpty() && it.any { c -> c.code > 0x7f } }.toSet()
    }.filter { it.isNotEmpty() }
    if (named.isEmpty()) return null
    val counts = named.flatten().groupingBy { it }.eachCount()
    val (best, count) = counts.maxByOrNull { it.value } ?: return null
    // Most of them, and no other as many: else none
    return best.takeIf { count * 2 > named.size && counts.values.count { it == count } == 1 }
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
    // A name the tag database did not tell: as the titles of the first page of E-Hentai write it
    var current = query
    return BackupMerger(
        accept = { info -> accept(info) },
        onEhPage = { page ->
            val unwritten = current.unwritten
            if (unwritten != null && page.isNotEmpty()) {
                val titles = page.flatMap { listOfNotNull(it.title, it.titleJpn) }
                val name = guessWrittenName(unwritten, titles)
                    ?: guessWrittenName(unwritten, runSuspendCatching { japaneseTitles(page) }.getOrNull()?.values?.flatten().orEmpty())
                current = name?.let(current::withName) ?: current.copy(unwritten = null)
            }
        },
        sources = listOf { page ->
            val query = current
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
