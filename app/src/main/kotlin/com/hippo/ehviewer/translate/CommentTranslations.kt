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

    data class Pending(override val language: String?) : CommentTranslation

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
                val cached = synchronized(cache) { cache[key] }
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
                val (needed, language) = detect(text, target)
                if (needed) {
                    states[key] = CommentTranslation.Pending(language)
                    ToTranslate(key, text, language)
                } else {
                    states[key] = CommentTranslation.NotNeeded(language)
                    null
                }
            }
            chunks(pending).forEach { chunk -> translateChunk(chunk, config, target, cache) }
        } finally {
            // Cancelled or crashed half way: translated again next time
            synchronized(lock) { todo.forEach { (key, _) -> if (states[key] is CommentTranslation.Pending) states.remove(key) } }
        }
    }

    private class ToTranslate(val key: String, val text: String, val language: String?)

    private fun chunks(pending: List<ToTranslate>): List<List<ToTranslate>> {
        val result = mutableListOf<List<ToTranslate>>()
        var current = mutableListOf<ToTranslate>()
        var chars = 0
        pending.forEach { item ->
            if (current.isNotEmpty() && (current.size >= MAX_ITEMS_PER_REQUEST || chars + item.text.length > MAX_CHARS_PER_REQUEST)) {
                result += current
                current = mutableListOf()
                chars = 0
            }
            current += item
            chars += item.text.length
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private suspend fun translateChunk(chunk: List<ToTranslate>, config: TranslateConfig, target: TargetLanguage, cache: LinkedHashMap<String, CachedTranslation>) {
        val items = chunk.mapIndexed { index, item -> CommentTranslateApi.Item(index + 1, item.text) }
        val result = try {
            CommentTranslateApi.translate(config, items, target)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(TAG) { "Comment translation failed: $e" }
            val message = e.message ?: e.toString()
            chunk.forEach { states[it.key] = CommentTranslation.Failed(it.language, message) }
            return
        }
        chunk.forEachIndexed { index, item ->
            val text = result[index + 1]?.trim()
            if (text.isNullOrEmpty()) {
                states[item.key] = CommentTranslation.Failed(item.language, "No translation in the reply")
            } else {
                states[item.key] = CommentTranslation.Done(item.language, text)
                synchronized(cache) { cache[item.key] = CachedTranslation(item.language, text) }
            }
        }
        saveCache()
    }

    /** Whether [text] needs translating into [target], and its language. */
    private suspend fun detect(text: String, target: TargetLanguage): Pair<Boolean, String?> = when (val guess = CommentScript.guess(text)) {
        null -> false to null
        CommentScript.OTHER -> {
            // A romanized language ("ja-Latn") is no language the reader reads, it is translated
            val language = identify(text)
            (language != target.primary) to language
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
