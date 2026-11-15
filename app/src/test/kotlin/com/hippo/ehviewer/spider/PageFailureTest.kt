package com.hippo.ehviewer.spider

import kotlin.test.Test
import kotlin.test.assertEquals

class PageFailureTest {
    @Test
    fun hathClientKeepsItsPort() {
        val url = "https://fjjnrmw.ruknyeuiaigd.hath.network:50100/h/0a0aded876-209657-1200-630-jpg/keystamp=1;fileindex=2;xres=org/0001.jpg"
        assertEquals("fjjnrmw.ruknyeuiaigd:50100", imageServerName(url))
    }

    @Test
    fun defaultPortIsLeftOut() {
        assertEquals("praogxqcch", imageServerName("https://praogxqcch.hath.network/h/abc/0001.jpg"))
        assertEquals("ehgt.org", imageServerName("https://ehgt.org/g/509.gif"))
    }
}
