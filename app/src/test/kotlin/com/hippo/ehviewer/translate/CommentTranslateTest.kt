package com.hippo.ehviewer.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommentTranslateTest {
    @Test
    fun scriptGuess() {
        assertEquals(CommentScript.CHINESE, CommentScript.guess("翻译组辛苦了，嵌字很用心 👍"))
        assertEquals(CommentScript.CHINESE, CommentScript.guess("求 Kemono Friends 的汉化"))
        assertEquals(CommentScript.CHINESE, CommentScript.guess("我の老婆"))
        assertEquals(CommentScript.CHINESE, CommentScript.guess("翻譯組辛苦了"))
        assertEquals(CommentScript.JAPANESE, CommentScript.guess("やっと来た、2話目の展開が神すぎる"))
        assertEquals(CommentScript.KOREAN, CommentScript.guess("한글판도 올렸습니다. 항상 응원해 주셔서 감사합니다!"))
        assertEquals(CommentScript.KOREAN, CommentScript.guess("ㅋㅋㅋㅋ"))
        assertEquals(CommentScript.OTHER, CommentScript.guess("Thought it was Kronii from the thumbnail"))
        assertEquals(CommentScript.OTHER, CommentScript.guess("这本 is the best of the best"))
        assertEquals(CommentScript.CHINESE, CommentScript.guess("汉化版在这里 https://e-hentai.org/g/123/abcdef/"))
        assertNull(CommentScript.guess("https://e-hentai.org/g/123/abcdef/"))
        assertNull(CommentScript.guess("+1 👍 2333"))
    }

    @Test
    fun endpoint() {
        assertEquals("https://api.deepseek.com/v1/chat/completions", TranslateConfig("https://api.deepseek.com/v1/", "", "m").endpoint)
        assertEquals("https://x.com/v1/chat/completions", TranslateConfig("https://x.com/v1/chat/completions", "", "m").endpoint)
        assertEquals("https://x.com/v1/models", TranslateConfig("https://x.com/v1/chat/completions", "", "").modelsEndpoint)
        assertEquals("https://api.deepseek.com/v1/models", TranslateConfig("https://api.deepseek.com/v1/", "", "").modelsEndpoint)
    }

    @Test
    fun parseModels() {
        val body = """{"object":"list","data":[{"id":"deepseek-reasoner","object":"model"},{"id":"deepseek-chat","object":"model"}]}"""
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), CommentTranslateApi.parseModels(body))
        assertEquals(listOf("a", "b"), CommentTranslateApi.parseModels("""{"models":[{"name":"b"},{"name":"a"}]}"""))
        assertNull(CommentTranslateApi.parseModels("""{"data":[]}"""))
        assertNull(CommentTranslateApi.parseModels("<html>"))
    }

    @Test
    fun parseTranslations() {
        val content = "<think>hmm</think>\n```json\n[{\"id\":1,\"text\":\"你好\"},{\"id\":\"2\",\"text\":\"再见\"}]\n```"
        assertEquals(mapOf(1 to "你好", 2 to "再见"), CommentTranslateApi.parseTranslations(content))
        assertNull(CommentTranslateApi.parseTranslations("你好"))
        assertEquals("你好", CommentTranslateApi.cleanContent("```\n你好\n```"))
    }

    @Test
    fun replyAndError() {
        val reply = """{"choices":[{"index":0,"message":{"role":"assistant","content":"[{\"id\":1,\"text\":\"好\"}]"}}]}"""
        assertEquals("""[{"id":1,"text":"好"}]""", CommentTranslateApi.replyContent(reply))
        assertEquals("Invalid API key", CommentTranslateApi.errorMessage("""{"error":{"message":"Invalid API key","type":"auth"}}"""))
        assertEquals("bad", CommentTranslateApi.errorMessage("""{"error":"bad"}"""))
        assertNull(CommentTranslateApi.errorMessage("<html>"))
    }
}
