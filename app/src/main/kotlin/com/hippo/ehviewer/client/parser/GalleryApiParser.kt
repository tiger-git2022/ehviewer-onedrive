/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.client.parser

import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.unescapeXml
import com.hippo.ehviewer.client.EhUtils.getCategory
import com.hippo.ehviewer.client.getThumbKey
import com.hippo.ehviewer.client.parseAs
import com.hippo.ehviewer.download.GallerySizes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object GalleryApiParser {
    fun parse(body: String, galleryInfoList: List<GalleryInfo>) {
        val items = body.parseAs<Result>().items
        // Fork: sizes for the download list
        GallerySizes.remember(items.filter { it.error == null }.associate { it.gid to it.filesize })
        items.forEach { item ->
            // Fork: a gallery the API cannot return comes as {"gid", "error"} and must not fail the others
            if (item.error != null) return@forEach
            val gi = galleryInfoList.find { it.gid == item.gid } ?: return@forEach
            gi.apply {
                title = item.title.unescapeXml()
                titleJpn = item.titleJpn.unescapeXml()
                category = getCategory(item.category)
                thumbKey = getThumbKey(item.thumb)
                uploader = item.uploader?.unescapeXml()
                posted = ParserUtils.formatDate(item.posted * 1000)
                rating = item.rating
                simpleTags = item.tags
                pages = item.pages
                generateSLang()
            }
        }
    }

    @Serializable
    data class Result(@SerialName("gmetadata") val items: List<Item>)

    @Serializable
    data class Item(
        val gid: Long,
        // Fork: defaults, so an error entry without them still parses
        val title: String = "",
        @SerialName("title_jpn")
        val titleJpn: String = "",
        val category: String = "",
        val thumb: String = "",
        // Null in some old galleries
        val uploader: String? = null,
        val posted: Long = 0,
        @SerialName("filecount")
        val pages: Int = 0,
        val rating: Float = 0f,
        val tags: List<String> = emptyList(),
        // Fork: bytes of the original archive
        val filesize: Long = 0,
        val error: String? = null,
    )
}
