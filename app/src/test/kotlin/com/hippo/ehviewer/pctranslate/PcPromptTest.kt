package com.hippo.ehviewer.pctranslate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PcPromptTest {
    private val item = PcItem(gid = 123, title = "标题", folder = "2026-11-11", state = PcState.ON_PC, time = 0)

    @Test
    fun pcDirFollowsTabletBridge() {
        assertEquals("123-[作者] 标题_ 副题_", PcPrompt.pcDir(123, "123-[作者] 标题: 副题?"))
        assertEquals("123-abc", PcPrompt.pcDir(123, "abc"))
        assertEquals("123-123", PcPrompt.pcDir(123, null))
    }

    @Test
    fun fillPutsInThePaths() {
        val out = PcPrompt.fill("{根目录}|{批次}|{编号}|{标题}|{作品文件夹}|{翻译文件夹}", "E:\\漫画源文件\\", item, "123-x")
        assertEquals("E:\\漫画源文件|2026-11-11|123|标题|123-x|E:\\漫画源文件\\2026-11-11\\123", out)
    }

    @Test
    fun defaultTemplateHasNoPlaceholderLeft() {
        val out = PcPrompt.fill(PcPrompt.DEFAULT_TEMPLATE, "E:\\漫画源文件", item, "123-x")
        PcPrompt.placeholders.forEach { (k, _) -> assertFalse(k in out, k) }
        assert("待翻目标：E:\\漫画源文件\\2026-11-11\\123" in out)
    }
}
