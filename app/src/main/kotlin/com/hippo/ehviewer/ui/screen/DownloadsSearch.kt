package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.model.TagNamespace

// Fork: the downloads search box takes what the main search box takes: several words at once, short
// namespaces (`f:`), quotes and `$` (`female:"big breasts$"`), `-` to leave out and `~`

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
    TagNamespace.Temp,
)

// One word: `ns:"two words$"`, `"two words"`, or a run without spaces
private val TermRegex = Regex("""[^\s"]*"[^"]*"?\S*|\S+""")

class DownloadsSearch private constructor(private val terms: List<Term>) {
    /** [exact]: the word ended in `$`, so a tag must be all of it. */
    private class Term(val namespace: TagNamespace?, val text: String, val exact: Boolean, val exclude: Boolean)

    private class Tag(val namespace: TagNamespace?, val name: String, val raw: String)

    /** Whether a gallery with these titles and tags (`female:big breasts`, `_` before a weak one) has every word. */
    fun matches(title: String?, titleJpn: String?, simpleTags: List<String>?): Boolean {
        if (terms.isEmpty()) return true
        val tags by lazy { simpleTags.orEmpty().map(::parseTag) }
        return terms.all { term ->
            val found = if (term.namespace != null) {
                tags.any { it.namespace == term.namespace && term.fits(it.name) }
            } else {
                title.has(term.text) || titleJpn.has(term.text) || tags.any { term.fits(it.name) || it.raw.has(term.text) }
            }
            found != term.exclude
        }
    }

    private fun Term.fits(name: String) = if (exact) name.equals(text, true) else name.has(text)

    companion object {
        fun parse(keyword: String) = DownloadsSearch(TermRegex.findAll(keyword).mapNotNull { parseTerm(it.value) }.toList())

        private fun parseTerm(word: String): Term? {
            var rest = word.removePrefix("~")
            val exclude = rest.length > 1 && rest.startsWith('-')
            if (exclude) rest = rest.drop(1)
            val colon = rest.indexOf(':')
            val namespace = if (colon > 0 && '"' !in rest.take(colon)) findNamespace(rest.take(colon)) else null
            if (namespace != null) rest = rest.drop(colon + 1)
            rest = rest.replace("\"", "")
            val exact = rest.endsWith('$')
            val text = rest.removeSuffix("$").trim()
            return if (text.isEmpty()) null else Term(namespace, text, exact, exclude)
        }

        private fun findNamespace(text: String) = NAMESPACES.find { it.value.equals(text, true) || it.prefix.equals(text, true) }

        private fun parseTag(raw: String): Tag {
            val tag = raw.removePrefix("_")
            val colon = tag.indexOf(':')
            val namespace = if (colon > 0) TagNamespace.from(tag.take(colon)) else null
            return Tag(namespace, if (namespace != null) tag.drop(colon + 1) else tag, raw)
        }

        private fun String?.has(text: String) = this?.contains(text, true) == true
    }
}
