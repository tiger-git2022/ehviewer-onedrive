package com.hippo.ehviewer.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MihonSearchTest {
    @Test
    fun coreTitleDropsEveryBracketGroup() {
        assertEquals(
            "デリヘルチケット使ったら・・・来たのは大好きな推しでした。",
            MihonSearch.coreTitle("[ミルクレープ (チビゴン)] デリヘルチケット使ったら・・・来たのは大好きな推しでした。 [中国翻訳]"),
        )
        assertEquals(
            "催眠性指導 宮島桜の場合",
            MihonSearch.coreTitle("(C97) [サークルA (作者A)] 催眠性指導 宮島桜の場合 (オリジナル) [中国翻訳] [DL版]"),
        )
        assertEquals(
            "Kanojo no Himitsu",
            MihonSearch.coreTitle("[Circle B] Kanojo no Himitsu | Her Secret [English]"),
        )
        assertEquals("Kanojo no Himitsu", MihonSearch.coreTitle("Kanojo no Himitsu|Her Secret [English]"))
        assertEquals("ドスケベシスターズの誘惑-背徳の聖女ハーレム-", MihonSearch.coreTitle("【デンジャラス・ラビット】ドスケベシスターズの誘惑-背徳の聖女ハーレム-（无修正）"))
        // Nothing but brackets: kept as it is
        assertEquals("[作者E]", MihonSearch.coreTitle("[作者E]"))
    }

    @Test
    fun artistComesFromTheLeadingGroup() {
        assertEquals("チビゴン", MihonSearch.artist("[ミルクレープ (チビゴン)] デリヘルチケット使ったら"))
        assertEquals("デンジャラス・ラビット", MihonSearch.artist("[デンジャラス・ラビット] ドスケベシスターズの誘惑"))
        assertEquals("作者A", MihonSearch.artist("(C97) [サークルA (作者A)] 催眠性指導"))
        // A translator group first: the next group names the artist
        assertEquals("作者A", MihonSearch.artist("[脸肿汉化组] (C97) [サークルA (作者A)] 催眠性指導"))
        assertEquals("作者A", MihonSearch.artist("[AI機翻] [サークルA (作者A)] 催眠性指導"))
        assertEquals("作者A", MihonSearch.artist("[嵌字组] [サークルA (作者A)] 催眠性指導"))
        assertEquals("飞鸟", MihonSearch.artist("催眠性指導", listOf("飞鸟")))
        assertNull(MihonSearch.artist("催眠性指導"))
    }
}
