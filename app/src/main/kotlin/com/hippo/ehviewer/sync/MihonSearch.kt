package com.hippo.ehviewer.sync

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/**
 * Searching a gallery in the Mihon fork (fork-only feature): a global search of the title without its bracket groups
 * (event, circle, parody, language, translator), or of the artist of its leading bracket group.
 */
object MihonSearch {
    private val BRACKET_GROUPS = Regex("""\[[^\]]*\]|\([^)]*\)|【[^】]*】|（[^）]*）|\{[^}]*\}|［[^］]*］""")
    private val LEADING_GROUP = Regex("""^\s*(?:(?:\([^)]*\)|（[^）]*）)\s*)*[\[【［]([^\]】］]+)[\]】］]""")
    private val INNER_PARENS = Regex("""[(（]([^)）]+)[)）]""")
    private val SPACES = Regex("""\s+""")

    // A leading group naming translators or a language rather than the artist
    private val NOT_ARTIST = Regex(
        """汉化|漢化|翻訳|翻译|翻譯|中文|中国|中國|chinese|english|translat|個人|个人|机翻|機翻|嵌字|修正|dl版|digital""",
        RegexOption.IGNORE_CASE,
    )

    /** The title without its bracket groups, the first of several titles separated by "|". */
    fun coreTitle(title: String): String {
        val first = title.substringBefore('|').trim()
        val core = first.replace(BRACKET_GROUPS, " ").replace(SPACES, " ").trim()
        return core.ifEmpty { first }
    }

    /**
     * The artist named in the leading bracket group of [title] ("[Circle (Artist)] Title" gives "Artist"), else the
     * first of [authors]; null if none.
     */
    fun artist(title: String, authors: List<String> = emptyList()): String? {
        var rest = title
        repeat(MAX_LEADING_GROUPS) {
            val group = LEADING_GROUP.find(rest) ?: return@repeat
            val inside = group.groupValues[1].trim()
            rest = rest.substring(group.range.last + 1)
            if (NOT_ARTIST.containsMatchIn(inside)) return@repeat
            val artist = INNER_PARENS.find(inside)?.groupValues?.get(1)?.trim() ?: inside
            if (artist.isNotEmpty()) return artist
        }
        return authors.map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }

    /** Opens a global search for [query] in Mihon; false if Mihon is not installed. */
    fun search(context: Context, query: String): Boolean {
        MIHON_PACKAGES.forEach { pkg ->
            val intent = Intent(MIHON_SEARCH_ACTION)
                .putExtra(MIHON_SEARCH_QUERY, query)
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // Not this build of Mihon
            }
        }
        return false
    }

    private const val MAX_LEADING_GROUPS = 3

    // The fork first, then the official app
    private val MIHON_PACKAGES = listOf("app.mihon.tiger", "app.mihon")
    private const val MIHON_SEARCH_ACTION = "eu.kanade.tachiyomi.SEARCH"
    private const val MIHON_SEARCH_QUERY = "query"
}
