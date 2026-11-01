package com.hippo.ehviewer.ui.main

import com.hippo.ehviewer.client.EhUtils.DOUJINSHI
import com.hippo.ehviewer.client.EhUtils.GAME_CG
import com.hippo.ehviewer.client.EhUtils.MANGA
import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryPickTest {
    private val picked = DOUJINSHI or MANGA or GAME_CG

    @Test
    fun longPressKeepsOnlyOneAndBringsTheOthersBack() {
        val solo = CategoryPick(picked).longPress(GAME_CG)
        assertEquals(CategoryPick(GAME_CG, picked), solo)
        assertEquals(CategoryPick(picked), solo.longPress(GAME_CG))
    }

    @Test
    fun anotherLongPressKeepsTheFirstOnesWaiting() {
        val solo = CategoryPick(picked).longPress(GAME_CG).longPress(MANGA)
        assertEquals(CategoryPick(MANGA, picked), solo)
        assertEquals(CategoryPick(picked), solo.longPress(MANGA))
    }

    @Test
    fun aTapForgetsTheOthers() {
        assertEquals(CategoryPick(GAME_CG or MANGA), CategoryPick(GAME_CG, picked).tap(MANGA))
    }

    @Test
    fun theOnlyOneStaysAsItIs() {
        assertEquals(CategoryPick(GAME_CG), CategoryPick(GAME_CG).longPress(GAME_CG))
    }
}
