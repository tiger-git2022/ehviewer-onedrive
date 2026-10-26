package com.hippo.ehviewer.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

class OneDriveSyncMergerTest {
    private fun gallery(gid: Long, title: String? = "g$gid", pages: Int = 0) = SyncGallery(gid = gid, token = "t$gid", title = title, pages = pages)
    private fun record(gid: Long, time: Long, gallery: SyncGallery = gallery(gid)) = SyncGalleryRecord(gid, time, gallery)
    private fun setting(value: Int, modifiedAt: Long) = SyncSetting(SyncSetting.TYPE_INT, JsonPrimitive(value), modifiedAt)
    private fun List<SyncGalleryRecord>.gids() = map { it.gid }.toSet()

    @Test
    fun firstSyncIsUnion() {
        val local = listOf(record(1, 10), record(2, 20))
        val cloud = listOf(record(2, 25), record(3, 30))
        val favorites = OneDriveSyncMerger.mergeFavorites(local, cloud, baseline = null)
        assertEquals(setOf(1L, 2L, 3L), favorites.gids())

        val history = OneDriveSyncMerger.mergeHistory(listOf(record(1, 10)), emptyList(), baseline = null)
        assertEquals(setOf(1L), history.gids())

        // Without a baseline nothing is deleted even if one side lacks entries
        val suggestions = OneDriveSyncMerger.mergeSuggestions(
            local = listOf(SyncSuggestion("a", 1)),
            cloud = listOf(SyncSuggestion("b", 2)),
            baseline = null,
        )
        assertEquals(listOf("b", "a"), suggestions.map { it.query })
    }

    @Test
    fun onlyOneSideAddsOrDeletes() {
        val baseline = listOf(record(1, 10), record(2, 20))
        // Local added 3, cloud unchanged
        assertEquals(setOf(1L, 2L, 3L), OneDriveSyncMerger.mergeFavorites(baseline + record(3, 30), baseline, baseline).gids())
        // Cloud added 3, local unchanged
        assertEquals(setOf(1L, 2L, 3L), OneDriveSyncMerger.mergeFavorites(baseline, baseline + record(3, 30), baseline).gids())
        // Local deleted 1
        assertEquals(setOf(2L), OneDriveSyncMerger.mergeFavorites(listOf(record(2, 20)), baseline, baseline).gids())
        // Cloud deleted 2
        assertEquals(setOf(1L), OneDriveSyncMerger.mergeFavorites(baseline, listOf(record(1, 10)), baseline).gids())
        // Both deleted 1, local added 4, cloud added 5
        assertEquals(
            setOf(2L, 4L, 5L),
            OneDriveSyncMerger.mergeFavorites(listOf(record(2, 20), record(4, 40)), listOf(record(2, 20), record(5, 50)), baseline).gids(),
        )
    }

    @Test
    fun additionWinsOverDeletion() {
        val baseline = listOf(record(1, 10))
        // Local removed 1, cloud removed and favorited it again (new time): it stays
        val merged = OneDriveSyncMerger.mergeFavorites(emptyList(), listOf(record(1, 50)), baseline)
        assertEquals(setOf(1L), merged.gids())
        // Both sides added the same gallery: kept once with the earlier time and the more complete metadata
        val both = OneDriveSyncMerger.mergeFavorites(
            local = listOf(record(7, 70, gallery(7, pages = 12))),
            cloud = listOf(record(7, 60, gallery(7))),
            baseline = emptyList(),
        ).single()
        assertEquals(60, both.time)
        assertEquals(12, both.gallery.pages)
    }

    @Test
    fun historyKeepsLatestTimeAndSyncsDeletions() {
        val baseline = listOf(record(1, 10), record(2, 20))
        val merged = OneDriveSyncMerger.mergeHistory(
            local = listOf(record(1, 15), record(2, 20)),
            cloud = listOf(record(1, 18), record(2, 20)),
            baseline = baseline,
        )
        assertEquals(18, merged.first { it.gid == 1L }.time)
        // Clearing history on one device clears it everywhere
        assertTrue(OneDriveSyncMerger.mergeHistory(emptyList(), baseline, baseline).isEmpty())
        // ...except entries read again on the other device since the last sync
        assertEquals(setOf(2L), OneDriveSyncMerger.mergeHistory(emptyList(), listOf(record(1, 10), record(2, 99)), baseline).gids())
    }

    @Test
    fun progressNewerWins() {
        val baseline = listOf(SyncProgress(1, 5, 100))
        val merged = OneDriveSyncMerger.mergeProgress(
            local = listOf(SyncProgress(1, 8, 300)),
            cloud = listOf(SyncProgress(1, 12, 200)),
            baseline = baseline,
        )
        assertEquals(8, merged.single().page)
        // Only the cloud changed it
        assertEquals(12, OneDriveSyncMerger.mergeProgress(baseline, listOf(SyncProgress(1, 12, 200)), baseline).single().page)
    }

    @Test
    fun settingsNewerWins() {
        val merged = OneDriveSyncMerger.mergeSettings(
            local = mapOf("theme_2" to setting(1, 200), "thumb_columns" to setting(4, 100), "local_only" to setting(1, 0)),
            cloud = mapOf("theme_2" to setting(2, 150), "thumb_columns" to setting(5, 300), "cloud_only" to setting(1, 0)),
        )
        assertEquals(setting(1, 200), merged["theme_2"])
        assertEquals(setting(5, 300), merged["thumb_columns"])
        assertEquals(setOf("theme_2", "thumb_columns", "local_only", "cloud_only"), merged.keys)
        // Never touched on either side: the cloud wins so a new device adopts the synced settings
        assertEquals(setting(9, 0), OneDriveSyncMerger.mergeSettings(mapOf("k" to setting(3, 0)), mapOf("k" to setting(9, 0)))["k"])
        // A removal is a change too
        val removed = SyncSetting(SyncSetting.TYPE_REMOVED, null, 500)
        assertEquals(removed, OneDriveSyncMerger.mergeSettings(mapOf("k" to removed), mapOf("k" to setting(9, 400)))["k"])
    }

    @Test
    fun blacklistedSettingsAreNeverUploaded() {
        val local = mapOf(
            "image_path" to SyncSetting(SyncSetting.TYPE_STRING, JsonPrimitive("/sdcard/Download"), 100),
            "archive_passwds" to SyncSetting(SyncSetting.TYPE_STRING_SET, null, 100),
            "display_name" to SyncSetting(SyncSetting.TYPE_STRING, JsonPrimitive("me"), 100),
            "fav_cat_0" to SyncSetting(SyncSetting.TYPE_STRING, JsonPrimitive("Fav 0"), 100),
            "theme_2" to setting(1, 100),
        )
        val merged = OneDriveSyncMerger.mergeSettings(local, emptyMap())
        assertEquals(setOf("theme_2"), merged.keys)
        assertFalse(OneDriveSyncMerger.isSyncableSetting("image_scheme"))
        assertFalse(OneDriveSyncMerger.isSyncableSetting("last_update_time"))
        assertFalse(OneDriveSyncMerger.isSyncableSetting("comment_translate_api_key"))
        assertTrue(OneDriveSyncMerger.isSyncableSetting("pref_default_reading_mode_key"))
    }

    @Test
    fun newerCloudVersionIsRejected() {
        OneDriveSyncMerger.checkVersion(SYNC_FORMAT_VERSION)
        assertFailsWith<SyncVersionException> { OneDriveSyncMerger.checkVersion(SYNC_FORMAT_VERSION + 1) }
    }

    @Test
    fun quickSearchOrderAndFilters() {
        fun qs(name: String, keyword: String = name, modifiedAt: Long = 0) = SyncQuickSearch(name = name, keyword = keyword, modifiedAt = modifiedAt)
        val baseline = SyncQuickSearchSection(
            quickSearches = listOf(qs("a"), qs("b")),
            filters = listOf(SyncFilter(0, "x", enable = true)),
        )
        val local = SyncQuickSearchSection(
            quickSearches = listOf(qs("a", "a2", 300), qs("b"), qs("local")),
            filters = listOf(SyncFilter(0, "x", enable = false, modifiedAt = 300), SyncFilter(2, "tag")),
        )
        val cloud = SyncQuickSearchSection(
            quickSearches = listOf(qs("cloud"), qs("b"), qs("a", "a3", 200)),
            filters = listOf(SyncFilter(0, "x", enable = true)),
        )
        val merged = OneDriveSyncMerger.mergeQuickSearch(local, cloud, baseline)
        // Cloud order, local-only appended
        assertEquals(listOf("cloud", "b", "a", "local"), merged.quickSearches.map { it.name })
        // Both edited "a": the newer edit wins
        assertEquals("a2", merged.quickSearches.first { it.name == "a" }.keyword)
        assertEquals(listOf(SyncFilter(0, "x", false, 300), SyncFilter(2, "tag")), merged.filters)
        // Removing a filter locally removes it from the cloud
        val removed = OneDriveSyncMerger.mergeQuickSearch(local.copy(filters = emptyList()), cloud, baseline)
        assertTrue(removed.filters.isEmpty())
    }

    @Test
    fun suggestionsKeepLatestDateAndLimit() {
        val merged = OneDriveSyncMerger.mergeSuggestions(
            local = listOf(SyncSuggestion("a", 5), SyncSuggestion("b", 1)),
            cloud = listOf(SyncSuggestion("a", 3), SyncSuggestion("c", 4)),
            baseline = null,
            limit = 2,
        )
        assertEquals(listOf(SyncSuggestion("a", 5), SyncSuggestion("c", 4)), merged)
    }

    private val remoteDevice = DeviceDownloads(
        deviceId = "phone",
        deviceName = "Phone",
        labels = listOf(SyncLabel("Later", 1), SyncLabel("Art", 0)),
        downloads = listOf(
            SyncDownload(1, label = "Art", finished = true),
            SyncDownload(2, label = "Later"),
            SyncDownload(3),
            SyncDownload(4, label = "Art"),
        ),
        galleries = listOf(gallery(1), gallery(2), gallery(3), gallery(4)),
    )

    @Test
    fun importSkipsExistingAndCreatesMissingLabels() {
        val plan = OneDriveSyncMerger.planDownloadImport(
            remote = remoteDevice,
            selected = listOf(1, 2, 3),
            localGids = setOf(3),
            localLabels = listOf("Later"),
        )
        assertEquals(listOf(1L, 2L), plan.downloads.map { it.gid })
        assertEquals(1, plan.skipped)
        assertEquals(listOf("Art"), plan.newLabels)
        assertEquals(setOf(1L, 2L), plan.galleries.keys)
    }

    @Test
    fun subscriptionOnlyImportsNewRecords() {
        // Subscribed when the device had 1 and 2
        val known = setOf(1L, 2L)
        val (imports, newKnown) = OneDriveSyncMerger.subscriptionImports(remoteDevice, known)
        assertEquals(listOf(3L, 4L), imports)
        assertEquals(setOf(1L, 2L, 3L, 4L), newKnown)

        // The user deleted 3 here after it was imported: it is known, so the next sync does not import it again
        val (again, _) = OneDriveSyncMerger.subscriptionImports(remoteDevice, newKnown)
        assertTrue(again.isEmpty())

        // The other device deleted 1: nothing is deleted here and nothing is imported
        val shrunk = remoteDevice.copy(downloads = remoteDevice.downloads.filter { it.gid != 1L })
        val (none, stillKnown) = OneDriveSyncMerger.subscriptionImports(shrunk, newKnown)
        assertTrue(none.isEmpty())
        assertTrue(1L in stillKnown)
    }

    @Test
    fun baselineGalleriesAreNeverTaken() {
        // Fork: a sync reads only the gid and the time of a baseline, see OneDriveSync.readBaselineOrNull
        val local = listOf(record(1, 10), record(2, 20), record(4, 41))
        val cloud = listOf(record(1, 10), record(3, 30), record(4, 40, gallery(4, pages = 9)), record(5, 50))
        val baseline = listOf(record(1, 10), record(2, 20), record(3, 30), record(4, 40))
        val stamps = baseline.map { SyncGalleryRecord(it.gid, it.time, SyncGallery(gid = 0, token = "")) }
        assertEquals(OneDriveSyncMerger.mergeFavorites(local, cloud, baseline), OneDriveSyncMerger.mergeFavorites(local, cloud, stamps))
        assertEquals(OneDriveSyncMerger.mergeHistory(local, cloud, baseline), OneDriveSyncMerger.mergeHistory(local, cloud, stamps))
    }
}
