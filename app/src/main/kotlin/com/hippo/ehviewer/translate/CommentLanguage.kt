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
    // "003 - ", "p.12 ", "12. ", "P45 "; not a decimal/date like "2026.11.14" or "1.5"
    private val pageRegex = Regex("""^(?:pp?\.?\s*)?(\d{1,4})\s*(?:[-–—~～:：)）|]|\.(?!\d)|\s)\s*(\S.*)$""", RegexOption.IGNORE_CASE)
    private val coverRegex = Regex("""^(?:cover|表紙|カバー)\s*[-–—:：|]+\s*\S""", RegexOption.IGNORE_CASE)

    // Dates like "2026.11.14", "2026-11-14", "11/14/2026" are never contents entries
    private val dateRegex = Regex("""^(?:\d{4}[.\-/]\d{1,2}[.\-/]\d{1,2}|\d{1,2}[.\-/]\d{1,2}[.\-/]\d{2,4})(?:\s|$)""")

    // A line whose content is just a URL ("1: https://exhentai.org/g/...") is a series link list, not a page TOC
    private val urlOnlyRegex = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE)

    // A long run of dots or the like between a name and a title, words on both sides; "そうか……" or "でも……好き" is
    // only punctuation
    private val leaderRegex = Regex("""\S\s*(?:\.{5,}|…{3,}|・{5,}|·{5,})\s*\S""")

    // A line ending in leader dots, with the title on the line below ("628 - Artist / 作者 ......\n『Title』")
    private val trailingLeaderRegex = Regex("""\S\s*(?:\.{5,}|…{3,}|・{5,}|·{5,})\s*$""")

    // The heading of the list: "Contents", "★Contents★", "目次", "収録作品:"
    private val headingRegex = Regex(
        """^[\p{P}\p{S}\s]*(?:table of contents|contents|目次|もくじ|目录|目錄|收录|收錄|収録作品|収録)[\p{P}\p{S}\s]*$""",
        RegexOption.IGNORE_CASE,
    )

    private const val MIN_ENTRIES = 4

    // Under a heading, a short list of three is enough
    private const val MIN_ENTRIES_UNDER_HEADING = 3

    private fun pageOf(line: String): Int? {
        if (dateRegex.containsMatchIn(line)) return null
        val match = pageRegex.find(line) ?: return null
        if (urlOnlyRegex.matches(match.groupValues[2].trim())) return null
        return match.groupValues[1].toInt()
    }

    fun isListing(text: String): Boolean {
        val all = text.lines().map(String::trim).filter(String::isNotEmpty)
        // With a heading, the title, the cover artist and the like above it are not counted
        val heading = all.indexOfFirst { headingRegex.matches(it) }
        val lines = if (heading >= 0) all.drop(heading + 1) else all
        val minEntries = if (heading >= 0) MIN_ENTRIES_UNDER_HEADING else MIN_ENTRIES
        if (lines.size < minEntries) return false
        val pages = mutableListOf<Int>()
        var entries = 0
        var leaders = 0
        var logicalLines = 0
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            logicalLines++
            val page = pageOf(line)
            val isCover = page == null && coverRegex.containsMatchIn(line)
            val hasTrailingLeader = trailingLeaderRegex.containsMatchIn(line)
            val inlineLeader = !hasTrailingLeader && leaderRegex.containsMatchIn(line)
            val splitLeader = hasTrailingLeader &&
                i + 1 < lines.size && pageOf(lines[i + 1]) == null && !headingRegex.matches(lines[i + 1])
            if (page != null) pages += page
            if (inlineLeader || splitLeader) leaders++
            if (page != null || isCover || inlineLeader || splitLeader) entries++
            i += if (splitLeader) 2 else 1
        }
        // A header and credits around the list are fine, but most lines are entries
        if (entries < minEntries || entries * 2 < logicalLines) return false
        // Pages go up, allowing section resets (e.g. Color Comic at the back listed before Comic), and further than
        // the points of a comment would
        val drops = pages.zipWithNext().count { (a, b) -> b < a }
        val ordered = drops == 0 || (drops <= 2 && pages.size >= 5 && drops * 3 < pages.size)
        val paged = pages.size >= minEntries && ordered && pages.max() >= 20
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
 *     Chinese digital, translated by 萌意永久润色机翻, You are here!
 *
 * Told by the format of its lines, not by the words of a heading: at least three entries of one kind, a short title with
 * a link, a title numbered 00, 01, 02 on, or a language and who translated it, in two languages or more. The other lines
 * are short headings ("Series part : 催眠用務員"), and a question among them or a longer remark and it is translated.
 */
object CommentSeries {
    private val urlRegex = Regex("""(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)

    // "00 - Sabbat", "01. Falling", "02: Infection"
    private val numberedRegex = Regex("""^(\d{1,3})\s*[-–—.:：)）]\s*\S""")

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

    private const val MIN_ENTRIES = 3
    private const val MAX_TITLE_WORDS = 10
    private const val MAX_TITLE_CHARS = 80

    // A heading or a title between the entries: "Other Language or Version", "Series part : 催眠用務員CASE.02..."
    private const val MAX_OTHER_LINE_CHARS = 60

    private fun words(text: String) = text.split(Regex("""[^\p{L}\p{N}]+""")).filter(String::isNotEmpty)

    private fun isQuestion(text: String) = '?' in text || '？' in text

    private fun isShortTitle(text: String) = !isQuestion(text) && text.trim().length <= MAX_TITLE_CHARS && words(text).size <= MAX_TITLE_WORDS

    // "00 - Sabbat - https://exhentai.org/g/1483497/3519d9a634/", "Part 2: https://..."
    private fun isLinkEntry(line: String) = urlRegex.containsMatchIn(line) && isShortTitle(urlRegex.replace(line, " "))

    // "01 - Falling": the number of a part, written with two digits or more; "1. The art is great" is a point of a review
    private fun numberOf(line: String): Int? {
        val digits = numberedRegex.find(line)?.groupValues?.get(1) ?: return null
        if (digits.length < 2 || !isShortTitle(urlRegex.replace(line, " "))) return null
        return digits.toInt()
    }

    // The language of "Japanese digital", "Chinese digital, translated by 萌意永久润色机翻, You are here!", "" for a
    // "digital" alone under it, or null for no such line
    private fun versionOf(line: String): String? {
        if (isQuestion(line)) return null
        // Who translated it is a name, whatever it is
        val words = words(line.split(translatedByRegex, limit = 2)[0]).map { it.lowercase() }
        if (words.isEmpty()) return null
        val language = words[0].takeIf { it in languages }
        val rest = if (language != null) words.drop(1) else words
        if (language == null && rest.isEmpty()) return null
        return if (rest.all { it in versionWords }) language.orEmpty() else null
    }

    fun isSeriesList(text: String): Boolean {
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.size < MIN_ENTRIES) return false
        var links = 0
        val numbers = mutableListOf<Int>()
        var versions = 0
        val versionLanguages = mutableSetOf<String>()
        var others = 0
        lines.forEach { line ->
            val number = numberOf(line)
            val version = versionOf(line)
            when {
                isLinkEntry(line) || number != null -> {
                    if (isLinkEntry(line)) links++
                    if (number != null) numbers += number
                }
                version != null -> {
                    versions++
                    if (version.isNotEmpty()) versionLanguages += version
                }
                // A heading or a title between them; a question or a remark and the comment says something
                isQuestion(line) || line.length > MAX_OTHER_LINE_CHARS -> return false
                else -> others++
            }
        }
        // Parts numbered one after another: 00, 01, 02
        val numbered = numbers.size >= MIN_ENTRIES && numbers.zipWithNext().all { (a, b) -> b == a + 1 }
        val versioned = versions >= MIN_ENTRIES && versionLanguages.size >= 2
        if (links < MIN_ENTRIES && !numbered && !versioned) return false
        val entries = lines.size - others
        return entries >= others * 2
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
