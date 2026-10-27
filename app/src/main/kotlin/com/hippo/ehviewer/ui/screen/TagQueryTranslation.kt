package com.hippo.ehviewer.ui.screen

import android.content.Context
import com.ehviewer.core.model.TagNamespace
import com.hippo.ehviewer.client.EhTagDatabase

// Fork: translations of the tags in a search history entry, shown below it

private val NAMESPACES = listOf(
    TagNamespace.Artist,
    TagNamespace.Cosplayer,
    TagNamespace.Character,
    TagNamespace.Female,
    TagNamespace.Group,
    TagNamespace.Language,
    TagNamespace.Location,
    TagNamespace.Male,
    TagNamespace.Mixed,
    TagNamespace.Other,
    TagNamespace.Parody,
    TagNamespace.Reclass,
)

// `f:"mind control$"`, `f:"mind control$` (closing quote lost), `a:chibigon$`, `artist:chibigon`; `-` or `~` before
private val TagRegex = Regex("""(?<![^\s\-~])([a-z]+):(?:"([^"$]+)\$?"?|([^\s"$]+)\$?)""")

/**
 * The tags of [query] translated, "女性:精神控制  语言:中文" for `f:"mind control$" l:chinese$ 催眠`; text that is no
 * tag is left out. Null when no tag of it has a translation, or the tag database is not loaded.
 */
context(_: Context)
fun translateTagQuery(query: String): String? {
    if (!EhTagDatabase.initialized || !EhTagDatabase.translatable) return null
    val parts = TagRegex.findAll(query).mapNotNull { match ->
        translateSearchTag(match.groupValues[1], match.groupValues[2].ifEmpty { match.groupValues[3] }.trim())
    }.toList()
    return parts.takeIf { it.isNotEmpty() }?.joinToString("  ")
}

/** "女性:巨乳" for the namespace `f` and the tag `big breasts`; null without a translation. */
context(_: Context)
fun translateSearchTag(ns: String, tag: String): String? {
    if (!EhTagDatabase.initialized || !EhTagDatabase.translatable) return null
    val namespace = NAMESPACES.find { it.value == ns || it.prefix == ns } ?: return null
    val translated = EhTagDatabase.getTranslation(namespace.prefix, tag) ?: return null
    val nsName = EhTagDatabase.getTranslation(tag = namespace.value) ?: namespace.value
    return "$nsName:$translated"
}
