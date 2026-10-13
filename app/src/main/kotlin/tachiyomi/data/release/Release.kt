package tachiyomi.data.release

import com.hippo.ehviewer.util.AppConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Release(
    @SerialName("tag_name") val version: String,
    @SerialName("body") val body: String? = null,
    @SerialName("html_url") val releaseLink: String,
    @SerialName("target_commitish") val targetCommitish: String = "",
    @SerialName("assets") val assets: List<Assets>,
) {
    val info: String
        get() = body.orEmpty()

    fun getDownloadLink(): String {
        val asset = assets.find { AppConfig.matchVariant(it.name) } ?: assets.find { "universal" in it.name } ?: assets[0]
        return asset.url
    }
}

@Serializable
data class Assets(
    @SerialName("browser_download_url")
    val url: String,

    val name: String,
)
