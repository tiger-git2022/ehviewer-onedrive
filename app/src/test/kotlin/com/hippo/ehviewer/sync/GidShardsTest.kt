package com.hippo.ehviewer.sync

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class GidShardsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val dir: File = Files.createTempDirectory("gid-shards").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun gids(range: LongRange) = range.toList().toLongArray()

    private fun gallery(gid: Long, slot: Int = 0, title: String = "Title $gid") = IndexedGallery(gid = gid, token = "t$gid", title = title, slot = slot)

    @Test
    fun firstWriteMakesShardsOfTheInitialSize() {
        val ranges = GidShards.split(gids(1L..2_500L), emptyList())
        assertEquals(listOf(1_000, 1_000, 500), ranges.map { it.count })
        assertEquals(listOf(0L, 1_001L, 2_001L), ranges.map { it.minGid })
    }

    @Test
    fun rangesAreKeptAndOnlyTheGrownOneSplits() {
        val first = GidShards.split(gids(1L..3_000L), emptyList())
        // 1500 galleries added at the end: only the last range grows past the limit and splits
        val second = GidShards.split(gids(1L..4_500L), first.map { it.minGid })
        assertEquals(listOf(0L, 1_001L, 2_001L), second.take(3).map { it.minGid })
        assertEquals(listOf(1_000, 1_000, 1_250, 1_250), second.map { it.count })
        assertTrue(second.all { it.count <= GidShards.MAX_COUNT })
        // Every gid is in exactly one range, in order
        assertEquals(4_500, second.sumOf { it.count })
        second.zipWithNext().forEach { (a, b) -> assertEquals(a.to, b.from) }
    }

    @Test
    fun shrunkNeighboursMerge() {
        val first = GidShards.split(gids(1L..3_000L), emptyList())
        // Most of the first two ranges removed
        val left = (1L..300L) + (1_001L..1_300L) + (2_001L..3_000L)
        val second = GidShards.split(left.toLongArray(), first.map { it.minGid })
        assertEquals(listOf(0L, 2_001L), second.map { it.minGid })
        assertEquals(listOf(600, 1_000), second.map { it.count })
    }

    @Test
    fun emptyRangesAreDroppedAndTheFirstStartsAtZero() {
        val second = GidShards.split(longArrayOf(5_000L, 5_001L), listOf(0L, 1_001L, 2_001L, 4_000L))
        assertEquals(listOf(GidShards.GidRange(0L, 0, 2)), second)
        assertEquals(listOf(GidShards.GidRange(0, 0, 0)), GidShards.split(LongArray(0), listOf(0L, 10L)))
    }

    @Test
    fun rangeOfFindsTheRangeOfAGid() {
        val minGids = longArrayOf(0L, 100L, 200L)
        assertEquals(0, GidShards.rangeOf(minGids, 5L))
        assertEquals(1, GidShards.rangeOf(minGids, 100L))
        assertEquals(1, GidShards.rangeOf(minGids, 199L))
        assertEquals(2, GidShards.rangeOf(minGids, 1_000_000L))
    }

    @Test
    fun ordersKeepNumbersOfEntriesThatStay() {
        val first = GidShards.orders(listOf(5L, 3L, 9L), emptyMap())
        val old = mapOf(5L to first[0], 3L to first[1], 9L to first[2])
        // One removed, one appended, one inserted: the others keep their numbers
        val second = GidShards.orders(listOf(5L, 7L, 9L, 1L), old)
        assertEquals(first[0], second[0])
        assertEquals(first[2], second[2])
        assertTrue(second.toList().zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun fingerprintSeesEveryField() {
        val base = GidShards.Fingerprint().add(gallery(1)).value
        assertEquals(base, GidShards.Fingerprint().add(gallery(1)).value)
        assertNotEquals(base, GidShards.Fingerprint().add(gallery(1, slot = 2)).value)
        assertNotEquals(base, GidShards.Fingerprint().add(gallery(1).copy(titleJpn = "")).value)
        assertNotEquals(base, GidShards.Fingerprint().add(gallery(1).copy(local = true)).value)
    }

    @Test
    fun sameContentGetsTheSameFile() {
        val a = GidShards.writeHashed(json, FavoritesShard.serializer(), FavoritesShard(galleries = listOf(gallery(1))), dir, "shard-")
        val b = GidShards.writeHashed(json, FavoritesShard.serializer(), FavoritesShard(galleries = listOf(gallery(1))), dir, "shard-")
        val c = GidShards.writeHashed(json, FavoritesShard.serializer(), FavoritesShard(galleries = listOf(gallery(2))), dir, "shard-")
        assertEquals(a, b)
        assertNotEquals(a.hash, c.hash)
        assertEquals(32, a.hash.length)
        assertEquals(listOf(gallery(1)), GidShards.readGzip(json, FavoritesShard.serializer(), File(dir, GidShards.fileName("shard-", a.hash))).galleries)
        // No temporary files left
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test
    fun storeKeepsTheListAndRewritesOnlyChangedParts() {
        val storeDir = File(dir, "store")
        val galleries = (1L..5_000L).map { gallery(it, slot = (it % 10).toInt()) }.shuffled(kotlin.random.Random(1))
        val cloud = CloudFavorites(
            userKey = "user",
            updatedAt = 1,
            slotNames = List(10) { "Slot $it" },
            galleries = galleries,
            japaneseTitles = mapOf(3L to "タイトル", 9_999_999L to ""),
        )
        val store = CloudFavoritesStore(storeDir, json)
        store.save(cloud)
        assertEquals(cloud, CloudFavoritesStore(storeDir, json).load())
        val before = storeDir.listFiles()!!.associate { it.name to it.lastModified() }
        Thread.sleep(20)

        // One favorite moved: one part is written again
        val moved = cloud.copy(updatedAt = 2, galleries = cloud.galleries.map { if (it.gid == 42L) it.copy(slot = 5) else it })
        store.save(moved)
        val after = storeDir.listFiles()!!.map { it.name }.toSet()
        val parts = after.filter { it.startsWith("part-") }
        val newParts = parts.filter { it !in before }
        assertEquals(1, newParts.size)
        assertEquals(parts.size, before.keys.count { it.startsWith("part-") })
        assertEquals(moved, CloudFavoritesStore(storeDir, json).load())

        // A favorite appended at the end of the list keeps the order of the others
        val added = moved.copy(galleries = moved.galleries + gallery(10_000L))
        store.save(added)
        assertEquals(added.galleries.map { it.gid }, CloudFavoritesStore(storeDir, json).load()!!.galleries.map { it.gid })
    }

    @Test
    fun checkpointAppendsPages() {
        val storeDir = File(dir, "checkpoint")
        val store = CheckpointStore(storeDir, json)
        val first = CloudRefreshCheckpoint(userKey = "user", startedAt = 1, galleries = (1L..100L).map { gallery(it) })
        store.save(first)
        val second = first.copy(slot = 2, galleries = first.galleries + (101L..150L).map { gallery(it) })
        store.save(second)
        assertEquals(2, storeDir.listFiles()!!.count { it.name.startsWith("pages-") })
        assertEquals(second, CheckpointStore(storeDir, json).load())
        // Galleries dropped (a slot read again): written again from the start
        val third = second.copy(galleries = second.galleries.take(30))
        store.save(third)
        assertEquals(1, storeDir.listFiles()!!.count { it.name.startsWith("pages-") })
        assertEquals(third, CheckpointStore(storeDir, json).load())
        store.save(null)
        assertTrue(!storeDir.exists())
    }

    @Test
    fun exportWritesOnlyChangedShards() {
        val cache = FavoritesExportCache(File(dir, "export"), json)
        val index = FavoritesIndex(updatedAt = 1, deviceId = "d", slotNames = List(10) { "Slot $it" }, galleries = (1L..4_000L).map { gallery(it) })
        val first = cache.build(index)
        assertEquals(4, first.shards.size)
        assertEquals(4_000, first.count)
        cache.saveManifest(first)
        val second = cache.build(index.copy(updatedAt = 2, galleries = index.galleries.map { if (it.gid == 1_500L) it.copy(slot = 3) else it }))
        assertTrue(first.sameContent(first.copy(updatedAt = 5)))
        assertEquals(listOf(true, false, true, true), first.shards.zip(second.shards).map { (a, b) -> a.hash == b.hash })
        assertEquals(index.galleries, cache.index(first).galleries)
    }
}
