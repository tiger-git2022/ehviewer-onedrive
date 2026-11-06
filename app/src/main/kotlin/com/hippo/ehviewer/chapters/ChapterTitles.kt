package com.hippo.ehviewer.chapters

/** Fork: titles of uploads of one work told apart only by language, translators, edition, see [ChapterCompare]. */
object ChapterTitles {
    private val BRACKETS = Regex("\\[[^\\]]*]|【[^】]*】|\\([^)]*\\)|（[^）]*）|\\{[^}]*}")
    private val SPACES = Regex("\\s+")

    /**
     * The title without what is in brackets (the artist, the event, the magazine, the language, the translators,
     * "Digital"…), in lower case: "[A] Title (COMIC X 2010-05) [Chinese] [Group]" and "[A] Title (COMIC X 2010-05)"
     * are both "title".
     */
    fun core(title: String) = title.replace(BRACKETS, " ").replace(SPACES, " ").trim().lowercase()
}
