package com.hippo.ehviewer.translate

import androidx.compose.runtime.mutableStateMapOf
import androidx.core.text.parseAsHtml
import com.ehviewer.core.model.GalleryComment
import com.ehviewer.core.util.logcat
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.hippo.ehviewer.Settings
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splitties.init.appCtx

/** Fork: where the translation of a comment stands; [language] is the language of the comment, null if unknown. */
sealed interface CommentTranslation {
    val language: String?

    /** In the target language already, or without words: nothing to translate. */
    data class NotNeeded(override val language: String?) : CommentTranslation

    /** Being translated; streamed, [partial] is the translation so far. */
    data class Pending(override val language: String?, val partial: String? = null) : CommentTranslation

    data class Done(override val language: String?, val text: String) : CommentTranslation

    data class Failed(override val language: String?, val message: String) : CommentTranslation
}

/**
 * Fork: the translations of the comments, shared by the comments page and the comments on the gallery details. A comment
 * is checked here first: by its scripts ([CommentScript]) Chinese, Japanese and Korean, by ML Kit (offline) any other
 * language. Only the ones not in the target language go to the model ([CommentTranslateApi]), a page of them per
 * request. Translations are kept by the text of the comment in a small file in the cache, so a comment is translated
 * once, and again only once edited. Not in the Room database, whose schema stays as upstream's.
 */
object CommentTranslations {
    private const val TAG = "CommentTranslations"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = mutableStateMapOf<String, CommentTranslation>()
    private val lock = Any()

    // A page of comments per request, but not too long for the model to answer in one go
    private const val MAX_ITEMS_PER_REQUEST = 15
    private const val MAX_CHARS_PER_REQUEST = 4000
    private const val MAX_CACHED = 5000

    val config: TranslateConfig
        get() = TranslateConfig(
            apiUrl = Settings.commentTranslateApiUrl.value,
            apiKey = Settings.commentTranslateApiKey.value,
            model = Settings.commentTranslateModel.value,
            thinking = ThinkingMode.of(Settings.commentTranslateThinking.value),
            shortRequestChars = Settings.commentTranslateThinkingChars.value,
            stream = Settings.commentTranslateStream.value,
        )

    val target: TargetLanguage
        get() = TargetLanguage.of(Settings.commentTranslateTarget.value)

    /** The state of [comment] in the target language, null before it was looked at. Read in composition. */
    fun stateOf(comment: GalleryComment): CommentTranslation? = states[key(comment.comment, target)]

    /** Translates the [comments] not translated yet (and the failed ones with [retry]), in the background. */
    fun request(comments: List<GalleryComment>, retry: Boolean = false) {
        val config = config
        if (!config.isComplete || comments.isEmpty()) return
        val target = target
        scope.launch { translate(comments, config, target, retry) }
    }

    /**
     * Translates [comment] again, its kept translation dropped: thinking first unless turned off, a short comment
     * translated without it may have been answered rather than translated.
     */
    fun retranslate(comment: GalleryComment) {
        val config = config.let { if (it.thinking == ThinkingMode.OFF) it else it.copy(thinking = ThinkingMode.ON) }
        if (!config.isComplete) return
        val target = target
        val key = key(comment.comment, target)
        scope.launch {
            val cache = cache()
            synchronized(cache) { cache.remove(key) }
            saveCache()
            synchronized(lock) { states.remove(key) }
            translate(listOf(comment), config, target, retry = true)
        }
    }

    /** Failed comments are translated again on the next [request], after a change to the API settings. */
    fun forgetFailures() = synchronized(lock) {
        states.entries.filter { it.value is CommentTranslation.Failed }.map { it.key }.forEach(states::remove)
    }

    private suspend fun translate(comments: List<GalleryComment>, config: TranslateConfig, target: TargetLanguage, retry: Boolean) {
        val cache = cache()
        val todo = synchronized(lock) {
            comments.mapNotNull { comment ->
                val key = key(comment.comment, target)
                when (states[key]) {
                    null -> Unit
                    is CommentTranslation.Failed -> if (!retry) return@mapNotNull null
                    else -> return@mapNotNull null
                }
                // A list of the contents or of the series, or a raw and where to buy it, is left as it is, even if it
                // was translated by an older version
                val text = plainText(comment.comment)
                if (CommentListing.isListing(text) || CommentSeries.isSeriesList(text) || CommentLinks.isLinksOnly(text)) {
                    states[key] = CommentTranslation.NotNeeded(null)
                    return@mapNotNull null
                }
                // A kept reply that was no translation (from before the check) is asked for again
                val cached = synchronized(cache) { cache[key] }?.takeIf { isTranslationInto(it.t, text, target) }
                if (cached != null) {
                    states[key] = CommentTranslation.Done(cached.l, cached.t)
                    null
                } else {
                    states[key] = CommentTranslation.Pending(null)
                    key to comment
                }
            }
        }
        if (todo.isEmpty()) return
        try {
            val pending = todo.mapNotNull { (key, comment) ->
                val text = plainText(comment.comment)
                val segments = CommentSegments.split(text, target)
                // Detect only on non-target segments so target-language lines (e.g. Chinese) never mask foreign ones
                val toSend = ArrayList<Pair<Int, String>>()
                var detectedLanguage: String? = null
                segments.forEachIndexed { segIndex, seg ->
                    if (!seg.translate) return@forEachIndexed
                    val (needed, language) = detect(seg.text, target)
                    if (detectedLanguage == null && language != null) detectedLanguage = language
                    if (needed) toSend += segIndex to seg.text
                }
                if (toSend.isNotEmpty()) {
                    states[key] = CommentTranslation.Pending(detectedLanguage)
                    ToTranslate(key, text, segments, toSend, detectedLanguage)
                } else {
                    states[key] = CommentTranslation.NotNeeded(detectedLanguage ?: target.primary)
                    null
                }
            }
            chunks(pending).forEach { chunk -> translateChunk(chunk, config, target, cache) }
        } finally {
            // Cancelled or crashed half way: translated again next time
            synchronized(lock) { todo.forEach { (key, _) -> if (states[key] is CommentTranslation.Pending) states.remove(key) } }
        }
    }

    private class ToTranslate(
        val key: String,
        val text: String,
        val segments: List<CommentSegments.Segment>,
        val parts: List<Pair<Int, String>>,
        val language: String?,
    ) {
        val sendChars: Int get() = parts.sumOf { it.second.length }

        fun assemble(translatedByPartIndex: Map<Int, String>): String = buildString {
            val bySegment = HashMap<Int, String>()
            parts.forEachIndexed { partIndex, (segIndex, _) ->
                translatedByPartIndex[partIndex]?.let { bySegment[segIndex] = it }
            }
            segments.forEachIndexed { segIndex, seg ->
                append(bySegment[segIndex] ?: seg.text)
            }
        }
    }

    private fun chunks(pending: List<ToTranslate>): List<List<ToTranslate>> {
        val result = mutableListOf<List<ToTranslate>>()
        var current = mutableListOf<ToTranslate>()
        var itemCount = 0
        var chars = 0
        pending.forEach { item ->
            if (current.isNotEmpty() && (itemCount + item.parts.size > MAX_ITEMS_PER_REQUEST || chars + item.sendChars > MAX_CHARS_PER_REQUEST)) {
                result += current
                current = mutableListOf()
                itemCount = 0
                chars = 0
            }
            current += item
            itemCount += item.parts.size
            chars += item.sendChars
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private suspend fun translateChunk(
        chunk: List<ToTranslate>,
        config: TranslateConfig,
        target: TargetLanguage,
        cache: LinkedHashMap<String, CachedTranslation>,
        isRetry: Boolean = false,
    ) {
        // Flatten each comment's translatable segments into sequential API items, mapping item id -> (comment, partIndex)
        val items = ArrayList<CommentTranslateApi.Item>()
        val mapping = ArrayList<Pair<ToTranslate, Int>>()
        val itemIdsByComment = HashMap<ToTranslate, List<Int>>()
        chunk.forEach { comment ->
            val ids = ArrayList<Int>(comment.parts.size)
            comment.parts.forEachIndexed { partIndex, (_, partText) ->
                val id = items.size + 1
                items += CommentTranslateApi.Item(id, partText)
                mapping += comment to partIndex
                ids += id
            }
            itemIdsByComment[comment] = ids
        }
        val result = try {
            CommentTranslateApi.translate(config, items, target, strictRetry = isRetry) { partial ->
                // Streamed: reassemble each comment's segments as translations come in
                val grouped = HashMap<ToTranslate, MutableMap<Int, String>>()
                partial.forEach { (id, text) ->
                    val (comment, partIndex) = mapping.getOrNull(id - 1) ?: return@forEach
                    grouped.getOrPut(comment) { HashMap() }[partIndex] = text
                }
                grouped.forEach { (comment, byPart) ->
                    if (states[comment.key] is CommentTranslation.Pending) {
                        states[comment.key] = CommentTranslation.Pending(comment.language, comment.assemble(byPart))
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(TAG) { "Comment translation failed: $e" }
            val message = e.message ?: e.toString()
            chunk.forEach { states[it.key] = CommentTranslation.Failed(it.language, message) }
            return
        }
        val retryComments = ArrayList<ToTranslate>()
        var savedAny = false
        chunk.forEach { comment ->
            val ids = itemIdsByComment.getValue(comment)
            val translatedParts = HashMap<Int, String>()
            var error: String? = null
            comment.parts.forEachIndexed { partIndex, (_, sourcePart) ->
                val partText = result[ids[partIndex]]?.trim()
                when {
                    partText.isNullOrEmpty() -> error = "No translation in the reply"
                    !isTranslationInto(partText, sourcePart, target) -> {
                        logcat(TAG) { "Not a translation into ${target.code}: $partText" }
                        error = "Reply not in ${target.displayName}: ${partText.take(40)}"
                    }
                    else -> translatedParts[partIndex] = partText
                }
            }
            if (error != null) {
                if (!isRetry) {
                    // Short/non-thinking passes occasionally echo short phrases (e.g. "wake up wake up wake up")
                    // unchanged; retry those items once with thinking enabled (if AUTO) and a stricter prompt.
                    states[comment.key] = CommentTranslation.Pending(comment.language)
                    retryComments += comment
                } else {
                    states[comment.key] = CommentTranslation.Failed(comment.language, error!!)
                }
            } else {
                val assembled = comment.assemble(translatedParts)
                states[comment.key] = CommentTranslation.Done(comment.language, assembled)
                synchronized(cache) { cache[comment.key] = CachedTranslation(comment.language, assembled) }
                savedAny = true
            }
        }
        if (savedAny) saveCache()
        if (retryComments.isNotEmpty()) {
            val retryConfig = if (config.thinking == ThinkingMode.AUTO) config.copy(thinking = ThinkingMode.ON) else config
            translateChunk(retryComments, retryConfig, target, cache, isRetry = true)
        }
    }

    /** Whether [text] needs translating into [target], and its language. */
    private suspend fun detect(text: String, target: TargetLanguage): Pair<Boolean, String?> = when (val guess = CommentScript.guess(text)) {
        null -> false to null
        CommentScript.OTHER -> {
            // A romanized language ("ja-Latn") is no language the reader reads, it is translated;
            // non-CJK script text is never already in a CJK target even if ML Kit mislabels a short phrase.
            val language = identify(text)
            val needed = target.primary in setOf(CommentScript.CHINESE, CommentScript.JAPANESE, CommentScript.KOREAN) ||
                language != target.primary
            needed to language
        }
        else -> (guess != target.primary) to guess
    }

    private val identifier by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build())
    }

    /** The language of [text] by ML Kit, null if unsure or if ML Kit fails. */
    private suspend fun identify(text: String): String? = runCatching {
        suspendCancellableCoroutine { cont ->
            identifier.identifyLanguage(text)
                .addOnSuccessListener { cont.resume(it.takeIf { code -> code != "und" }) }
                .addOnFailureListener { cont.resume(null) }
        }
    }.getOrNull()

    /** A language code as its name in the language of the app: "ko" → 韩语. */
    fun languageName(code: String): String {
        val locale = Locale.forLanguageTag(code)
        return locale.getDisplayLanguage(Locale.getDefault()).ifEmpty { code }
    }

    /** The text of a comment without its HTML; images left out. */
    fun plainText(html: String) = html.parseAsHtml().toString().replace('￼', ' ').trim()

    private fun key(html: String, target: TargetLanguage): String {
        val digest = MessageDigest.getInstance("SHA-1").digest((target.code + "\u0000" + html).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // The cache of the translations, by key, oldest first
    @Serializable
    private class CachedTranslation(val l: String? = null, val t: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val cacheFile: File
        get() = File(appCtx.cacheDir, "comment_translations.json")
    private var cacheMap: LinkedHashMap<String, CachedTranslation>? = null

    private fun cache(): LinkedHashMap<String, CachedTranslation> = synchronized(this) {
        cacheMap ?: run {
            val loaded = runCatching { json.decodeFromString<Map<String, CachedTranslation>>(cacheFile.readText()) }.getOrNull().orEmpty()
            LinkedHashMap(loaded).also { cacheMap = it }
        }
    }

    private fun saveCache() {
        val cache = cache()
        val text = synchronized(cache) {
            while (cache.size > MAX_CACHED) cache.remove(cache.keys.first())
            json.encodeToString<Map<String, CachedTranslation>>(cache)
        }
        runCatching { cacheFile.writeText(text) }.onFailure { logcat(TAG) { "Saving comment translations failed: $it" } }
    }

    /** How many translations are kept, and the size of their file in bytes. */
    fun cacheInfo(): Pair<Int, Long> = cache().let { synchronized(it) { it.size } } to cacheFile.length()

    fun clearCache() {
        val cache = cache()
        synchronized(cache) { cache.clear() }
        cacheFile.delete()
        synchronized(lock) { states.clear() }
    }
}
