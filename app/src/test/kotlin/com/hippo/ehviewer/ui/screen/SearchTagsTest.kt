package com.hippo.ehviewer.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchTagsTest {
    @Test
    fun tagsAreTakenOutOfTheSearch() {
        assertEquals(listOf("f:\"big breasts$\"", "l:chinese$") to "", splitSearchTags("f:\"big breasts$\" l:chinese$"))
        assertEquals(listOf("f:\"big breasts$\"", "l:chinese$") to "english", splitSearchTags("f:\"big breasts$\" english l:chinese$"))
        assertEquals(listOf("-m:futanari$", "~f:\"mind control$\"") to "", splitSearchTags("-m:futanari$ ~f:\"mind control$\""))
    }

    @Test
    fun textThatIsNoTagStays() {
        assertEquals(emptyList<String>() to "kanojo okarishimasu", splitSearchTags("kanojo okarishimasu"))
        assertEquals(emptyList<String>() to "uploader:\"misaki\"", splitSearchTags("uploader:\"misaki\""))
        assertEquals(emptyList<String>() to "f:\"mind con", splitSearchTags("f:\"mind con"))
        assertEquals(emptyList<String>() to "l:chinese", splitSearchTags("l:chinese"))
        assertEquals(emptyList<String>() to "", splitSearchTags(""))
    }

    @Test
    fun tagsAndTextJoinWithASpace() {
        assertEquals("f:\"big breasts$\" l:chinese$ english", joinSearchTags(listOf("f:\"big breasts$\"", "l:chinese$"), "english"))
        assertEquals("f:\"big breasts$\"", joinSearchTags(listOf("f:\"big breasts$\""), ""))
        assertEquals("english ", joinSearchTags(emptyList(), "english "))
        val (tags, text) = splitSearchTags("f:\"big breasts$\" l:chinese$ english")
        assertEquals("f:\"big breasts$\" l:chinese$ english", joinSearchTags(tags, text))
    }

    @Test
    fun excludingATag() {
        assertEquals("-f:\"big breasts$\"", toggleExcludedTag("f:\"big breasts$\""))
        assertEquals("f:\"big breasts$\"", toggleExcludedTag("-f:\"big breasts$\""))
        assertEquals("-l:chinese$", toggleExcludedTag("~l:chinese$"))
        assertEquals("-f:\"big breasts$\"", pickedSearchTag("f:\"big breasts$\"", "-big bre"))
        assertEquals("f:\"big breasts$\"", pickedSearchTag("f:\"big breasts$\"", "big bre"))
    }

    @Test
    fun partsOfATag() {
        assertEquals("f" to "big breasts", searchTagParts("-f:\"big breasts$\""))
        assertEquals("l" to "chinese", searchTagParts("l:chinese$"))
    }
}
