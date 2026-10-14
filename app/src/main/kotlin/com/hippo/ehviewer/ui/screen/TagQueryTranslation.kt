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
        val ns = match.groupValues[1]
        val namespace = NAMESPACES.find { it.value == ns || it.prefix == ns } ?: return@mapNotNull null
        val tag = match.groupValues[2].ifEmpty { match.groupValues[3] }.trim()
        val translated = EhTagDatabase.getTranslation(namespace.prefix, tag) ?: return@mapNotNull null
        val nsName = EhTagDatabase.getTranslation(tag = namespace.value) ?: namespace.value
        "$nsName:$translated"
    }.toList()
    return parts.takeIf { it.isNotEmpty() }?.joinToString("  ")
}
