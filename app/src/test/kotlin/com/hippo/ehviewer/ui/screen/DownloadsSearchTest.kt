package com.hippo.ehviewer.ui.screen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadsSearchTest {
    private val tags = listOf("language:chinese", "language:translated", "female:big breasts", "female:anal intercourse", "_male:glasses")

    private fun found(keyword: String) = DownloadsSearch.parse(keyword).matches("[Circle] Kanojo no Himitsu", "彼女の秘密", tags)

    @Test
    fun emptyKeywordFindsAll() {
        assertTrue(found(""))
        assertTrue(found("   "))
        assertTrue(DownloadsSearch.parse("").matches(null, null, null))
    }

    @Test
    fun titleAndRawTagAsBefore() {
        assertTrue(found("kanojo"))
        assertTrue(found("秘密"))
        assertTrue(found("big breasts"))
        assertTrue(found("female:big"))
        assertTrue(found("glasses"))
        assertFalse(found("tentacles"))
        assertFalse(found("巨乳"))
    }

    @Test
    fun namespaces() {
        assertTrue(found("f:big breasts"))
        assertTrue(found("female:big"))
        assertTrue(found("l:chinese"))
        assertTrue(found("language:chinese"))
        assertFalse(found("m:big breasts"))
        assertTrue(found("m:glasses"))
    }

    @Test
    fun mainSearchSyntax() {
        assertTrue(found("female:\"big breasts$\""))
        assertTrue(found("f:\"big breasts$"))
        assertTrue(found("l:chinese$"))
        assertTrue(found("\"no himitsu\""))
        assertTrue(found("f:anal"))
        assertFalse(found("f:anal$"))
        assertFalse(found("-l:chinese"))
        assertTrue(found("-l:english"))
        assertTrue(found("~l:chinese"))
    }

    @Test
    fun everyWordMustMatch() {
        assertTrue(found("f:\"big breasts$\" l:chinese$"))
        assertTrue(found("kanojo f:big breasts"))
        assertTrue(found("himitsu kanojo"))
        assertFalse(found("big breasts tentacles"))
    }

    @Test
    fun colonInTitleIsNoNamespace() {
        assertTrue(DownloadsSearch.parse("Re:Zero").matches("Re:Zero kara", null, null))
    }
}
