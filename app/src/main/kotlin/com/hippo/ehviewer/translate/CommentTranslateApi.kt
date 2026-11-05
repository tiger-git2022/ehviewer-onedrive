package com.hippo.ehviewer.translate

import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.client.executeSafely
import com.hippo.ehviewer.util.bodyAsUtf8Text
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Fork: where and with what the comments are translated, from Settings → EH → Comment translation. */
data class TranslateConfig(
    val apiUrl: String,
    val apiKey: String,
    val model: String,
    val thinking: ThinkingMode = ThinkingMode.AUTO,
    val stream: Boolean = false,
) {
    val isComplete: Boolean
        get() = apiUrl.isNotBlank() && model.isNotBlank()

    /** The chat completions endpoint: the address as given if it names one, else the base URL of the API + it. */
    val endpoint: String
        get() {
            val url = apiUrl.trim().trimEnd('/')
            return if (url.endsWith("/chat/completions")) url else "$url/chat/completions"
        }

    /** The list of the models, next to the chat completions endpoint. */
    val modelsEndpoint: String
        get() = apiUrl.trim().trimEnd('/').removeSuffix("/chat/completions") + "/models"
}

class TranslateException(message: String) : Exception(message)

/** Fork: whether the model thinks before it translates. Thinking helps with a long page, it only slows a short one. */
enum class ThinkingMode(val code: String) {
    /** Thinks on a long request only: short ones go without, see [CommentTranslateApi.SHORT_REQUEST_CHARS]. */
    AUTO("auto"),

    /** As the API does by default: no parameter sent. */
    ON("on"),
    OFF("off"),
    ;

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code } ?: AUTO
    }
}

/**
 * Fork: translates comments with a large language model behind an OpenAI compatible chat completions API (DeepSeek,
 * Qwen, Kimi, OpenAI, Gemini, OpenRouter, a local Ollama...). The comments of a page go in one request as a JSON array,
 * the reply is the same array translated.
 */
object CommentTranslateApi {
    private val json = Json { ignoreUnknownKeys = true }

    // A model thinks before it answers: a page of comments takes a while
    private const val TIMEOUT_MILLIS = 120_000L

    /** Up to this many characters of comments, a request is short: in [ThinkingMode.AUTO] the model does not think. */
    const val SHORT_REQUEST_CHARS = 500

    // The APIs that answered an error to the parameters turning thinking off, asked without them since
    private val noThinkingParamsRejected = mutableSetOf<String>()

    class Item(val id: Int, val text: String)

    /** Whether thinking is turned off for a request of [items]. */
    fun thinkingOff(mode: ThinkingMode, items: List<Item>) = when (mode) {
        ThinkingMode.ON -> false
        ThinkingMode.OFF -> true
        ThinkingMode.AUTO -> items.sumOf { it.text.length } <= SHORT_REQUEST_CHARS
    }

    /** The translations of [items] by id; an item missing from the reply is missing here. */
    suspend fun translate(config: TranslateConfig, items: List<Item>, target: TargetLanguage): Map<Int, String> {
        if (!config.isComplete) throw TranslateException("API not configured")
        val content = request(config, systemPrompt(target), userContent(items), thinkingOff(config.thinking, items))
        val parsed = parseTranslations(content)
        if (parsed != null) return parsed
        // Not the JSON asked for: a single comment is its own translation, a page is asked again comment by comment
        return if (items.size == 1) {
            mapOf(items[0].id to cleanContent(content))
        } else {
            items.associate { item -> item.id to translate(config, listOf(item), target).getValue(item.id) }
        }
    }

    /** The ids of the models the API offers (GET /models), sorted; for Settings to pick one. */
    suspend fun listModels(config: TranslateConfig): List<String> {
        if (config.apiUrl.isBlank()) throw TranslateException("API not configured")
        return ktorClient.prepareGet(config.modelsEndpoint) {
            timeout { requestTimeoutMillis = 30_000 }
            if (config.apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${config.apiKey.trim()}")
            header(HttpHeaders.Accept, "application/json")
        }.executeSafely { response ->
            val text = response.bodyAsUtf8Text()
            if (!response.status.isSuccess()) {
                throw TranslateException("HTTP ${response.status.value}" + (errorMessage(text)?.let { ": $it" } ?: ""))
            }
            parseModels(text) ?: throw TranslateException(errorMessage(text) ?: "No models in the reply")
        }
    }

    /** The model ids of a /models reply: {"data":[{"id":...}]}, or {"models":[...]} as some servers answer. */
    fun parseModels(body: String): List<String>? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val list = (root["data"] ?: root["models"])!!.jsonArray
        list.mapNotNull { element ->
            when (element) {
                is JsonObject -> (element["id"] ?: element["name"])?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> element.contentOrNull
                else -> null
            }
        }.filter { it.isNotBlank() }.distinct().sorted()
    }.getOrNull()?.ifEmpty { null }

    private suspend fun request(config: TranslateConfig, system: String, user: String, noThinking: Boolean): String {
        val key = config.endpoint + " " + config.model.trim()
        val sendNoThinking = noThinking && synchronized(noThinkingParamsRejected) { key !in noThinkingParamsRejected }
        return try {
            post(config, requestBody(config, system, user, sendNoThinking))
        } catch (e: HttpException) {
            // An API that knows none of the parameters may refuse them all: ask again as it is, and from now on
            if (!sendNoThinking || e.status !in 400..422) throw e.toTranslateException()
            synchronized(noThinkingParamsRejected) { noThinkingParamsRejected += key }
            try {
                post(config, requestBody(config, system, user, false))
            } catch (e: HttpException) {
                throw e.toTranslateException()
            }
        }
    }

    /** The chat completions request; [noThinking] adds the parameters turning thinking off, as the APIs name them. */
    fun requestBody(config: TranslateConfig, system: String, user: String, noThinking: Boolean) = buildJsonObject {
        put("model", config.model.trim())
        put("temperature", 0.3)
        put("stream", config.stream)
        if (noThinking) {
            // DeepSeek and the relays built on it
            putJsonObject("thinking") { put("type", "disabled") }
            // Qwen
            put("enable_thinking", false)
            // OpenAI, Gemini
            put("reasoning_effort", "none")
        }
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", system)
            }
            addJsonObject {
                put("role", "user")
                put("content", user)
            }
        }
    }

    private class HttpException(val status: Int, val body: String) : Exception() {
        fun toTranslateException() = TranslateException("HTTP $status" + (errorMessage(body)?.let { ": $it" } ?: ""))
    }

    private suspend fun post(config: TranslateConfig, body: JsonObject): String = ktorClient.preparePost(config.endpoint) {
        timeout { requestTimeoutMillis = TIMEOUT_MILLIS }
        if (config.apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer ${config.apiKey.trim()}")
        header(HttpHeaders.Accept, if (config.stream) "text/event-stream, application/json" else "application/json")
        contentType(ContentType.Application.Json)
        setBody(body.toString())
    }.executeSafely { response ->
        val text = response.bodyAsUtf8Text()
        if (!response.status.isSuccess()) throw HttpException(response.status.value, text)
        replyContent(text) ?: throw TranslateException(errorMessage(text) ?: streamError(text) ?: "Empty reply")
    }

    fun systemPrompt(target: TargetLanguage) = """
        You translate comments left on an online gallery of manga, doujinshi and illustrations into ${target.promptName}.
        The input is a JSON array of objects, each with an "id" and a "text". Translate every text.
        Keep URLs, user names, gallery titles in brackets, numbers and emoji as they are. Keep the tone, the slang and the line breaks. Add no notes.
        Reply with only a JSON array of objects with the same ids and the translated texts, like [{"id":1,"text":"..."}].
    """.trimIndent()

    fun userContent(items: List<Item>) = buildJsonArray {
        items.forEach { item ->
            addJsonObject {
                put("id", item.id)
                put("text", item.text)
            }
        }
    }.toString()

    /** The text of the first choice of a chat completions reply, streamed (server-sent events) or not. */
    fun replyContent(body: String): String? = if (isEventStream(body)) streamedContent(body) else runCatching {
        val choice = json.parseToJsonElement(body).jsonObject["choices"]!!.jsonArray[0].jsonObject
        choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun isEventStream(body: String) = body.trimStart().startsWith("data:") || body.trimStart().startsWith("event:") || body.trimStart().startsWith(":")

    private fun streamEvents(body: String) = body.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("data:") }
        .map { it.removePrefix("data:").trim() }
        .filter { it.isNotEmpty() && it != "[DONE]" }
        .mapNotNull { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    /** The answer of a streamed reply: the content of the deltas of the first choice, the thoughts left out. */
    fun streamedContent(body: String): String? = buildString {
        streamEvents(body).forEach { event ->
            val choice = (event["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@forEach
            val delta = (choice["delta"] ?: choice["message"]) as? JsonObject ?: return@forEach
            (delta["content"] as? JsonPrimitive)?.contentOrNull?.let(::append)
        }
    }.takeIf { it.isNotBlank() }

    /** The message of an error sent in a streamed reply. */
    private fun streamError(body: String): String? = if (isEventStream(body)) {
        streamEvents(body).firstNotNullOfOrNull { event -> event["error"]?.let { errorMessage(event.toString()) } }
    } else {
        null
    }

    /** The message of an error reply: {"error":{"message":...}} or {"error":"..."} or {"message":...}. */
    fun errorMessage(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        when (val error = root["error"]) {
            is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> error.contentOrNull
            else -> root["message"]?.jsonPrimitive?.contentOrNull
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }?.take(200)

    // The thoughts some local models write before their answer
    private val thinkRegex = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    private val fenceRegex = Regex("^```[a-zA-Z]*\\s*|\\s*```$")

    /** The reply without the thoughts and the Markdown code fence around it. */
    fun cleanContent(content: String) = fenceRegex.replace(thinkRegex.replace(content, "").trim(), "").trim()

    /** The translations by id from the JSON array of the reply, null if it holds none. */
    fun parseTranslations(content: String): Map<Int, String>? {
        val cleaned = cleanContent(content)
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val array = runCatching { json.parseToJsonElement(cleaned.substring(start, end + 1)) as? JsonArray }.getOrNull() ?: return null
        val result = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: return@mapNotNull null
            val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            id to text
        }.toMap()
        return result.ifEmpty { null }
    }
}
