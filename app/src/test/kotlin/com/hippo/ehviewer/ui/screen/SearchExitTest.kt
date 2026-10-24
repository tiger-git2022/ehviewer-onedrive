package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.ui.component.searchExitFraction
import com.ehviewer.core.ui.component.searchExitGlassAlpha
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchExitTest {
    @Test
    fun cardsLeaveFromTheBottomUp() {
        // At the bottom a card starts at once, at the top 40 % into the closing
        assertEquals(0f, searchExitFraction(0f, 0f))
        assertEquals(0.5f, searchExitFraction(0.2f, 0f), 1e-4f)
        assertEquals(1f, searchExitFraction(0.4f, 0f))
        assertEquals(0f, searchExitFraction(0.4f, 1f))
        assertEquals(1f, searchExitFraction(0.8f, 1f))
        assertTrue(searchExitFraction(0.3f, 0.2f) > searchExitFraction(0.3f, 0.6f))
        // A card below or above the screen leaves like one at its edge
        assertEquals(searchExitFraction(0.3f, 0f), searchExitFraction(0.3f, -0.5f))
        assertEquals(searchExitFraction(0.7f, 1f), searchExitFraction(0.7f, 1.2f))
    }

    @Test
    fun glassFadesOnceTheCardsHaveMostlyLeft() {
        assertEquals(1f, searchExitGlassAlpha(0f))
        assertEquals(1f, searchExitGlassAlpha(0.5f))
        assertTrue(searchExitGlassAlpha(0.75f) in 0.1f..0.9f)
        assertEquals(0f, searchExitGlassAlpha(1f), 1e-4f)
    }
}
