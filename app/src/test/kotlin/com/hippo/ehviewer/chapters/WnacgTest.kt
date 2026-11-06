package com.hippo.ehviewer.chapters

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pages cut down from those the site served on 2026-11-06, the titles made up. */
class WnacgTest {
    @Test
    fun readsASearch() {
        val html = """
            <ul class="cc">
            <li class="li gallary_item">
            <div class="pic_box cate-1">
            <a href="https://www.wnacg.com/photos-index-aid-402119.html" title="[Circle] Title &#39;A&#39; [中国翻訳]"><img alt="x" src="//t4.qy0.ru/a.webp"></a></div>
            <div class="info"><div class="title">
            <a href="https://www.wnacg.com/photos-index-aid-402119.html" title="[Circle] Title &#39;A&#39; [中国翻訳]">[Circle] Title</a>
            </div><div class="info_col">
            91張圖片，
            創建於2026-11-06 16:53:11</div></div></li>
            <li class="li gallary_item">
            <div class="pic_box cate-3">
            <a href="/photos-index-aid-401268.html" title="Other"><img alt="Other" src="//t4.qy0.ru/b.webp"></a></div>
            </li></ul>
        """.trimIndent()
        assertEquals(
            listOf(Wnacg.Album(402119, "[Circle] Title 'A' [中国翻訳]", 91), Wnacg.Album(401268, "Other", 0)),
            Wnacg.parseSearch(html),
        )
    }

    @Test
    fun readsThumbnailsOnce() {
        val html = """
            <li class="li tb gallary_item">
            <!--<div class="pic_box"><a href="/photos-view-id-35609471.html">
            <img src="//t4.qy0.ru/data/t/4021/19/1.webp" alt="[Circle] Title_0001" /></a></div>-->
            <div class="pic_box tb"><a href="https://www.wnacg.com/photos-view-id-35609471.html">
            <img src="//t4.qy0.ru/data/t/4021/19/1.webp" alt="[Circle] Title_0001"></a></div>
            </li>
            <li class="li tb gallary_item">
            <div class="pic_box tb"><a href="https://www.wnacg.com/photos-view-id-35609472.html">
            <img src="https://t4.qy0.ru/data/t/4021/19/2.webp" alt="[Circle] Title_0002"></a></div>
            </li>
        """.trimIndent()
        assertEquals(
            listOf(1 to "https://t4.qy0.ru/data/t/4021/19/1.webp", 2 to "https://t4.qy0.ru/data/t/4021/19/2.webp"),
            Wnacg.parseThumbnails(html),
        )
    }
}
