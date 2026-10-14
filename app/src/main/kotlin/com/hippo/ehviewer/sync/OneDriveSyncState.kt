package com.hippo.ehviewer.sync

import com.ehviewer.core.preferences.DataStorePreferences
import com.ehviewer.core.preferences.PrefDelegate
import kotlinx.serialization.json.Json

/**
 * Sync options and bookkeeping, kept in their own DataStore (`onedrive_sync`) so neither the database schema
 * nor the synced settings change. Excluded from cloud backups: it holds the id of this device.
 */
object OneDriveSyncState : DataStorePreferences("onedrive_sync") {
    // Account
    val accountId = stringOrNullPref("account_id")
    val accountName = stringOrNullPref("account_name")

    // This device, the id survives account switches
    val deviceId = stringOrNullPref("device_id")
    val deviceName = stringOrNullPref("device_name")

    // Categories
    val syncSettings = boolPref("sync_settings", true)
    val syncFavorites = boolPref("sync_favorites", true)

    // The old "history and progress" switch keeps its key for the reading progress
    val syncProgress = boolPref("sync_history", true)

    /** The list of galleries read: off unless turned on, only the reading progress is synced. */
    val syncHistory = boolPref("sync_history_list", false)
    val syncQuickSearch = boolPref("sync_quick_search", true)
    val syncSuggestions = boolPref("sync_suggestions", true)
    val uploadDownloads = boolPref("upload_downloads", true)

    // Favorites index for other apps, see FavoritesIndexSync
    val exportFavoritesIndex = boolPref("export_favorites_index", true)
    val favoritesIndexETag = stringOrNullPref("favorites_index_etag")

    /** The cloud favorites changed since the index was last uploaded, see OneDriveSync.markFavoritesIndexChanged. */
    val favoritesIndexDirty = boolPref("favorites_index_dirty", false)

    /** A read of all cloud favorites slots was asked and not started yet, see CloudFavoritesWork. */
    val cloudFavoritesFullRequested = boolPref("cloud_favorites_full_requested", false)

    /** Why CloudFavoritesWork stopped last time, null when it ended well. */
    val cloudFavoritesWorkError = stringOrNullPref("cloud_favorites_work_error")

    /** The index was deleted after sharing was turned off; cleared by the next upload. */
    val favoritesIndexRemoved = boolPref("favorites_index_removed", false)

    // Automatic sync
    val autoSync = boolPref("auto_sync", true)
    val autoSyncIntervalMinutes = intPref("auto_sync_interval_minutes", 360)
    val syncOnChange = boolPref("sync_on_change", true)
    val wifiOnly = boolPref("wifi_only", false)

    /** A bar below the search bar of the gallery lists shows a running sync, see OneDriveSyncProgressBanner. */
    val showProgressBar = boolPref("show_progress_bar", true)

    /** Cloud slot whose new galleries go to the downloads on this device, -1 for none, see [AutoDownload]. */
    val autoDownloadSlot = intPref("auto_download_slot", -1)

    // Result of the last sync
    val lastSyncTime = longPref("last_sync_time", 0)
    val lastSyncSummary = stringOrNullPref("last_sync_summary")
    val lastSyncFailed = boolPref("last_sync_failed", false)

    // Per category: ETag of the cloud copy cached in files/onedrive_sync/cache, local change flag and time
    private val eTags = SyncCategory.entries.associateWith { stringOrNullPref("etag_${it.name.lowercase()}") }
    private val dirty = SyncCategory.entries.associateWith { boolPref("dirty_${it.name.lowercase()}", false) }
    private val changedAt = SyncCategory.entries.associateWith { longPref("changed_at_${it.name.lowercase()}", 0) }
    fun eTag(category: SyncCategory) = eTags.getValue(category)
    fun dirty(category: SyncCategory) = dirty.getValue(category)
    fun changedAt(category: SyncCategory) = changedAt.getValue(category)

    /** Time each synced preference last changed on this device: key -> millis. */
    val settingsModifiedAt = stringPref("settings_modified_at", "{}")

    // Download list of this device
    val downloadsDirty = boolPref("downloads_dirty", true)
    val deviceFileETag = stringOrNullPref("device_file_etag")

    /** ETags of the cached download lists of other devices: device id -> ETag. */
    val remoteDeviceETags = stringPref("remote_device_etags", "{}")

    /** Subscribed devices: device id -> gids of that device already seen (imported or skipped). */
    val subscriptions = stringPref("subscriptions", "{}")

    val isSignedIn: Boolean
        get() = accountId.value != null

    fun isEnabled(category: SyncCategory): Boolean = categoryPref(category).value

    fun categoryPref(category: SyncCategory): PrefDelegate<Boolean> = when (category) {
        SyncCategory.SETTINGS -> syncSettings
        SyncCategory.FAVORITES -> syncFavorites
        SyncCategory.HISTORY -> syncHistory
        SyncCategory.PROGRESS -> syncProgress
        SyncCategory.QUICK_SEARCH -> syncQuickSearch
        SyncCategory.SUGGESTIONS -> syncSuggestions
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun readLongMap(pref: PrefDelegate<String>): Map<String, Long> = runCatching { json.decodeFromString<Map<String, Long>>(pref.value) }.getOrDefault(emptyMap())

    fun readStringMap(pref: PrefDelegate<String>): Map<String, String> = runCatching { json.decodeFromString<Map<String, String>>(pref.value) }.getOrDefault(emptyMap())

    fun readSubscriptions(): Map<String, Set<Long>> = runCatching { json.decodeFromString<Map<String, Set<Long>>>(subscriptions.value) }.getOrDefault(emptyMap())

    fun encodeLongMap(map: Map<String, Long>) = json.encodeToString(map)

    fun encodeStringMap(map: Map<String, String>) = json.encodeToString(map)

    fun encodeSubscriptions(map: Map<String, Set<Long>>) = json.encodeToString(map)
}
