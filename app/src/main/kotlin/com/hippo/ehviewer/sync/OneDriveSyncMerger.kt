package com.hippo.ehviewer.sync

import java.io.IOException

class SyncVersionException(val version: Int) : IOException("The sync data was written by a newer version (format $version)")

/**
 * Pure three-way merge of the synced data: local state, the copy currently in the cloud and the baseline,
 * i.e. the result of the last successful sync. Without a baseline (first sync, or after switching
 * accounts) every merge only adds, nothing is deleted.
 *
 * Nothing in here touches Android or the database so it can be unit tested on the JVM.
 */
object OneDriveSyncMerger {
    /** Newest search suggestions kept in the cloud. */
    const val SUGGESTION_LIMIT = 1000

    /** Device specific or sensitive preferences that never leave this device. */
    private val settingsBlacklist = setOf(
        // Download location
        "image_scheme",
        "image_authority",
        "image_path",
        "image_query",
        "image_fragment",
        "media_scan",
        // E-Hentai account
        "has_signed_in",
        "need_sign_in",
        "display_name",
        "avatar",
        "fav_cloud",
        // Secrets and per device state
        "archive_passwds",
        "clipboard_text_hash_code",
        "last_update_time",
        "last_dawn_days",
        "app_link_verify_tip",
        // Device security
        "require_unlock",
        "require_unlock_delay",
        "enable_secure",
        // Depends on the device hardware or network stack
        "enable_cronet",
        "enable_quic",
        "hardware_bitmap_threshold",
        // Downloads are per device, so are their labels and list state
        "download_sort_mode",
        "download_filter_mode",
        "recent_download_label",
        "has_default_download_label",
        "default_download_label",
        // Transient UI state
        "recent_fav_cat",
        "recent_toplist",
    )

    /** Favorite category names and counts come from the E-Hentai account. */
    private val settingsBlacklistPrefixes = listOf("fav_cat_", "fav_count_")

    fun isSyncableSetting(key: String) = key !in settingsBlacklist && settingsBlacklistPrefixes.none { key.startsWith(it) }

    fun checkVersion(version: Int) {
        if (version > SYNC_FORMAT_VERSION) throw SyncVersionException(version)
    }

    /**
     * Generic keyed three-way merge.
     *
     * - Key not in baseline: added by one side, or by both ([resolve]).
     * - Key in baseline and missing on one side: deleted there, unless the other side changed it since
     *   the baseline (a change wins over a deletion).
     * - Key on both sides: the side that changed it since the baseline wins, both changed → [resolve].
     */
    fun <K, V> mergeKeyed(
        local: Map<K, V>,
        cloud: Map<K, V>,
        baseline: Map<K, V>?,
        changed: (old: V, new: V) -> Boolean,
        resolve: (local: V, cloud: V) -> V,
    ): Map<K, V> {
        // Fork: sized for the larger side, the two sides mostly share their keys; their sum doubled the tables
        val result = LinkedHashMap<K, V>(maxOf(cloud.size, local.size))
        val keys = LinkedHashSet<K>(maxOf(cloud.size, local.size)).apply {
            addAll(cloud.keys)
            addAll(local.keys)
            baseline?.let { addAll(it.keys) }
        }
        for (key in keys) {
            val l = local[key]
            val c = cloud[key]
            val b = baseline?.get(key)
            val merged = if (b == null) {
                when {
                    l != null && c != null -> resolve(l, c)
                    else -> l ?: c
                }
            } else {
                when {
                    l == null && c == null -> null
                    l == null -> c!!.takeIf { changed(b, it) }
                    c == null -> l.takeIf { changed(b, it) }
                    else -> {
                        val localChanged = changed(b, l)
                        val cloudChanged = changed(b, c)
                        when {
                            localChanged && cloudChanged -> resolve(l, c)
                            localChanged -> l
                            else -> c
                        }
                    }
                }
            }
            if (merged != null) result[key] = merged
        }
        return result
    }

    /** Settings: per key, the newer [SyncSetting.modifiedAt] wins; on a tie the cloud wins. */
    fun mergeSettings(
        local: Map<String, SyncSetting>,
        cloud: Map<String, SyncSetting>,
    ): Map<String, SyncSetting> {
        val result = sortedMapOf<String, SyncSetting>()
        (cloud.keys + local.keys).filter(::isSyncableSetting).forEach { key ->
            val l = local[key]
            val c = cloud[key]
            result[key] = when {
                l == null -> c!!
                c == null -> l
                l.modifiedAt > c.modifiedAt -> l
                else -> c
            }
        }
        return result
    }

    private fun moreComplete(local: SyncGallery, cloud: SyncGallery) = if (cloud.completeness > local.completeness) cloud else local

    /** Local favorites: set with baseline, an addition wins over a deletion, the earlier time is kept. */
    fun mergeFavorites(
        local: List<SyncGalleryRecord>,
        cloud: List<SyncGalleryRecord>,
        baseline: List<SyncGalleryRecord>?,
    ) = mergeKeyed(
        local = local.associateBy { it.gid },
        cloud = cloud.associateBy { it.gid },
        baseline = baseline?.associateBy { it.gid },
        changed = { old, new -> old.time != new.time },
        resolve = { l, c -> SyncGalleryRecord(c.gid, minOf(l.time, c.time), moreComplete(l.gallery, c.gallery)) },
    ).values.sortedBy { it.gid }

    /** History: set with baseline (single deletions and clearing sync), the later time is kept. */
    fun mergeHistory(
        local: List<SyncGalleryRecord>,
        cloud: List<SyncGalleryRecord>,
        baseline: List<SyncGalleryRecord>?,
    ) = mergeKeyed(
        local = local.associateBy { it.gid },
        cloud = cloud.associateBy { it.gid },
        baseline = baseline?.associateBy { it.gid },
        changed = { old, new -> old.time != new.time },
        resolve = { l, c -> SyncGalleryRecord(c.gid, maxOf(l.time, c.time), moreComplete(l.gallery, c.gallery)) },
    ).values.sortedBy { it.gid }

    /** Reading progress: the newer [SyncProgress.modifiedAt] wins; on a tie the cloud wins. */
    fun mergeProgress(
        local: List<SyncProgress>,
        cloud: List<SyncProgress>,
        baseline: List<SyncProgress>?,
    ) = mergeKeyed(
        local = local.associateBy { it.gid },
        cloud = cloud.associateBy { it.gid },
        baseline = baseline?.associateBy { it.gid },
        changed = { old, new -> old.page != new.page },
        resolve = { l, c -> if (l.modifiedAt > c.modifiedAt) l else c },
    ).values.sortedBy { it.gid }

    /**
     * Quick searches (by name) and filters (by mode and text): sets with baseline, fields of an entry
     * changed on both sides resolved by time. Quick search order follows the cloud unless only this
     * device reordered them; entries only known locally are appended.
     */
    fun mergeQuickSearch(
        local: SyncQuickSearchSection,
        cloud: SyncQuickSearchSection,
        baseline: SyncQuickSearchSection?,
    ): SyncQuickSearchSection {
        val quickSearches = mergeKeyed(
            local = local.quickSearches.associateBy { it.name },
            cloud = cloud.quickSearches.associateBy { it.name },
            baseline = baseline?.quickSearches?.associateBy { it.name },
            changed = { old, new -> !old.sameContent(new) },
            resolve = { l, c -> if (l.modifiedAt > c.modifiedAt) l else c },
        )
        val baselineOrder = baseline?.quickSearches?.map { it.name }
        val localOrder = local.quickSearches.map { it.name }
        val cloudOrder = cloud.quickSearches.map { it.name }
        val localReordered = baselineOrder != null && localOrder.filter { it in baselineOrder } != baselineOrder.filter { it in localOrder }
        val cloudReordered = baselineOrder != null && cloudOrder.filter { it in baselineOrder } != baselineOrder.filter { it in cloudOrder }
        val order = LinkedHashSet<String>().apply {
            if (localReordered && !cloudReordered) {
                addAll(localOrder)
                addAll(cloudOrder)
            } else {
                addAll(cloudOrder)
                addAll(localOrder)
            }
        }
        val filters = mergeKeyed(
            local = local.filters.associateBy { it.key },
            cloud = cloud.filters.associateBy { it.key },
            baseline = baseline?.filters?.associateBy { it.key },
            changed = { old, new -> old.enable != new.enable },
            resolve = { l, c -> if (l.modifiedAt > c.modifiedAt) l else c },
        )
        return SyncQuickSearchSection(
            quickSearches = order.mapNotNull { quickSearches[it] },
            filters = filters.values.sortedWith(compareBy({ it.mode }, { it.text })),
        )
    }

    /** Search suggestions: union with baseline deletions, the later date is kept, newest [limit] entries. */
    fun mergeSuggestions(
        local: List<SyncSuggestion>,
        cloud: List<SyncSuggestion>,
        baseline: List<SyncSuggestion>?,
        limit: Int = SUGGESTION_LIMIT,
    ) = mergeKeyed(
        local = local.associateBy { it.query },
        cloud = cloud.associateBy { it.query },
        baseline = baseline?.associateBy { it.query },
        changed = { old, new -> old.date != new.date },
        resolve = { l, c -> if (l.date > c.date) l else c },
    ).values.sortedWith(compareByDescending<SyncSuggestion> { it.date }.thenBy { it.query }).take(limit)

    /** What importing downloads of another device into this one does. */
    data class DownloadImportPlan(
        val downloads: List<SyncDownload>,
        val galleries: Map<Long, SyncGallery>,
        val newLabels: List<String>,
        val skipped: Int,
    )

    /**
     * Downloads of another device selected by the user: gids this device already has are skipped
     * untouched, labels missing here are created. Records without gallery metadata cannot be imported.
     */
    fun planDownloadImport(
        remote: DeviceDownloads,
        selected: Collection<Long>,
        localGids: Set<Long>,
        localLabels: Collection<String>,
    ): DownloadImportPlan {
        val galleries = remote.galleries.associateBy { it.gid }
        val wanted = selected.toSet()
        val candidates = remote.downloads.filter { it.gid in wanted }
        val downloads = candidates.filter { it.gid !in localGids && it.gid in galleries }
        val labelOrder = remote.labels.sortedBy { it.position }.map { it.label }
        val usedLabels = downloads.mapNotNullTo(LinkedHashSet()) { it.label }
        val newLabels = (labelOrder.filter { it in usedLabels } + usedLabels).distinct().filter { it !in localLabels }
        return DownloadImportPlan(
            downloads = downloads,
            galleries = downloads.associate { it.gid to galleries.getValue(it.gid) },
            newLabels = newLabels,
            skipped = candidates.size - downloads.size,
        )
    }

    /**
     * Subscription to another device: only gids the remote device gained since the last import are
     * imported, so deletions on either device never propagate and a record deleted here after being
     * imported is not imported again.
     *
     * @return the gids to import and the new set of known gids of that device
     */
    fun subscriptionImports(remote: DeviceDownloads, known: Set<Long>): Pair<List<Long>, Set<Long>> {
        val remoteGids = remote.downloads.map { it.gid }
        val added = remoteGids.filter { it !in known }
        return added to (known + remoteGids)
    }
}
