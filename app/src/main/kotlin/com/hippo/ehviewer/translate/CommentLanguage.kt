package com.hippo.ehviewer.translate

/**
 * Fork: the language a comment is translated into, see [CommentTranslations]. [code] is stored in the settings,
 * [promptName] goes into the request to the model.
 */
enum class TargetLanguage(val code: String, val promptName: String, val displayName: String) {
    SimplifiedChinese("zh-CN", "Simplified Chinese", "简体中文"),
    TraditionalChinese("zh-TW", "Traditional Chinese", "繁體中文"),
    English("en", "English", "English"),
    Japanese("ja", "Japanese", "日本語"),
    Korean("ko", "Korean", "한국어"),
    ;

    /** The language of the comments left as they are: Chinese for both Chinese targets. */
    val primary: String
        get() = code.substringBefore('-')

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code } ?: SimplifiedChinese
    }
}

/**
 * Fork: a first guess at the language of a comment from the scripts of its letters, before ML Kit (see
 * [CommentTranslations]). Chinese, Japanese and Korean are told apart by their scripts alone, more reliably than by
 * ML Kit on short comments; anything else (Latin, Cyrillic...) is [OTHER], for ML Kit to name.
 */
object CommentScript {
    const val CHINESE = "zh"
    const val JAPANESE = "ja"
    const val KOREAN = "ko"

    /** Letters in another script, to be named by ML Kit. */
    const val OTHER = "other"

    // Links say nothing about the language of a comment
    private val urlRegex = Regex("""(https?://|www\.)\S+""", RegexOption.IGNORE_CASE)

    /** The language by script, [OTHER], or null for a comment without letters (numbers, emoji, links only). */
    fun guess(text: String): String? {
        val stripped = urlRegex.replace(text, " ")
        var han = 0
        var kana = 0
        var hangul = 0
        var other = 0
        var i = 0
        while (i < stripped.length) {
            val cp = stripped.codePointAt(i)
            i += Character.charCount(cp)
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                else -> if (Character.isLetter(cp)) other++
            }
        }
        if (han + kana + hangul + other == 0) return null
        // A Chinese character is about a word, so is a word of about four Latin letters: "求 Kemono Friends 的汉化"
        // stays Chinese, "这本 is the best of the best" does not
        val total = han + kana + hangul + other / 4.0
        return when {
            hangul >= total * 0.3 -> KOREAN
            // Japanese writes kana around its kanji; one "の" in a Chinese comment is not enough
            kana >= 2 && kana * 4 >= han + kana && han + kana >= total * 0.3 -> JAPANESE
            han + kana >= total * 0.5 -> CHINESE
            else -> OTHER
        }
    }
}

/**
 * Fork: a comment that lists the contents of a gallery (an anthology, a magazine issue) is names and titles, nothing
 * worth translating. Told by its lines: dots leading to a page, or pages going up through the issue:
 *
 *     003 - ジョン・K・ペー太 / John K. Pe-ta ... どうしようが止まらない
 *     007 - 朱シオ / Aka Shio .............................. 不器用巫女の恋愛析願Q
 *
 * A comment of a few numbered points ("1. the art... 2. the story...") is no such list and is translated.
 */
object CommentListing {
    // "003 - ", "p.12 ", "12. ", "P45 "
    private val pageRegex = Regex("""^(?:pp?\.?\s*)?(\d{1,4})\s*(?:[-–—~～:：.)）|]|\s)\s*\S""", RegexOption.IGNORE_CASE)

    // A run of dots or the like between a title and its page
    private val leaderRegex = Regex("""\.{4,}|…{2,}|・{4,}|·{4,}""")

    private const val MIN_ENTRIES = 4

    fun isListing(text: String): Boolean {
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.size < MIN_ENTRIES) return false
        val pages = mutableListOf<Int>()
        var entries = 0
        lines.forEach { line ->
            val page = pageRegex.find(line)?.groupValues?.get(1)?.toInt()
            if (page != null) pages += page
            if (page != null || leaderRegex.containsMatchIn(line)) entries++
        }
        // A header and credits around the list are fine, but most lines are entries
        if (entries < MIN_ENTRIES || entries * 2 < lines.size) return false
        val leaders = lines.count { leaderRegex.containsMatchIn(it) }
        // Pages go up, and further than the points of a comment would
        val paged = pages.size >= MIN_ENTRIES && pages.zipWithNext().all { (a, b) -> b >= a } && pages.last() >= 20
        return leaders >= 3 || paged
    }
}
