package com.hippo.ehviewer.chapters

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
            listOf(
                Wnacg.Album(402119, "[Circle] Title 'A' [中国翻訳]", 91, "https://t4.qy0.ru/a.webp", millis("2026-11-06T16:53:11"), 1),
                Wnacg.Album(401268, "Other", 0, "https://t4.qy0.ru/b.webp", 0, 3),
            ),
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

    @Test
    fun numbersThumbnailsInTheirOrder() {
        // Names of files not numbered, not one after the other
        val html = """
            <li class="li tb gallary_item"><div class="pic_box tb"><a href="/photos-view-id-1.html">
            <img src="//t4.qy0.ru/data/t/1/a.webp" alt="cover"></a></div></li>
            <li class="li tb gallary_item"><div class="pic_box tb"><a href="/photos-view-id-2.html">
            <img src="//t4.qy0.ru/data/t/1/b.webp" alt="IMG_20240101_7"></a></div></li>
            <li class="li tb gallary_item"><div class="pic_box tb"><a href="/photos-view-id-3.html">
            <img src="//t4.qy0.ru/data/t/1/c.webp" alt="Title_0003"></a></div></li>
        """.trimIndent()
        assertEquals(
            listOf(13 to "https://t4.qy0.ru/data/t/1/a.webp", 14 to "https://t4.qy0.ru/data/t/1/b.webp", 15 to "https://t4.qy0.ru/data/t/1/c.webp"),
            Wnacg.parseThumbnails(html, first = 13),
        )
    }

    @Test
    fun tellsTheNextPageOfASearch() {
        val html = """<span class="thispage">1</span><a href="https://www.wnacg.com/search/index.php?q=x&amp;m=&amp;syn=yes&amp;f=_all&amp;s=create_time_DESC&amp;p=2&amp;c=a~0">2</a>"""
        assertTrue(Wnacg.hasPage(html, 2))
        assertFalse(Wnacg.hasPage(html, 3))
        assertFalse(Wnacg.hasPage(html, 20))
    }

    @Test
    fun readsAnAlbum() {
        val html = """
            <div id="bodywrap" class="userwrap">
                <h2>[Circle (Artist)] Title 2 [中国翻訳] [DL版]</h2>
                <div class="asTB">
                    <div class="asTBcell uwthumb">
                        <img alt="x" src="//t4.qy0.ru/data/t/3786/24/cover.webp" width="240" height="302">
                    </div>
                    <div class="asTBcell uwconn">
                        <label>分類：同人誌／漢化</label><label>頁數：53P</label><label title="x">收藏：1132</label>
                        <div class="addtags">標籤：<a class="tagshow" href="/albums-index-tag-a.html">Artist</a><a class="tagshow" href="/albums-index-tag-b.html">巨乳</a><a onclick="alert(1)" class="btnNarrow">+TAG</a>
                        </div>
                        <p style="overflow-wrap:anywhere;">簡介：Line one<br>Line &amp; two</p>
                    </div>
                    <div class="asTBcell uwuinfo">
                        <a href="/albums-user-uid-3.html"><img src="//x/avatar.jpg" class="img_108x108 f_l">
                        <p>Uploader</p></a>
                        <p>投搞作品 <font color="#D70202">165476</font> 部</p>
                    </div>
                </div>
            </div>
            <li class="li tb gallary_item">
                <div class="pic_box tb"><a href="/photos-view-id-1.html"><img src="//t4.qy0.ru/data/t/3786/24/1.webp" alt="Title_0001"></a></div>
                <div class="info_col">上傳於2026-08-20</div>
            </li>
            <li class="li tb gallary_item">
                <div class="pic_box tb"><a href="/photos-view-id-2.html"><img src="//t4.qy0.ru/data/t/3786/24/2.webp" alt="Title_0002"></a></div>
            </li>
            <div class="comment_wrap" id="comments">
            <div class="pl_pv_hd">評論 (3)</div>
            <div class="pl_pv">
                <img src="//x/a.jpg">
                <div class="pl_pv_b">
                    <div class="pl_pv_n">qip</div>
                    <div class="pl_pv_c">还不错 &lt;3</div>
                    <div class="pl_pv_m">2026-09-22 <svg class="pl_pv_ico" viewBox="0 0 24 24"><path fill="currentColor" d="M1 21h4V9H1v12z"></path></svg>5</div>
                </div>
            </div>
            <a class="pl_pv_all" href="/comment-index-aid-378624.html">查看 / 發表全部 3 條評論 ›</a>
            </div>
        """.trimIndent()
        val album = Wnacg.parseAlbum(378624, html)
        assertEquals("[Circle (Artist)] Title 2 [中国翻訳] [DL版]", album.title)
        assertEquals("https://t4.qy0.ru/data/t/3786/24/cover.webp", album.cover)
        assertEquals(1, album.category)
        assertEquals(53, album.pages)
        assertEquals(listOf("Artist", "巨乳"), album.tags)
        assertEquals("Line one\nLine & two", album.description)
        assertEquals("Uploader", album.uploader)
        assertEquals(millis("2026-08-20T00:00:00"), album.created)
        assertEquals(listOf(1 to "https://t4.qy0.ru/data/t/3786/24/1.webp", 2 to "https://t4.qy0.ru/data/t/3786/24/2.webp"), album.thumbnails)
        assertEquals(listOf(Wnacg.Comment(0, "qip", millis("2026-09-22T00:00:00"), "还不错 <3", 5)), album.comments)
        assertEquals(3, album.commentCount)
    }

    @Test
    fun readsAnAlbumWithoutComments() {
        val html = """
            <h2>Title</h2><div class="asTBcell uwconn"><label>分類：單行本／日語</label><label>頁數：9P</label></div>
            <div class="comment_wrap" id="comments"><div class="pl_pv_hd">評論 </div>
            <a class="pl_pv_all" href="/comment-index-aid-1.html">搶沙發，發表評論 ›</a></div>
        """.trimIndent()
        val album = Wnacg.parseAlbum(1, html)
        assertEquals(13, album.category)
        assertEquals(emptyList(), album.comments)
        assertEquals(0, album.commentCount)
        assertEquals(emptyList(), album.tags)
        assertEquals("", album.description)
    }

    @Test
    fun readsAllComments() {
        val html = """
            <div id="plList">
            <div class="plItem" data-id="20912">
                <img class="plAv" src="//x/a.jpg">
                <div class="plBody">
                    <div class="plHead"><span class="plName">qip</span><span class="plTime">2026-09-22</span></div>
                    <div class="plText">还不错，没雷。<br>第二行</div>
                    <div class="plMeta">
                        <a href="javascript:;" class="plUp" data-id="20912"><svg viewBox="0 0 24 24"><path d="M1 21h4"></path></svg> <i>4</i></a>
                        <a href="javascript:;" class="plDown" data-id="20912"><svg viewBox="0 0 24 24"><path d="M1 21h4"></path></svg> <i>1</i></a>
                        <a href="javascript:;" class="plReply" data-id="20912" data-name="qip">回覆</a>
                    </div>
                </div>
            </div>
            <div class="plItem" data-id="15445">
                <img class="plAv" src="//x/b.jpg">
                <div class="plBody">
                    <div class="plHead"><span class="plName">Other</span><span class="plTime">2026-09-06</span></div>
                    <div class="plText">有的水</div>
                    <div class="plMeta"><a class="plUp" data-id="15445"><i>0</i></a><a class="plDown" data-id="15445"><i>0</i></a></div>
                </div>
            </div>
            </div>
        """.trimIndent()
        assertEquals(
            listOf(
                Wnacg.Comment(20912, "qip", millis("2026-09-22T00:00:00"), "还不错，没雷。\n第二行", 3),
                Wnacg.Comment(15445, "Other", millis("2026-09-06T00:00:00"), "有的水", 0),
            ),
            Wnacg.parseComments(html),
        )
    }

    @Test
    fun readsTheImagesOfTheReader() {
        val script = """
            var imglist = [{ url: fast_img_host+\"//img5.qy0.ru/data/3786/24/01.jpg\", caption: \"[Title]01.jpg\"},
            { url: fast_img_host+\"//img5.qy0.ru/data/3786/24/02.png\", caption: \"[Title]02.png\"},
            { url: fast_img_host+\"/themes/weitu/images/bg/shoucang.jpg\", caption: \"\"}];
        """.trimIndent()
        assertEquals(
            listOf("https://img5.qy0.ru/data/3786/24/01.jpg", "https://img5.qy0.ru/data/3786/24/02.png"),
            Wnacg.parseImages(script),
        )
    }

    @Test
    fun keepsTheVerifyOfEachImage() {
        // As the site writes it (2026-11): without its "?verify=..." an image is refused (403)
        val script = """
            document.writeln("  var fast_img_host=\"\";");
            document.writeln("  var imglist = [{ url: fast_img_host+\"//img5.qy0.ru/data/3000/00/01.jpg?verify=1794506400-mrcjnNBKCpxb\", caption: \"[01]\"},{ url: fast_img_host+\"//img5.qy0.ru/data/3000/00/02.webp?verify=1794506400-5jncFCw_Mn-p\", caption: \"[02]\"},{ url: fast_img_host+\"/themes/weitu/images/bg/shoucang.jpg\", caption: \"喜歡紳士漫畫的同學請加入收藏哦！\"}];");
        """.trimIndent()
        assertEquals(
            listOf(
                "https://img5.qy0.ru/data/3000/00/01.jpg?verify=1794506400-mrcjnNBKCpxb",
                "https://img5.qy0.ru/data/3000/00/02.webp?verify=1794506400-5jncFCw_Mn-p",
            ),
            Wnacg.parseImages(script),
        )
    }

    @Test
    fun readsTheImagesAfterTheirHost() {
        val script = """
            var fast_img_host = "https://img4.qy0.ru";
            var imglist = [{ url: fast_img_host+\"/data/3786/24/01.webp\", caption: \"01\"},
            { url: fast_img_host+\"/data/3786/24/02.webp\", caption: \"02\"}];
        """.trimIndent()
        assertEquals(
            listOf("https://img4.qy0.ru/data/3786/24/01.webp", "https://img4.qy0.ru/data/3786/24/02.webp"),
            Wnacg.parseImages(script),
        )
    }

    private fun millis(time: String) = LocalDateTime.parse(time).toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
}
