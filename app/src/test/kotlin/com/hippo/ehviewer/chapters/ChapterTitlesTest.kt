package com.hippo.ehviewer.chapters

import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterTitlesTest {
    @Test
    fun leavesOutTheBrackets() {
        assertEquals("futari no kanojo", ChapterTitles.core("[Tatsunami Youtoku] Futari no Kanojo (COMIC Megastore H 2006-07) [Chinese]"))
        assertEquals("futari no kanojo", ChapterTitles.core("[Tatsunami Youtoku] Futari no Kanojo (COMIC Megastore H 2006-07)"))
        assertEquals("futari no kanojo", ChapterTitles.core("[たつなみ] Futari no  Kanojo [英訳] [DL版]"))
        assertEquals("ふたりの彼女", ChapterTitles.core("[竜南洋徳] ふたりの彼女 (コミックメガストアH 2006年7月号)【中国翻訳】"))
    }
}
