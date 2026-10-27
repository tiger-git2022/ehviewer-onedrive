package com.hippo.ehviewer.ui.screen

/**
 * Fork: the tags of a search, shown as chips in the search field instead of the text `f:"big breasts$"`. A tag is a
 * namespaced exact tag (`f:"big breasts$"`, `l:chinese$`), with `-` (excluded) or `~` (or) in front; the rest of the
 * search is plain text. The search sent is the tags and the text joined with spaces, see [joinSearchTags].
 */
private val SearchTagRegex = Regex("""(?<!\S)[-~]?[a-z]+:(?:"[^"$]+\$"|[^\s"$]+\$)(?!\S)""")

/** The tags of [query] and the text left, the words of the text joined with single spaces. */
fun splitSearchTags(query: String): Pair<List<String>, String> {
    val tags = SearchTagRegex.findAll(query).map { it.value }.toList()
    if (tags.isEmpty()) return emptyList<String>() to query
    val text = query.replace(SearchTagRegex, " ").trim().replace(Whitespace, " ")
    return tags to text
}

/** The search of [tags] and [text], as E-Hentai takes it: tags and text apart from each other with a space. */
fun joinSearchTags(tags: List<String>, text: CharSequence): String = buildString {
    tags.joinTo(this, " ")
    if (tags.isNotEmpty() && text.isNotEmpty() && !text[0].isWhitespace()) append(' ')
    append(text)
}

/** [tag] excluded from the search, or no longer excluded. */
fun toggleExcludedTag(tag: String): String = when (tag.firstOrNull()) {
    '-' -> tag.drop(1)
    '~' -> "-${tag.drop(1)}"
    else -> "-$tag"
}

fun isExcludedTag(tag: String) = tag.startsWith('-')

/** The namespace and the tag of [tag], `f` and `big breasts` for `-f:"big breasts$"`. */
fun searchTagParts(tag: String): Pair<String, String> {
    val body = tag.trimStart('-', '~')
    val namespace = body.substringBefore(':')
    val name = body.substringAfter(':').removeSurrounding("\"").removeSuffix("$")
    return namespace to name
}

/**
 * A tag picked from the suggestions as typed: [typed] is the text it takes the place of, a `-` or `~` before it stays
 * in front of the tag.
 */
fun pickedSearchTag(wrapped: String, typed: String): String = when (typed.trimStart().firstOrNull()) {
    '-', '~' -> typed.trimStart().first() + wrapped
    else -> wrapped
}

private val Whitespace = Regex("\\s+")
