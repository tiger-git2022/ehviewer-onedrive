package com.hippo.ehviewer.sync

import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.client.executeSafely
import com.hippo.ehviewer.util.bodyAsUtf8Text
import com.hippo.ehviewer.util.ensureSuccess
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The archive of panda.chaika.moe, which keeps raw copies of many galleries E-Hentai removed: a removed favorite has
 * no default (romanized) title from the API, the archive of its raw original has one, see
 * [FavoritesIndexMerger.pandaDefaultTitle].
 */
object PandaChaika {
    private const val SEARCH_URL = "https://panda.chaika.moe/search/"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class SearchResult(val archives: List<Archive> = emptyList())

    /** An archive as the search lists it: its default title and its Japanese one. */
    @Serializable
    data class Archive(val title: String? = null, @SerialName("title_jpn") val titleJpn: String? = null)

    /** The archives whose title contains [query], first page. */
    suspend fun search(query: String): List<Archive> = ktorClient.prepareGet(SEARCH_URL) {
        parameter("qsearch", query)
        parameter("json", "")
    }.executeSafely {
        it.status.ensureSuccess()
        json.decodeFromString(SearchResult.serializer(), it.bodyAsUtf8Text()).archives
    }
}
