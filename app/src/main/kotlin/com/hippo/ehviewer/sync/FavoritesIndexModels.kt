package com.hippo.ehviewer.sync

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/** Format version of the files of the cloud favorites kept on this device. */
const val FAVORITES_INDEX_VERSION = 1

/** Number of E-Hentai cloud favorite slots. */
const val CLOUD_SLOT_COUNT = 10

/** The cloud slots 0..9. */
val ALL_CLOUD_SLOTS = (0 until CLOUD_SLOT_COUNT).toList()

/** [IndexedGallery.slot] of a gallery that is not in the cloud favorites. */
const val NO_CLOUD_SLOT = -1

/**
 * The favorites index, uploaded in shards (see [FavoritesManifest]) to the OneDrive app folder: every local favorite
 * and every E-Hentai cloud favorite of this account, for other apps signed in to the same app folder (the Mihon fork
 * shows which manga are favorited here). Read only by other apps; EhViewer itself only reads the cloud part back, see [CloudFavorites].
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
    /**
     * [title] and [titleJpn] are the ones the API gave (the default title, the list title when the API has none):
     * another device takes them instead of looking them up again. Written only when true.
     */
    val titlesLookedUp: Boolean = false,
)

/**
 * The cloud favorites known on this device, kept in `files/cloud_favorites/` (see [CloudFavoritesStore]). E-Hentai has no
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
    /**
     * Default titles (romanized or English, the ones most other sites list) looked up with [japaneseTitles]: gid ->
     * title, empty if the API has none (removed galleries). An account showing Japanese titles gets only those on its
     * list pages, and sites listing the default title would not match them.
     */
    val defaultTitles: Map<Long, String> = emptyMap(),
    /**
     * Default titles of galleries the API has none for (removed), from the raw copy panda.chaika.moe archived: gid ->
     * title, empty if it has none. Not shared with other devices, which look up the few themselves.
     */
    val pandaTitles: Map<Long, String> = emptyMap(),
) {
    fun countBySlot(): List<Int> {
        val counts = IntArray(CLOUD_SLOT_COUNT)
        galleries.forEach { if (it.slot in 0 until CLOUD_SLOT_COUNT) counts[it.slot]++ }
        return counts.toList()
    }
}

/**
 * A refresh of the cloud favorites that has not finished, kept in `files/cloud_favorites_refresh/` (see [CheckpointStore]): a refresh
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

/** Fork: format version of the sharded export in `ehviewer/export/favorites/`, see [FavoritesManifest]. */
const val FAVORITES_EXPORT_VERSION = 2

/**
 * `ehviewer/export/favorites/manifest.json`: the [FavoritesIndex] of this account without its galleries, which are in
 * the content addressed [shards] (`shard-<hash>.json.gz`, see [GidShards]). A reader downloads only the shards it does
 * not have yet: a change of a few favorites changes a few shards.
 */
@Serializable
data class FavoritesManifest(
    @EncodeDefault
    val version: Int = FAVORITES_EXPORT_VERSION,
    val updatedAt: Long = 0,
    val deviceId: String = "",
    val deviceName: String = "",
    val ehUserKey: String? = null,
    val cloudUpdatedAt: Long = 0,
    val cloudRefreshedAt: Long = 0,
    val slotNames: List<String> = emptyList(),
    /** Galleries of all shards. */
    val count: Int = 0,
    /** By [ManifestShard.minGid]: a shard holds the gids from its min gid up to the next shard's. */
    val shards: List<ManifestShard> = emptyList(),
) {
    /** Content without the write time, to tell whether a new manifest must be uploaded. */
    fun sameContent(other: FavoritesManifest) = copy(updatedAt = 0) == other.copy(updatedAt = 0)
}

@Serializable
data class ManifestShard(
    /** First 16 bytes of the SHA-256 of the shard JSON before compression, in hex; names the file. */
    val hash: String,
    val minGid: Long,
    val count: Int,
    /** Size of the gzipped file. */
    val size: Long,
)

/** A shard of the export: galleries of a gid range, sorted by gid. */
@Serializable
data class FavoritesShard(
    @EncodeDefault
    val version: Int = FAVORITES_EXPORT_VERSION,
    val galleries: List<IndexedGallery> = emptyList(),
)

/**
 * Fork: `files/cloud_favorites/meta.json`, the [CloudFavorites] without its galleries and titles, which are kept in
 * shards by gid range ([CloudFavoritesPart], see [GidShards]): a change rewrites only the shards it touches.
 */
@Serializable
data class CloudFavoritesMeta(
    @EncodeDefault
    val version: Int = FAVORITES_INDEX_VERSION,
    val userKey: String? = null,
    val updatedAt: Long = 0,
    val refreshedAt: Long = 0,
    val slotNames: List<String> = emptyList(),
    val parts: List<StorePart> = emptyList(),
)

@Serializable
data class StorePart(
    val minGid: Long,
    /** Content hash naming the file `part-<hash>.json.gz`. */
    val hash: String,
    /** [GidShards.Fingerprint] of the content, to skip writing a part that did not change. */
    val fingerprint: Long,
)

/** Galleries and looked up titles of a gid range of [CloudFavorites]. */
@Serializable
data class CloudFavoritesPart(
    val galleries: List<IndexedGallery> = emptyList(),
    /** Position numbers of [galleries] in the whole list, which keeps its order, see [GidShards.orders]. */
    val orders: List<Long> = emptyList(),
    val japaneseTitles: Map<Long, String> = emptyMap(),
    val defaultTitles: Map<Long, String> = emptyMap(),
    val pandaTitles: Map<Long, String> = emptyMap(),
)

/**
 * Fork: `files/cloud_favorites_refresh/meta.json`, a [CloudRefreshCheckpoint] without its galleries, which are appended
 * to page files as the refresh goes on instead of rewriting all galleries read so far every few pages.
 */
@Serializable
data class CheckpointMeta(
    val checkpoint: CloudRefreshCheckpoint,
    /** Files `pages-<n>.json.gz` holding the galleries in order. */
    val pages: List<String> = emptyList(),
)

@Serializable
data class CheckpointPage(val galleries: List<IndexedGallery> = emptyList())
