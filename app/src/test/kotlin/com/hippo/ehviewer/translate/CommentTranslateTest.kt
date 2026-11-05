package com.hippo.ehviewer.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun listing() {
        val contents = """
            COMIC Masyo 2026-02
            コミックマショウ 2026年2月号

            ILLUSTRATION Urushihara Satoshi / うるし原智志

            ★Contents★
            003 - ジョン・K・ペー太 / John K. Pe-ta ... どうしようが止まらない
            007 - 朱シオ / Aka Shio .............................. 不器用巫女の恋愛析願Q
            029 - 戸村水仙 / Tomura Suisen ................. ダメギャルと恋の授業
            107 - ジロウ / Jirou ..................................... 熟女好きな俺の新しい扉
            131 - あ~る・こが / R-Koga ....................... 双子ミニマム母娘
            239 - ジョン・K・ペー太 / John K. Pe-ta ... 歴戦の女戦士と僕の旅 10話 chapter 10!
        """.trimIndent()
        assertTrue(CommentListing.isListing(contents))
        // Pages alone, without dots
        assertTrue(CommentListing.isListing("Contents\n001 Foo / Bar\n021 Baz / Qux\n045 Quux\n069 Corge\n091 Grault"))
        // Pages and authors under a heading, after the titles and the cover artist
        val anthology = """
            Sanran Anthology Comics Vol. 3
            産卵 アンソロジーコミックス Vol.3 デジタル版 [中文翻译]

            Cover artist - Matsunami Rumi

            Contents
            005 - Matsunami Rumi
            025 - Aya Hinase
            045 - kinntarou
        """.trimIndent()
        assertTrue(CommentListing.isListing(anthology))
        // Three points without a heading are not a list of contents
        assertFalse(CommentListing.isListing("Title\n5 - good\n25 - meh\n45 - bad"))
        // Dots alone, without pages
        assertTrue(CommentListing.isListing("Contents\nAka Shio ........ 不器用巫女\nTomura ........ ダメギャル\nJirou ........ 熟女好き\nR-Koga ……… 双子"))
        // Points of a review are translated
        assertFalse(CommentListing.isListing("Thoughts:\n1. The art is great\n2. The story drags\n3. Too short\n4. Still worth it"))
        // Ellipses of a Japanese comment are punctuation, not leaders
        assertFalse(CommentListing.isListing("そうか……\nでも……\nうーん……\nまあ……好きだけど……"))
        assertFalse(CommentListing.isListing("한글판도 올렸습니다.\n항상 응원해 주셔서 감사합니다!"))
        assertFalse(CommentListing.isListing("Page 12 is the best...\nAnd 30 too\nLove it\nThanks for uploading\nMore please"))
    }

    @Test
    fun linksOnly() {
        assertTrue(CommentLinks.isLinksOnly("RAW: https://exhentai.org/g/4292915/252a0c089c/"))
        assertTrue(CommentLinks.isLinksOnly("raw\nexhentai.org/g/4292915/252a0c089c"))
        assertTrue(CommentLinks.isLinksOnly("Raw"))
        assertTrue(CommentLinks.isLinksOnly("Raw: https://e-hentai.org/g/1/a/\nBuy the original at DLsite: https://www.dlsite.com/maniax/work/=/product_id/RJ01234567.html"))
        assertTrue(CommentLinks.isLinksOnly("Support the artist: https://www.melonbooks.co.jp/detail/detail.php?product_id=123"))
        assertTrue(CommentLinks.isLinksOnly("原作 / Raw: https://e-hentai.org/g/1/a/\nDL版はこちら https://www.dmm.co.jp/dc/doujin/-/detail/=/cid=d_123/"))
        assertTrue(CommentLinks.isLinksOnly("Japanese raw (uncensored) - https://e-hentai.org/g/1/a/"))
        // Something said besides the links
        assertFalse(CommentLinks.isLinksOnly("Thanks for the translation! Raw: https://e-hentai.org/g/1/a/"))
        assertFalse(CommentLinks.isLinksOnly("The raw is uncensored, why is this one censored https://e-hentai.org/g/1/a/"))
        assertFalse(CommentLinks.isLinksOnly("Is this the original? https://e-hentai.org/g/1/a/"))
        assertFalse(CommentLinks.isLinksOnly("この作品の原作はこちら、続編も出てほしい https://e-hentai.org/g/1/a/"))
        // No link and no "raw": a short remark, translated
        assertFalse(CommentLinks.isLinksOnly("Original"))
        assertFalse(CommentLinks.isLinksOnly("Source?"))
        assertFalse(CommentLinks.isLinksOnly("Drawn so well"))
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

    @Test
    fun streamedReply() {
        // As the relays send it: the thoughts in reasoning_content, the answer in content, then [DONE]
        val body = """
            data: {"choices":[{"delta":{"role":"assistant"},"index":0}]}

            data: {"choices":[{"delta":{"reasoning_content":"We need"},"index":0}]}

            data: {"choices":[{"delta":{"content":"早上"},"index":0}]}

            data: {"choices":[{"delta":{"content":"好"},"index":0}]}

            data: [DONE]
        """.trimIndent()
        assertEquals("早上好", CommentTranslateApi.replyContent(body))
        assertNull(CommentTranslateApi.replyContent("data: [DONE]"))
    }

    @Test
    fun thinking() {
        val short = listOf(CommentTranslateApi.Item(1, "Thanks!"))
        val long = listOf(CommentTranslateApi.Item(1, "a".repeat(CommentTranslateApi.SHORT_REQUEST_CHARS + 1)))
        assertTrue(CommentTranslateApi.thinkingOff(ThinkingMode.AUTO, short))
        assertFalse(CommentTranslateApi.thinkingOff(ThinkingMode.AUTO, long))
        assertFalse(CommentTranslateApi.thinkingOff(ThinkingMode.ON, short))
        assertTrue(CommentTranslateApi.thinkingOff(ThinkingMode.OFF, long))
        assertEquals(ThinkingMode.AUTO, ThinkingMode.of("?"))

        val config = TranslateConfig("http://x/v1", "", "m", stream = true)
        val off = CommentTranslateApi.requestBody(config, "s", "u", noThinking = true).toString()
        assertTrue("\"thinking\":{\"type\":\"disabled\"}" in off)
        assertTrue("\"reasoning_effort\":\"none\"" in off)
        assertTrue("\"stream\":true" in off)
        assertFalse("thinking" in CommentTranslateApi.requestBody(config, "s", "u", noThinking = false).toString())
    }

    @Test
    fun partialTranslations() {
        val content = """[{"id":1,"text":"第一条\n完了"},{"id": 2, "text": "第二条说到一半\"引"""
        assertEquals(mapOf(1 to "第一条\n完了", 2 to "第二条说到一半\"引"), CommentTranslateApi.partialTranslations(content))
        assertEquals(mapOf(1 to "好"), CommentTranslateApi.partialTranslations("""[{"id":"1","text":"好\u"""))
        assertEquals(emptyMap(), CommentTranslateApi.partialTranslations("""<think>[{"id":1,"text":"x"""))
        assertEquals(mapOf(1 to "y"), CommentTranslateApi.partialTranslations("""<think>hm</think>[{"id":1,"text":"y"""))
        assertEquals(emptyMap(), CommentTranslateApi.partialTranslations("""[{"id":1,"text":"""))
        // The text before the id, as JSON allows
        assertEquals(
            mapOf(1 to "一 }", 2 to "二"),
            CommentTranslateApi.partialTranslations("""[{"text":"一 }","id":1},{"text":"二","id":"2"},{"text":"三还没有 id"""),
        )
    }

    @Test
    fun translationCheck() {
        val zh = TargetLanguage.SimplifiedChinese
        val source = "Page 9 was water damaged, work with what is legible."
        // The model answered the comment instead of translating it
        assertFalse(isTranslationInto("Got it — noted. I'll work with what's legible.", source, zh))
        assertTrue(isTranslationInto("第 9 页被水泡坏了，按能看清的来。", source, zh))
        assertTrue(isTranslationInto("Kronii 太可爱了", "Kronii is so cute", zh))
        assertTrue(isTranslationInto("Kronii", "Kronii", zh))
        assertTrue(isTranslationInto("+1 👍", "+1 👍 lol", zh))
        assertFalse(isTranslationInto("한글판도 올렸습니다", "한글판도 올렸습니다!", zh))
        assertTrue(isTranslationInto("I uploaded the Korean version too", "한글판도 올렸습니다", TargetLanguage.English))
        assertFalse(isTranslationInto("我也上传了韩文版", "한글판도 올렸습니다", TargetLanguage.English))
    }
}
