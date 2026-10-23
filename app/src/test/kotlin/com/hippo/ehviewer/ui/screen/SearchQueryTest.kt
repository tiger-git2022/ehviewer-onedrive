package com.hippo.ehviewer.ui.screen

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchQueryTest {
    @Test
    fun closedQueriesStayAsTheyAre() {
        assertEquals("", closeTagQuotes(""))
        assertEquals("kanojo", closeTagQuotes("kanojo"))
        assertEquals("f:\"mind control$\" p:\"azur lane$\"", closeTagQuotes("f:\"mind control$\" p:\"azur lane$\""))
        assertEquals("l:chinese$ g:misaki$", closeTagQuotes("l:chinese$ g:misaki$"))
        assertEquals("\"big breasts\" -l:english", closeTagQuotes("\"big breasts\" -l:english"))
    }

    @Test
    fun openTagIsClosedAtItsDollar() {
        assertEquals("f:\"mind control$\" p:\"azur lane$\"", closeTagQuotes("f:\"mind control$ p:\"azur lane$\""))
        assertEquals("f:\"mind control$\"", closeTagQuotes("f:\"mind control$"))
        assertEquals("a:\"asama hinami$\" f:\"mind control$\"", closeTagQuotes("a:\"asama hinami$ f:\"mind control$"))
    }

    @Test
    fun openQuoteIsClosedAtTheEnd() {
        assertEquals("f:\"big breasts\"", closeTagQuotes("f:\"big breasts"))
    }

    @Test
    fun suggestionGoesAfterTheLastTag() {
        assertEquals(-1, tagSuggestionIndex("mind"))
        val query = "f:\"mind control$\" azur"
        assertEquals(query.indexOf("\" "), tagSuggestionIndex(query))
        assertEquals(9, tagSuggestionIndex("l:chinese$ azur"))
    }

    @Test
    fun suggestionReplacesTheTagTypedInAnOpenQuote() {
        assertEquals(-1, tagSuggestionIndex("f:\"mind con"))
        val query = "l:chinese$ f:\"mind con"
        assertEquals(query.indexOf(" f:"), tagSuggestionIndex(query))
    }
}
