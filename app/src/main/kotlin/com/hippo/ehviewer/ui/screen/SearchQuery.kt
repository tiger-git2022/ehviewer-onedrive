package com.hippo.ehviewer.ui.screen

/**
 * Fork: closes the quoted tags left open in a search, `f:"mind control$ p:"azur lane$"` becomes
 * `f:"mind control$" p:"azur lane$"`. E-Hentai read an open quote on to the next quote, so two tags searched for one
 * odd phrase and found nothing. A `$` ends a tag: inside a quote, a `$` before a space or the end closes the quote; a
 * quote still open at the end is closed there.
 */
fun closeTagQuotes(query: String): String = buildString {
    var quoted = false
    query.forEachIndexed { i, c ->
        append(c)
        if (c == '"') {
            quoted = !quoted
        } else if (quoted && c == '$' && (i == query.lastIndex || query[i + 1].isWhitespace())) {
            append('"')
            quoted = false
        }
    }
    if (quoted) append('"')
}

/**
 * Fork: where a tag picked from the suggestions goes in [query] (the index of the last character kept before it, -1
 * for none). After a closed tag, it goes after its `"` or `$`; while typing in an open quote (`f:"mind con`), it takes
 * the place of the whole tag being typed, or the namespace and the quote stayed in front of it.
 */
fun tagSuggestionIndex(query: String): Int {
    val end = query.lastIndexOfAny(charArrayOf('"', '$'))
    if (end < 0 || query[end] != '"') return end
    val opening = query.take(end + 1).count { it == '"' } % 2 == 1
    return if (opening) query.lastIndexOf(' ', end) else end
}
