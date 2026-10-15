package com.hippo.ehviewer.sync

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Format version of every file written under `ehviewer/` in the OneDrive app folder.
 * A file with a higher version was written by a newer build and is never merged.
 */
const val SYNC_FORMAT_VERSION = 1

/**
 * Synced categories, in the order a sync processes them. Automatic syncs (periodic, at launch, after changes) only
 * take the [automatic] ones, the data that changes while reading; the others wait for a manual sync.
 */
enum class SyncCategory(val fileName: String, val automatic: Boolean) {
    SETTINGS("settings.json.gz", automatic = false),
    FAVORITES("favorites.json.gz", automatic = true),
    HISTORY("history.json.gz", automatic = true),
    PROGRESS("progress.json.gz", automatic = true),
    QUICK_SEARCH("quicksearch.json.gz", automatic = false),
    SUGGESTIONS("suggestions.json.gz", automatic = true),
}

/** Common wrapper of the per category files. */
@Serializable
data class SectionFile<T>(
    @EncodeDefault
    val version: Int = SYNC_FORMAT_VERSION,
    val updatedAt: Long = 0,
    val deviceId: String = "",
    val items: T,
)

/**
 * One preference. [value] is null for a key that was removed (a pref reset to its default);
 * [modifiedAt] is 0 for a value that was never changed since the sync started tracking.
 */
@Serializable
data class SyncSetting(
    val type: String,
    val value: JsonElement? = null,
    val modifiedAt: Long = 0,
) {
    companion object {
        const val TYPE_BOOLEAN = "bool"
        const val TYPE_INT = "int"
        const val TYPE_LONG = "long"
        const val TYPE_FLOAT = "float"
        const val TYPE_DOUBLE = "double"
        const val TYPE_STRING = "string"
        const val TYPE_STRING_SET = "stringSet"
        const val TYPE_REMOVED = "removed"
    }
}

/** Fields of `GalleryEntity`, embedded in the records referencing the gallery. */
@Serializable
data class SyncGallery(
    val gid: Long,
    val token: String,
    val title: String? = null,
    val titleJpn: String? = null,
    val thumbKey: String? = null,
    val category: Int = 0,
    val posted: String? = null,
    val uploader: String? = null,
    val rating: Float = 0f,
    val simpleTags: List<String>? = null,
    val pages: Int = 0,
    val simpleLanguage: String? = null,
    val favoriteSlot: Int = -2,
) {
    /** How many optional fields are filled, used to keep the more complete copy of a gallery. */
    val completeness: Int
        get() = listOf(
            !title.isNullOrEmpty(),
            !titleJpn.isNullOrEmpty(),
            !thumbKey.isNullOrEmpty(),
            !posted.isNullOrEmpty(),
            !uploader.isNullOrEmpty(),
            rating > 0f,
            !simpleTags.isNullOrEmpty(),
            pages > 0,
            !simpleLanguage.isNullOrEmpty(),
        ).count { it }
}

/** A local favorite or a history record. */
@Serializable
data class SyncGalleryRecord(
    val gid: Long,
    val time: Long,
    val gallery: SyncGallery,
)

@Serializable
data class SyncProgress(
    val gid: Long,
    val page: Int,
    val modifiedAt: Long = 0,
)

@Serializable
data class SyncQuickSearch(
    val name: String,
    val mode: Int = 0,
    val category: Int = 0,
    val keyword: String? = null,
    val advanceSearch: Int = 0,
    val minRating: Int = 0,
    val pageFrom: Int = 0,
    val pageTo: Int = 0,
    val modifiedAt: Long = 0,
) {
    fun sameContent(other: SyncQuickSearch) = copy(modifiedAt = 0) == other.copy(modifiedAt = 0)
}

@Serializable
data class SyncFilter(
    val mode: Int,
    val text: String,
    val enable: Boolean = true,
    val modifiedAt: Long = 0,
) {
    val key: Pair<Int, String>
        get() = mode to text
}

@Serializable
data class SyncQuickSearchSection(
    val quickSearches: List<SyncQuickSearch> = emptyList(),
    val filters: List<SyncFilter> = emptyList(),
)

@Serializable
data class SyncSuggestion(
    val query: String,
    val date: Long,
)

/**
 * Fork: `ehviewer/settings/<deviceId>.json.gz`, the settings of one device, written only by that device. Settings
 * differ between devices: another device's are only applied when the user restores them.
 */
@Serializable
data class DeviceSettings(
    @EncodeDefault
    val version: Int = SYNC_FORMAT_VERSION,
    val deviceId: String,
    val deviceName: String = "",
    val updatedAt: Long = 0,
    val settings: Map<String, SyncSetting> = emptyMap(),
)

/** `ehviewer/devices/<deviceId>.json.gz`: the download list of one device, written only by that device. */
@Serializable
data class DeviceDownloads(
    @EncodeDefault
    val version: Int = SYNC_FORMAT_VERSION,
    val deviceId: String,
    val deviceName: String = "",
    val updatedAt: Long = 0,
    val labels: List<SyncLabel> = emptyList(),
    val downloads: List<SyncDownload> = emptyList(),
    val galleries: List<SyncGallery> = emptyList(),
)

@Serializable
data class SyncLabel(
    val label: String,
    val position: Int,
)

@Serializable
data class SyncDownload(
    val gid: Long,
    val label: String? = null,
    val time: Long = 0,
    val finished: Boolean = false,
    val artists: List<String> = emptyList(),
)
