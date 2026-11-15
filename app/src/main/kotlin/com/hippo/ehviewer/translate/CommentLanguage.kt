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

    // A long run of dots or the like between a name and a title, words on both sides; "そうか……" or "でも……好き" is
    // only punctuation
    private val leaderRegex = Regex("""\S\s*(?:\.{5,}|…{3,}|・{5,}|·{5,})\s*\S""")

    // The heading of the list: "Contents", "★Contents★", "目次", "収録作品:"
    private val headingRegex = Regex(
        """^[\p{P}\p{S}\s]*(?:table of contents|contents|目次|もくじ|目录|目錄|收录|收錄|収録作品|収録)[\p{P}\p{S}\s]*$""",
        RegexOption.IGNORE_CASE,
    )

    private const val MIN_ENTRIES = 4

    // Under a heading, a short list of three is enough
    private const val MIN_ENTRIES_UNDER_HEADING = 3

    fun isListing(text: String): Boolean {
        val all = text.lines().map(String::trim).filter(String::isNotEmpty)
        // With a heading, the title, the cover artist and the like above it are not counted
        val heading = all.indexOfFirst { headingRegex.matches(it) }
        val lines = if (heading >= 0) all.drop(heading + 1) else all
        val minEntries = if (heading >= 0) MIN_ENTRIES_UNDER_HEADING else MIN_ENTRIES
        if (lines.size < minEntries) return false
        val pages = mutableListOf<Int>()
        var entries = 0
        lines.forEach { line ->
            val page = pageRegex.find(line)?.groupValues?.get(1)?.toInt()
            if (page != null) pages += page
            if (page != null || leaderRegex.containsMatchIn(line)) entries++
        }
        // A header and credits around the list are fine, but most lines are entries
        if (entries < minEntries || entries * 2 < lines.size) return false
        val leaders = lines.count { leaderRegex.containsMatchIn(it) }
        // Pages go up, and further than the points of a comment would
        val paged = pages.size >= minEntries && pages.zipWithNext().all { (a, b) -> b >= a } && pages.last() >= 20
        return leaders >= 3 || paged
    }
}

/**
 * Fork: a comment that only points somewhere, the raw (the untranslated original) or where to buy it, is links and a
 * label, nothing worth translating:
 *
 *     RAW: https://exhentai.org/g/4292915/252a0c089c/
 *     Original / buy it at DLsite: https://www.dlsite.com/maniax/work/=/product_id/RJ01234567.html
 *
 * Told by what is left once the links and the labels around them are taken out: nothing. It needs a link or the word
 * "raw", so a short remark ("Original", "Source?") is still translated, and so is a question about the link.
 */
object CommentLinks {
    // With or without "https://": "exhentai.org/g/123/abc", "www.dlsite.com/..."
    private val urlRegex = Regex(
        """(?:https?://|www\.)\S+|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|jp|co|me|io|tv|moe|info|to|cc)\b(?:/\S*)?""",
        RegexOption.IGNORE_CASE,
    )

    private val rawWords = setOf("raw", "raws")

    // The words around such links; any other word and the comment says something
    private val labelWords = rawWords + setOf(
        "original", "originals", "orig", "source", "sauce", "src", "link", "links", "here", "mirror", "alt",
        "buy", "purchase", "support", "store", "shop", "official", "available", "at", "on", "from", "in", "of", "for",
        "and", "or", "the", "a", "it", "its", "their", "them", "by", "with", "via", "full", "page", "gallery", "work",
        "artist", "artists", "author", "circle", "version", "ver", "edition", "digital", "hd", "jp", "jpn", "japanese",
        "eng", "en", "english", "cn", "chinese", "kr", "korean", "uncensored", "uncen", "decensored", "censored",
        "color", "colored", "colour", "coloured", "dl", "download", "dlsite", "fanza", "dmm", "melonbooks", "melon",
        "toranoana", "tora", "booth", "fantia", "pixiv", "fanbox", "patreon", "twitter", "skeb", "amazon", "getchu",
        "digiket", "kindle", "previous", "prev", "next", "part", "chapter", "ch", "vol", "volume",
    )

    // Japanese, Chinese and Korean labels, longest first: "DL版はこちら", "购买链接", "원본"
    private val cjkLabels = listOf(
        "無修正", "無修整", "同人誌", "単行本", "こちら", "ここ", "購入", "販売", "原作", "原本", "原版", "生肉", "公式", "作者",
        "作品", "リンク", "サイト", "ページ", "支援", "応援", "購買", "购买", "鏈接", "链接", "連結", "连结", "来源", "來源", "出处",
        "出處", "原图", "原圖", "원본", "구매", "링크", "여기", "版", "元", "先", "は", "で", "の", "から", "より", "在", "这里", "這裡",
    )

    fun isLinksOnly(text: String): Boolean {
        val hasLink = urlRegex.containsMatchIn(text)
        var rest = urlRegex.replace(text, " ")
        // A question about the link is worth reading
        if ('?' in rest || '？' in rest) return false
        val words = rest.split(Regex("""[^\p{L}]+""")).filter(String::isNotEmpty)
        val hasRaw = words.any { it.lowercase() in rawWords } || "生肉" in rest
        if (!hasLink && !hasRaw) return false
        cjkLabels.forEach { rest = rest.replace(it, " ") }
        return rest.split(Regex("""[^\p{L}]+""")).all { it.isEmpty() || it.lowercase() in labelWords }
    }
}

/**
 * Fork: a comment that lists the other parts of a series or the other languages of a gallery is titles, links and
 * translators, nothing worth translating:
 *
 *     The series so far:
 *     00 - Sabbat - https://exhentai.org/g/1483497/3519d9a634/
 *     01 - Falling - https://exhentai.org/g/1489916/15a374622c/
 *
 *     Other Language or Version
 *     Japanese digital
 *     English digital, translated by Doujins.com
 *     Series part : 催眠用務員
 *
 * Told by its lines: headings ("The series so far", "Series part : title", "Other Language or Version"), a short title
 * with a link, or a language and who translated it. A question or a remark among them and the comment is translated.
 */
object CommentSeries {
    private val urlRegex = Regex("""(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)

    // "The series so far:", "Series part : 催眠用務員", "Other Language or Version", "Previous: ...", "系列："
    private val headingRegex = Regex(
        """^[\p{P}\s]*(?:(?:the\s+)?series(?:\s+(?:so\s+far|parts?|list|index|entries|order))?|other\s+languages?(?:\s+(?:or|and|/)\s+versions?)?|other\s+versions?|previous(?:\s+(?:part|chapter|work))?|prev|next(?:\s+(?:part|chapter|work))?|related(?:\s+works?)?|シリーズ|前作|続編|系列作品|系列|续作|其他语言|其他版本)(?:\s*[:：-]\s*(.*)|\s*)$""",
        RegexOption.IGNORE_CASE,
    )

    private val languages = setOf(
        "japanese", "english", "chinese", "korean", "spanish", "french", "german", "russian", "portuguese", "italian",
        "thai", "vietnamese", "indonesian", "polish", "ukrainian", "turkish", "arabic", "hungarian", "czech", "dutch",
        "tagalog", "filipino", "malay", "swedish", "finnish", "greek", "hebrew", "romanian", "slovak", "norwegian", "danish",
    )

    // What follows the language on such a line: "digital", "translated by X", "You are here!"
    private val versionWords = setOf(
        "digital", "print", "raw", "decensored", "uncensored", "censored", "colored", "colorized", "coloured", "rewrite",
        "rewritten", "version", "ver", "edition", "hd", "ai", "mtl", "machine", "translation", "translated", "by",
        "you", "are", "here", "and", "edited", "edit",
    )

    private val translatedByRegex = Regex("""translated\s+by""", RegexOption.IGNORE_CASE)

    private const val MAX_TITLE_WORDS = 10
    private const val MAX_TITLE_CHARS = 80

    private fun words(text: String) = text.split(Regex("""[^\p{L}\p{N}]+""")).filter(String::isNotEmpty)

    private fun isQuestion(text: String) = '?' in text || '？' in text

    private fun isHeading(line: String): Boolean {
        val match = headingRegex.matchEntire(line) ?: return false
        val title = match.groupValues[1]
        return !isQuestion(title) && title.length <= MAX_TITLE_CHARS
    }

    // "Japanese digital", "Chinese digital, translated by 萌意永久润色机翻, You are here!", or "digital" alone
    private fun isVersion(line: String): Boolean {
        if (isQuestion(line)) return false
        // Who translated it is a name, whatever it is
        val words = words(line.split(translatedByRegex, limit = 2)[0]).map { it.lowercase() }
        if (words.isEmpty()) return false
        val rest = if (words[0] in languages) words.drop(1) else words
        return (words[0] in languages || rest.isNotEmpty()) && rest.all { it in versionWords }
    }

    // "00 - Sabbat - https://exhentai.org/g/1483497/3519d9a634/", "Part 2: https://..."
    private fun isLinkEntry(line: String): Boolean {
        if (!urlRegex.containsMatchIn(line)) return false
        val rest = urlRegex.replace(line, " ")
        return !isQuestion(rest) && rest.trim().length <= MAX_TITLE_CHARS && words(rest).size <= MAX_TITLE_WORDS
    }

    fun isSeriesList(text: String): Boolean {
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.size < 3) return false
        var headings = 0
        var entries = 0
        lines.forEach { line ->
            when {
                isLinkEntry(line) || isVersion(line) -> entries++
                isHeading(line) -> headings++
            }
        }
        // A list under a heading, or links alone; most lines are entries, a remark among them is fine
        return entries >= 2 && (headings >= 1 || entries >= 3) && (entries + headings) * 5 >= lines.size * 4
    }
}

/**
 * Fork: whether [result], the reply of the model for [source], reads as a translation into [target], by its scripts. A
 * model sometimes answers a comment instead ("Got it — noted..."), in the language of the comment; that is no
 * translation, it is neither shown nor kept. A reply as the comment was (a name) or without letters passes.
 *
 * Names and titles are kept as they were (see [CommentTranslateApi.systemPrompt]): "会有后续吗？请翻译：[Chococornet
 * (Tenro Aya)] Collapse Kuzure Yuku Risei" is Chinese, though half its letters are Latin. So what the reply took from the
 * comment as it was is left out before the scripts are counted: links, what is in brackets, capitalized words and words
 * in kana or hangul. Lower case words taken over are counted, an untranslated sentence is no translation.
 */
fun isTranslationInto(result: String, source: String, target: TargetLanguage): Boolean {
    if (result.trim() == source.trim()) return true
    // A reply all taken over from the comment is judged as a whole: a Korean comment left as it was is no translation
    val guess = CommentScript.guess(withoutKeptNames(result, source)) ?: CommentScript.guess(result) ?: return true
    return when (target.primary) {
        // Japanese all in kanji ("最高") reads as Chinese by its script
        CommentScript.JAPANESE -> guess == CommentScript.JAPANESE || guess == CommentScript.CHINESE
        CommentScript.CHINESE, CommentScript.KOREAN -> guess == target.primary
        // Latin or any other script, not Chinese, Japanese or Korean
        else -> guess == CommentScript.OTHER
    }
}

private val keptUrlRegex = Regex("""(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)

// [Chococornet (Tenro Aya)], 【...】, 「...」, 『...』, (...), （...）
private val bracketRegex = Regex("""\[[^\[\]\n]*]|【[^【】\n]*】|「[^「」\n]*」|『[^『』\n]*』|\([^()\n]*\)|（[^（）\n]*）""")

// Letters and digits; a word of Chinese runs to the next space or punctuation
private val tokenRegex = Regex("""[\p{L}\p{N}\p{M}'’]+""")

/** [result] without what it kept as it was in [source]: links, brackets, names and titles. */
internal fun withoutKeptNames(result: String, source: String): String {
    val sourceLower = source.lowercase()
    val sourceWords = tokenRegex.findAll(sourceLower).map { it.value }.toSet()
    var text = keptUrlRegex.replace(result, " ")
    // Brackets twice: a name in brackets in a title in brackets
    repeat(2) {
        text = bracketRegex.replace(text) { match ->
            val inside = match.value.substring(1, match.value.length - 1).trim()
            if (inside.isNotEmpty() && inside.lowercase() in sourceLower) " " else match.value
        }
    }
    return tokenRegex.replace(text) { match ->
        val token = match.value
        val kept = if (hasCjk(token)) token.lowercase() in sourceLower else token.lowercase() in sourceWords
        if (kept && isNameLike(token)) " " else token
    }
}

// Chinese and Japanese run on without spaces: a title kept is part of a longer run in the comment
private fun hasCjk(token: String) = token.codePoints().anyMatch {
    when (Character.UnicodeScript.of(it)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL -> true
        else -> false
    }
}

// "Sabbat", "CASE", "02", "杉崎霧香の一週間", "Doujins": a name or a title, not a word of a sentence ("please")
private fun isNameLike(token: String): Boolean {
    val first = token.codePointAt(0)
    if (Character.isUpperCase(first) || Character.isDigit(first)) return true
    var i = 0
    while (i < token.length) {
        val cp = token.codePointAt(i)
        i += Character.charCount(cp)
        when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> return true
            else -> Unit
        }
    }
    return false
}
