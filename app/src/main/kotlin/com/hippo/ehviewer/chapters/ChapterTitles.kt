package com.hippo.ehviewer.chapters

import java.text.Normalizer

/** Fork: titles of uploads of one work told apart only by language, translators, edition, see [ChapterCompare]. */
object ChapterTitles {
    // Brackets escaped: on Android (ICU) a bare "}" does not compile
    private val BRACKETS = Regex("""\[[^\]]*\]|【[^】]*】|\([^)]*\)|（[^）]*）|\{[^}]*\}""")
    private val SPACES = Regex("\\s+")

    /**
     * The title without what is in brackets (the artist, the event, the magazine, the language, the translators,
     * "Digital"…), in lower case: "[A] Title (COMIC X 2010-05) [Chinese] [Group]" and "[A] Title (COMIC X 2010-05)"
     * are both "title". Full-width letters and signs as the plain ones ("＃" as "#").
     */
    fun core(title: String) = Normalizer.normalize(title, Normalizer.Form.NFKC).replace(BRACKETS, " ").replace(SPACES, " ").trim().lowercase()
}
