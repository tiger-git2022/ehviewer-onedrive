package com.hippo.ehviewer.sync

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/** Format version of `ehviewer/export/favorites-index.json.gz`. Readers skip a file with a higher version. */
const val FAVORITES_INDEX_VERSION = 1

/** Number of E-Hentai cloud favorite slots. */
const val CLOUD_SLOT_COUNT = 10

/** The cloud slots 0..9. */
val ALL_CLOUD_SLOTS = (0 until CLOUD_SLOT_COUNT).toList()

/** [IndexedGallery.slot] of a gallery that is not in the cloud favorites. */
const val NO_CLOUD_SLOT = -1

/**
 * `ehviewer/export/favorites-index.json.gz` in the OneDrive app folder: every local favorite and every E-Hentai cloud
 * favorite of this account, for other apps signed in to the same app folder (the Mihon fork shows which manga are
 * favorited here). Read only by other apps; EhViewer itself only reads the cloud part back, see [CloudFavorites].
 *
 * A gallery that is both a local and a cloud favorite is listed once. [slotNames] are the names of the 10 cloud slots.
 */
@Serializable
data class FavoritesIndex(
    @EncodeDefault
    val version: Int = FAVORITES_INDEX_VERSION,
    val updatedAt: Long = 0,
    val deviceId: String = "",
    val deviceName: String = "",
    /** [CloudFavorites.userKey] of the cloud part, null without an E-Hentai account. */
    val ehUserKey: String? = null,
    /** Last change of the cloud part, see [CloudFavorites.updatedAt]. */
    val cloudUpdatedAt: Long = 0,
    /** Last full refresh of the cloud part, 0 if it was never read completely. */
    val cloudRefreshedAt: Long = 0,
    val slotNames: List<String> = emptyList(),
    val galleries: List<IndexedGallery> = emptyList(),
) {
    /** Content without the write time, to tell whether a new copy must be uploaded. */
    fun sameContent(other: FavoritesIndex) = copy(updatedAt = 0) == other.copy(updatedAt = 0)
}

@Serializable
data class IndexedGallery(
    val gid: Long,
    val token: String,
    val title: String? = null,
    /** Null if unknown, empty if the gallery has no Japanese title. */
    val titleJpn: String? = null,
    val uploader: String? = null,
    val category: Int = 0,
    val local: Boolean = false,
    /** Cloud slot 0..9, [NO_CLOUD_SLOT] if the gallery is not a cloud favorite. */
    val slot: Int = NO_CLOUD_SLOT,
)

/**
 * The cloud favorites known on this device, kept in `files/onedrive_sync/cloud_favorites.json.gz`. E-Hentai has no
 * cheap way to list them, so the list is built from the favorites pages the user opens, the favorite changes made in
 * the app and a page by page refresh when the counts of E-Hentai no longer match.
 */
@Serializable
data class CloudFavorites(
    @EncodeDefault
    val version: Int = FAVORITES_INDEX_VERSION,
    /**
     * Hash of the E-Hentai member id the list belongs to. A list of another account is dropped, so signing in to
     * another account never exports the favorites of the previous one.
     */
    val userKey: String? = null,
    /** Last change of [galleries] or [slotNames]; the copy changed last wins between devices. */
    val updatedAt: Long = 0,
    /** Last full refresh, 0 before the first one. */
    val refreshedAt: Long = 0,
    val slotNames: List<String> = emptyList(),
    /** Galleries with [IndexedGallery.slot] in 0..9, [IndexedGallery.local] unused. */
    val galleries: List<IndexedGallery> = emptyList(),
    /**
     * Japanese titles looked up for the index, local favorites included: gid -> title, empty if the gallery has none.
     * The list pages show only one title, and the database is left alone (an empty title would show in the app).
     */
    val japaneseTitles: Map<Long, String> = emptyMap(),
) {
    fun countBySlot(): List<Int> {
        val counts = IntArray(CLOUD_SLOT_COUNT)
        galleries.forEach { if (it.slot in 0 until CLOUD_SLOT_COUNT) counts[it.slot]++ }
        return counts.toList()
    }
}

/**
 * A refresh of the cloud favorites that has not finished, kept in `files/cloud_favorites_refresh.json.gz`: a refresh
 * cut short (the app closed, no network) goes on from [slot] and [next] instead of reading every page again.
 */
@Serializable
data class CloudRefreshCheckpoint(
    @EncodeDefault
    val version: Int = FAVORITES_INDEX_VERSION,
    /** [CloudFavorites.userKey] of the account the pages belong to. */
    val userKey: String? = null,
    val startedAt: Long = 0,
    /** Slots this refresh reads, in order: all of them the first time, else those whose count changed on the website. */
    val slots: List<Int> = ALL_CLOUD_SLOTS,
    /** Slot read now; the slots of [slots] before it are done. */
    val slot: Int = 0,
    /** Cursor of the next page of [slot], null at its first page. */
    val next: String? = null,
    /** Galleries of the pages read so far. */
    val galleries: List<IndexedGallery> = emptyList(),
    /** Count of each slot when its pages were read: a slot read before that changed since is read again. */
    val counts: List<Int> = emptyList(),
    /**
     * Galleries changed in the app since [startedAt]: the known list is right about them, the pages read before the
     * change are not.
     */
    val touched: Set<Long> = emptySet(),
    /**
     * Slot the user put a gallery in since [startedAt] ([NO_CLOUD_SLOT] when removed): pages read before the change
     * are corrected with it, also for galleries the known list did not have yet.
     */
    val intended: Map<Long, Int> = emptyMap(),
)
