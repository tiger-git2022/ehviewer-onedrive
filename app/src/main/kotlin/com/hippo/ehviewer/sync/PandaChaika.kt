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

    /** A page of results, 100 archives; [hasNext] when more follow. */
    @Serializable
    data class SearchResult(val archives: List<Archive> = emptyList(), @SerialName("has_next") val hasNext: Boolean = false)

    /** An archive as the search lists it: its default title and its Japanese one. */
    @Serializable
    data class Archive(val title: String? = null, @SerialName("title_jpn") val titleJpn: String? = null)

    /** Page [page] (from 1) of the archives whose title contains [query]. */
    suspend fun search(query: String, page: Int = 1): SearchResult = ktorClient.prepareGet(SEARCH_URL) {
        parameter("qsearch", query)
        parameter("json", "")
        if (page > 1) parameter("page", page)
    }.executeSafely {
        it.status.ensureSuccess()
        json.decodeFromString(SearchResult.serializer(), it.bodyAsUtf8Text())
    }
}
