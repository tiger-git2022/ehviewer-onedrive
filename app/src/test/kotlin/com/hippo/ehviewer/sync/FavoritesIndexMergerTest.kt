package com.hippo.ehviewer.sync

import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals(emptyList(), FavoritesIndexMerger.slotsToRefresh(refreshed, counts))
        // Only the slots whose counts differ, however old the list
        assertEquals(
            listOf(0, 3),
            FavoritesIndexMerger.slotsToRefresh(
                refreshed,
                counts.mapIndexed { i, c ->
                    if (i == 0) {
                        1
                    } else if (i == 3) {
                        1
                    } else {
                        c
                    }
                },
            ),
        )
        // Never read completely: all slots
        assertEquals(ALL_CLOUD_SLOTS, FavoritesIndexMerger.slotsToRefresh(CloudFavorites(), List(CLOUD_SLOT_COUNT) { 0 }))
        assertEquals(emptyList(), FavoritesIndexMerger.slotsToRefresh(refreshed, emptyList()))
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
        assertEquals(ALL_CLOUD_SLOTS, FavoritesIndexMerger.slotsToRefresh(other, List(CLOUD_SLOT_COUNT) { 0 }))
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
    fun defaultTitlesJoinTheJapaneseOnes() {
        // The list page gave the Japanese title (account setting), the API both
        val jp = "[ミルクレープ (チビゴン)] SNSで裏垢特定した人妻をハメまくった話"
        val en = "[Mille-Crepe (Chibigon)] SNS de Uraaka Tokutei shita Hitozuma o Hamemakutta Hanashi"
        val cloud = CloudFavorites(
            galleries = listOf(gallery(1, slot = 0, title = jp), gallery(2, slot = 0, title = en), gallery(3, slot = 0, title = "removed")),
        )
        val withTitles = FavoritesIndexMerger.withJapaneseTitles(cloud, mapOf(1L to jp, 2L to "日本語", 3L to ""), mapOf(1L to en, 2L to en, 3L to ""))
        val index = FavoritesIndexMerger.buildIndex(emptyList(), withTitles, "dev", "", now = 1)
        assertEquals(
            listOf(
                gallery(1, slot = 0, title = en, titleJpn = jp).copy(titlesLookedUp = true),
                gallery(2, slot = 0, title = en, titleJpn = "日本語").copy(titlesLookedUp = true),
                // The API has nothing: its list title is not shared as a default one
                gallery(3, slot = 0, title = "removed", titleJpn = ""),
            ),
            index.galleries,
        )
        assertEquals(emptyList(), FavoritesIndexMerger.missingJapaneseTitles(index, 10, withTitles.defaultTitles))
        assertEquals(mapOf(1L to en), FavoritesIndexMerger.pruneTitles(withTitles, index.copy(galleries = index.galleries.take(1))).defaultTitles)
    }

    @Test
    fun removedGalleriesTakeTheTitleOfTheirArchivedRawCopy() {
        val jp = "[ミルクレープ (チビゴン)] SNSで裏垢特定した人妻をハメまくった話。 [中国翻訳] [小衣ゆうき个人机翻润色]"
        // The API had nothing for it: both titles looked up as empty
        val cloud = CloudFavorites(
            galleries = listOf(gallery(1, slot = 0, title = jp), gallery(2, slot = 0, title = "ok")),
            japaneseTitles = mapOf(1L to "", 2L to "二"),
            defaultTitles = mapOf(1L to "", 2L to "two"),
        )
        val index = FavoritesIndexMerger.buildIndex(emptyList(), cloud, "dev", "", now = 1)
        assertEquals(listOf(1L), FavoritesIndexMerger.missingPandaTitles(index, cloud).map { it.gid })
        val removed = index.galleries.first()
        assertEquals("SNSで裏垢特定した人妻をハメまくった話。", FavoritesIndexMerger.pandaQuery(removed))
        val archives = listOf(
            "[Other] SNS" to "[他] SNSで裏垢特定した人妻をハメまくった話 2",
            "[Mille-Crepe (Chibigon)] SNS de Uraaka Tokutei shita Hitozuma o Hamemakutta Hanashi." to "[ミルクレープ (チビゴン)] SNSで裏垢特定した人妻をハメまくった話。",
        )
        val en = FavoritesIndexMerger.pandaDefaultTitle(removed, archives)
        assertEquals("[Mille-Crepe (Chibigon)] SNS de Uraaka Tokutei shita Hitozuma o Hamemakutta Hanashi.", en)
        assertEquals("", FavoritesIndexMerger.pandaDefaultTitle(removed, archives.take(1)))
        // Round brackets in the title tell the parts apart, a leading event group does not count
        val second = gallery(3, slot = 0, title = "(C104) [サークル] 作品 (後編) [中国翻訳]")
        val parts = listOf("[Circle] Sakuhin (Zenpen)" to "[サークル] 作品 (前編)", "[Circle] Sakuhin (Kouhen)" to "(C104) [サークル] 作品 (後編)")
        assertEquals("[Circle] Sakuhin (Kouhen)", FavoritesIndexMerger.pandaDefaultTitle(second, parts))
        val withPanda = FavoritesIndexMerger.withPandaTitles(cloud, mapOf(1L to en))
        val rebuilt = FavoritesIndexMerger.buildIndex(emptyList(), withPanda, "dev", "", now = 2)
        // Both titles listed, not marked as the API's
        assertEquals(gallery(1, slot = 0, title = en, titleJpn = jp), rebuilt.galleries.first())
        assertEquals(emptyList(), FavoritesIndexMerger.missingPandaTitles(rebuilt, withPanda))
    }

    @Test
    fun aKnownMissingJapaneseTitleStaysEmpty() {
        // Renamed since the list was read: the API's default title replaces it, there is no Japanese one
        val gallery = gallery(1, slot = 0, title = "old name", titleJpn = "")
        assertEquals(gallery(1, slot = 0, title = "new name", titleJpn = ""), FavoritesIndexMerger.withDefaultTitle(gallery, "new name"))
        assertEquals(gallery(1, slot = 0, title = "new name", titleJpn = "old name"), FavoritesIndexMerger.withDefaultTitle(gallery.copy(titleJpn = null), "new name"))
    }

    @Test
    fun adoptingANewerListKeepsTheDefaultTitles() {
        val mine = CloudFavorites(userKey = "a", updatedAt = 1, galleries = listOf(gallery(1, slot = 0)), defaultTitles = mapOf(1L to "one"))
        val remote = FavoritesIndex(ehUserKey = "a", cloudUpdatedAt = 5, galleries = listOf(gallery(1, slot = 2)))
        val adopted = FavoritesIndexMerger.withRemoteTitles(FavoritesIndexMerger.adopt(mine, remote), remote)
        assertEquals(listOf(2), adopted.galleries.map { it.slot })
        assertEquals(mapOf(1L to "one"), adopted.defaultTitles)
    }

    @Test
    fun cloudFavoritesWithoutDefaultTitleAreLookedUpAgain() {
        // Looked up before the default titles were kept: the Japanese title is known, the default one is not
        val index = FavoritesIndex(galleries = listOf(gallery(1, slot = 0, titleJpn = "一"), gallery(2, slot = 0, titleJpn = "二"), gallery(3, titleJpn = "三").copy(local = true)))
        assertEquals(listOf(2L), FavoritesIndexMerger.missingJapaneseTitles(index, 10, mapOf(1L to "one")).map { it.gid })
        assertEquals(emptyList(), FavoritesIndexMerger.missingJapaneseTitles(index, 10))
    }

    @Test
    fun anotherDeviceSharesItsTitles() {
        val remote = FavoritesIndex(
            ehUserKey = "other",
            galleries = listOf(
                gallery(1, slot = 0, title = "one", titleJpn = "一").copy(titlesLookedUp = true),
                // Looked up by an older version: only the Japanese title is known to be the API's
                gallery(2, slot = 0, title = "二", titleJpn = "二"),
                gallery(3, slot = 0),
            ),
        )
        val mine = CloudFavorites(userKey = "a", updatedAt = 10, galleries = listOf(gallery(1, slot = 0), gallery(2, slot = 0), gallery(3, slot = 0)))
        val shared = FavoritesIndexMerger.withRemoteTitles(mine, remote)
        assertEquals(mapOf(1L to "一", 2L to "二"), shared.japaneseTitles)
        assertEquals(mapOf(1L to "one"), shared.defaultTitles)
        // Its own titles stay
        assertSame(shared, FavoritesIndexMerger.withRemoteTitles(shared, remote.copy(galleries = listOf(gallery(1, title = "x", titleJpn = "y").copy(titlesLookedUp = true)))))
        assertEquals(listOf(2L, 3L), FavoritesIndexMerger.stillMissing(remote.galleries, shared).map { it.gid })
    }

    @Test
    fun lookupsFromTheEndKeepLocalFavoritesFirst() {
        val index = FavoritesIndex(galleries = listOf(gallery(1, slot = 0), gallery(2, slot = 1), gallery(3, slot = 0), gallery(4).copy(local = true)))
        assertEquals(listOf(4L, 3L, 2L, 1L), FavoritesIndexMerger.missingJapaneseTitles(index, 10, fromEnd = true).map { it.gid })
        assertEquals(listOf(4L, 3L), FavoritesIndexMerger.missingJapaneseTitles(index, 2, fromEnd = true).map { it.gid })
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

    @Test
    fun missingTitlesTakeEverySlotInTurn() {
        val index = FavoritesIndex(
            galleries = listOf(
                gallery(1, slot = 0),
                gallery(2, slot = 0),
                gallery(3, slot = 0),
                gallery(4, slot = 1),
                gallery(5, slot = 1, titleJpn = "五"),
                gallery(6, slot = 2),
                gallery(7).copy(local = true),
            ),
        )
        assertEquals(listOf(7L, 1L, 4L, 6L, 2L, 3L), FavoritesIndexMerger.missingJapaneseTitles(index, 10).map { it.gid })
        assertEquals(listOf(7L, 1L, 4L), FavoritesIndexMerger.missingJapaneseTitles(index, 3).map { it.gid })
    }

    @Test
    fun resumedRefreshReadsChangedSlotsAgain() {
        val counts = List(CLOUD_SLOT_COUNT) { if (it < 3) 2 else 0 }
        val checkpoint = CloudRefreshCheckpoint(
            slot = 2,
            next = "cursor",
            galleries = listOf(gallery(1, slot = 0), gallery(2, slot = 1), gallery(3, slot = 2)),
            counts = counts,
        )
        // Nothing changed: goes on where it stopped
        assertEquals(checkpoint, FavoritesIndexMerger.resumeCheckpoint(checkpoint, counts))
        // A slot not read yet changed: also goes on
        val later = counts.mapIndexed { slot, count -> if (slot == 5) 1 else count }
        assertEquals(checkpoint.copy(counts = later), FavoritesIndexMerger.resumeCheckpoint(checkpoint, later))
        // Slot 1 changed on the website: slots 1 and 2 are read again
        val changed = counts.mapIndexed { slot, count -> if (slot == 1) 0 else count }
        val resumed = FavoritesIndexMerger.resumeCheckpoint(checkpoint, changed)
        assertEquals(1, resumed.slot)
        assertEquals(null, resumed.next)
        assertEquals(listOf(1L), resumed.galleries.map { it.gid })
        assertEquals(changed, resumed.counts)
        // A checkpoint without counts starts again
        assertEquals(0, FavoritesIndexMerger.resumeCheckpoint(checkpoint.copy(counts = emptyList()), counts).slot)
    }

    @Test
    fun userMovesCorrectPagesReadBeforeThem() {
        val cloud = CloudFavorites(slotNames = names, galleries = listOf(gallery(1, slot = 0)))
        // 2 and 3 were only in pages not saved yet when the user moved 2 to slot 4 and removed 3
        val pages = listOf(gallery(1, slot = 0), gallery(2, slot = 1), gallery(3, slot = 1))
        val intended = mapOf(2L to 4, 3L to NO_CLOUD_SLOT)
        val partial = FavoritesIndexMerger.withRefreshPages(cloud, pages, setOf(2L, 3L), now = 10, intended = intended)
        assertEquals(listOf(1L to 0, 2L to 4), partial.galleries.map { it.gid to it.slot })
        val done = FavoritesIndexMerger.completeRefresh(cloud, pages, setOf(2L, 3L), names, now = 20, intended = intended)
        assertEquals(setOf(1L to 0, 2L to 4), done.galleries.map { it.gid to it.slot }.toSet())
    }

    @Test
    fun partialRefreshReplacesOnlyItsSlots() {
        val cloud = CloudFavorites(
            refreshedAt = 1,
            slotNames = names,
            galleries = listOf(gallery(1, slot = 0), gallery(2, slot = 1), gallery(3, slot = 1), gallery(4, slot = 2)),
        )
        // Slot 1 read again: 3 was removed on the website, 4 moved there from slot 2 (read too), 5 is new
        val pages = listOf(gallery(2, slot = 1), gallery(4, slot = 1), gallery(5, slot = 1))
        val done = FavoritesIndexMerger.completeRefresh(cloud, pages, emptySet(), names, now = 30, slots = listOf(1, 2))
        assertEquals(setOf(1L to 0, 2L to 1, 4L to 1, 5L to 1), done.galleries.map { it.gid to it.slot }.toSet())
        // A slot not read keeps its galleries, unless the pages found them in a read slot
        val onlySlot1 = FavoritesIndexMerger.completeRefresh(cloud, pages, emptySet(), names, now = 30, slots = listOf(1))
        assertEquals(setOf(1L to 0, 2L to 1, 4L to 1, 5L to 1), onlySlot1.galleries.map { it.gid to it.slot }.toSet())
    }

    @Test
    fun newGalleriesAreUnknownOrInAnotherSlot() {
        val cloud = CloudFavorites(galleries = listOf(gallery(1, slot = 0), gallery(2, slot = 1)))
        val page = listOf(gallery(1, slot = 0), gallery(2, slot = 3), gallery(3, slot = 0), gallery(4))
        assertEquals(listOf(2L, 3L), FavoritesIndexMerger.newGalleries(cloud, page).map { it.gid })
    }

    @Test
    fun resumedPartialRefreshOnlyLooksAtItsSlots() {
        val counts = List(CLOUD_SLOT_COUNT) { 2 }
        val checkpoint = CloudRefreshCheckpoint(slots = listOf(2, 5), slot = 5, galleries = listOf(gallery(1, slot = 2)), counts = counts)
        // Slot 3 is not read by this refresh: going on
        val other = counts.mapIndexed { slot, count -> if (slot == 3) 9 else count }
        assertEquals(checkpoint.copy(counts = other), FavoritesIndexMerger.resumeCheckpoint(checkpoint, other))
        // Slot 2 changed since it was read: read again from it
        val changed = counts.mapIndexed { slot, count -> if (slot == 2) 9 else count }
        val resumed = FavoritesIndexMerger.resumeCheckpoint(checkpoint, changed)
        assertEquals(2, resumed.slot)
        assertEquals(emptyList(), resumed.galleries)
    }
}
