package com.hippo.ehviewer.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FavoritesIndexMergerTest {
    private val names = List(CLOUD_SLOT_COUNT) { "Slot $it" }
    private fun gallery(gid: Long, slot: Int = NO_CLOUD_SLOT, title: String? = "g$gid", titleJpn: String? = null) = IndexedGallery(gid = gid, token = "t$gid", title = title, titleJpn = titleJpn, slot = slot)

    @Test
    fun slotOfAllPageNeedsUniqueName() {
        assertEquals(3, FavoritesIndexMerger.slotOf("Slot 3", names))
        assertEquals(NO_CLOUD_SLOT, FavoritesIndexMerger.slotOf(null, names))
        assertEquals(NO_CLOUD_SLOT, FavoritesIndexMerger.slotOf("Other", names))
        val duplicated = names.toMutableList().apply { this[5] = "Slot 3" }
        assertEquals(NO_CLOUD_SLOT, FavoritesIndexMerger.slotOf("Slot 3", duplicated))
    }

    @Test
    fun upsertKeepsKnownFieldsAndIgnoresNonCloudSlots() {
        val cloud = CloudFavorites(galleries = listOf(gallery(1, slot = 0, titleJpn = "日本語")))
        val updated = FavoritesIndexMerger.upsert(cloud, listOf(gallery(1, slot = 2), gallery(2, slot = NO_CLOUD_SLOT)), now = 5)
        assertEquals(listOf(gallery(1, slot = 2, titleJpn = "日本語")), updated.galleries)
        assertEquals(5, updated.updatedAt)
        // Nothing new: same instance, the time does not move
        assertSame(updated, FavoritesIndexMerger.upsert(updated, listOf(gallery(1, slot = 2)), now = 9))
    }

    @Test
    fun moveAndRemove() {
        val cloud = CloudFavorites(galleries = listOf(gallery(1, slot = 0), gallery(2, slot = 1)))
        val moved = FavoritesIndexMerger.move(cloud, listOf(1, 3), slot = 4, now = 7)
        assertEquals(listOf(4, 1), moved.galleries.map { it.slot })
        val removed = FavoritesIndexMerger.move(moved, listOf(2), slot = NO_CLOUD_SLOT, now = 8)
        assertEquals(listOf(1L), removed.galleries.map { it.gid })
        assertEquals(8, removed.updatedAt)
    }

    @Test
    fun refreshReplacesListAndCountsDecideTheNextOne() {
        val cloud = CloudFavorites(
            updatedAt = 1,
            refreshedAt = 1,
            slotNames = names,
            galleries = listOf(gallery(1, slot = 0), gallery(2, slot = 0)),
            japaneseTitles = mapOf(1L to "一"),
        )
        val refreshed = FavoritesIndexMerger.replaceAll(cloud, listOf(gallery(1, slot = 3), gallery(3, slot = 3), gallery(3, slot = 3)), names, now = 100)
        assertEquals(listOf(1L, 3L), refreshed.galleries.map { it.gid })
        assertEquals(100, refreshed.refreshedAt)
        assertEquals(mapOf(1L to "一"), refreshed.japaneseTitles)

        val counts = List(CLOUD_SLOT_COUNT) { if (it == 3) 2 else 0 }
        assertFalse(FavoritesIndexMerger.needsRefresh(refreshed, counts, now = 200))
        assertTrue(FavoritesIndexMerger.needsRefresh(refreshed, counts.mapIndexed { i, c -> if (i == 0) 1 else c }, now = 200))
        assertTrue(FavoritesIndexMerger.needsRefresh(refreshed, counts, now = 100 + FavoritesIndexMerger.REFRESH_MAX_AGE_MILLIS + 1))
        assertTrue(FavoritesIndexMerger.needsRefresh(CloudFavorites(), List(CLOUD_SLOT_COUNT) { 0 }, now = 1))
    }

    @Test
    fun indexListsEachGalleryOnceWithLocalFlagAndSlot() {
        val cloud = CloudFavorites(
            updatedAt = 3,
            slotNames = names,
            galleries = listOf(gallery(1, slot = 2), gallery(2, slot = 5)),
            japaneseTitles = mapOf(2L to "二", 3L to ""),
        )
        val local = listOf(gallery(1, titleJpn = "一"), gallery(3), gallery(3))
        val index = FavoritesIndexMerger.buildIndex(local, cloud, "dev", "Phone", now = 9)
        assertEquals(
            listOf(
                IndexedGallery(1, "t1", "g1", "一", local = true, slot = 2),
                IndexedGallery(3, "t3", "g3", "", local = true, slot = NO_CLOUD_SLOT),
                IndexedGallery(2, "t2", "g2", "二", local = false, slot = 5),
            ),
            index.galleries,
        )
        assertEquals(names, index.slotNames)
        assertEquals(3, index.cloudUpdatedAt)
        assertTrue(index.sameContent(index.copy(updatedAt = 99)))
        assertEquals(emptyList(), FavoritesIndexMerger.missingJapaneseTitles(index, limit = 10))
    }

    @Test
    fun adoptOnlyNewerCloudPartOfTheSameAccount() {
        val mine = CloudFavorites(userKey = "a", updatedAt = 10, galleries = listOf(gallery(1, slot = 0)), japaneseTitles = mapOf(1L to "一"))
        val older = FavoritesIndex(ehUserKey = "a", cloudUpdatedAt = 5, galleries = listOf(gallery(9, slot = 1)))
        assertSame(mine, FavoritesIndexMerger.adopt(mine, older))
        val otherAccount = FavoritesIndex(ehUserKey = "b", cloudUpdatedAt = 50, galleries = listOf(gallery(9, slot = 1)))
        assertSame(mine, FavoritesIndexMerger.adopt(mine, otherAccount))
        val noAccount = FavoritesIndex(cloudUpdatedAt = 50, galleries = listOf(gallery(9, slot = 1)))
        assertSame(mine, FavoritesIndexMerger.adopt(mine, noAccount))

        val newer = FavoritesIndex(
            ehUserKey = "a",
            cloudUpdatedAt = 20,
            cloudRefreshedAt = 15,
            slotNames = names,
            galleries = listOf(gallery(1, slot = 4).copy(local = true), gallery(2, slot = 1, titleJpn = "二"), gallery(3).copy(local = true)),
        )
        val adopted = FavoritesIndexMerger.adopt(mine, newer)
        assertEquals(listOf(1L to 4, 2L to 1), adopted.galleries.map { it.gid to it.slot })
        assertTrue(adopted.galleries.none { it.local })
        assertEquals(20, adopted.updatedAt)
        assertEquals("a", adopted.userKey)
        assertEquals(15, adopted.refreshedAt)
        assertEquals(mapOf(1L to "一", 2L to "二"), adopted.japaneseTitles)
    }

    @Test
    fun anotherAccountStartsFromAnEmptyList() {
        val cloud = CloudFavorites(userKey = "a", updatedAt = 3, refreshedAt = 3, slotNames = names, galleries = listOf(gallery(1, slot = 0)), japaneseTitles = mapOf(1L to "一"))
        assertSame(cloud, FavoritesIndexMerger.forUser(cloud, "a"))
        val other = FavoritesIndexMerger.forUser(cloud, "b")
        assertEquals(CloudFavorites(userKey = "b", japaneseTitles = mapOf(1L to "一")), other)
        assertTrue(FavoritesIndexMerger.needsRefresh(other, List(CLOUD_SLOT_COUNT) { 0 }, now = 4))
        assertEquals("b", FavoritesIndexMerger.buildIndex(emptyList(), other, "dev", "", now = 5).ehUserKey)
    }

    @Test
    fun titlesAreAddedAndPruned() {
        val cloud = CloudFavorites(galleries = listOf(gallery(1, slot = 0)))
        val withTitles = FavoritesIndexMerger.withJapaneseTitles(cloud, mapOf(1L to "一", 2L to ""))
        assertEquals(mapOf(1L to "一", 2L to ""), withTitles.japaneseTitles)
        val index = FavoritesIndexMerger.buildIndex(emptyList(), withTitles, "dev", "", now = 1)
        assertEquals(mapOf(1L to "一"), FavoritesIndexMerger.pruneTitles(withTitles, index).japaneseTitles)
    }

    @Test
    fun changedGidsAreTheAddedRemovedAndMovedOnes() {
        val before = CloudFavorites(galleries = listOf(gallery(1, slot = 0), gallery(2, slot = 1), gallery(3, slot = 2)))
        val after = CloudFavorites(galleries = listOf(gallery(1, slot = 0, title = "renamed"), gallery(2, slot = 4), gallery(4, slot = 0)))
        assertEquals(setOf(2L, 3L, 4L), FavoritesIndexMerger.changedGids(before, after))
        assertEquals(emptySet(), FavoritesIndexMerger.changedGids(before, before))
    }

    @Test
    fun unfinishedRefreshOnlyAddsAndLeavesTouchedGalleriesAlone() {
        val cloud = CloudFavorites(galleries = listOf(gallery(1, slot = 0), gallery(2, slot = 5)))
        // 2 was moved to slot 5 in the app after its page was read in slot 1
        val pages = listOf(gallery(2, slot = 1), gallery(3, slot = 1))
        val partial = FavoritesIndexMerger.withRefreshPages(cloud, pages, touched = setOf(2L), now = 10)
        assertEquals(listOf(1L to 0, 2L to 5, 3L to 1), partial.galleries.map { it.gid to it.slot })
        // Nothing removed and not marked as refreshed before the last page
        assertEquals(0, partial.refreshedAt)
    }

    @Test
    fun completedRefreshReplacesTheListButKeepsTouchedGalleries() {
        val cloud = CloudFavorites(
            slotNames = names,
            galleries = listOf(gallery(1, slot = 0), gallery(2, slot = 5), gallery(9, slot = 3)),
        )
        // 1 is gone on the website, 2 moved in the app after its page was read, 4 removed in the app after its page
        val pages = listOf(gallery(2, slot = 1), gallery(3, slot = 1), gallery(4, slot = 2), gallery(9, slot = 3))
        val done = FavoritesIndexMerger.completeRefresh(cloud, pages, touched = setOf(2L, 4L), names, now = 20)
        assertEquals(listOf(3L to 1, 9L to 3, 2L to 5), done.galleries.map { it.gid to it.slot })
        assertEquals(20, done.refreshedAt)
    }
}
