package com.hippo.ehviewer.sync

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ShardCacheTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val dir: File = Files.createTempDirectory("shard-cache").toFile()
    private val cache = ShardCache(dir, json, ShardedCategory.FAVORITES)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun record(gid: Long, time: Long = gid) = SyncGalleryRecord(gid, time, SyncGallery(gid = gid, token = "t$gid", title = "Title $gid"))

    private fun records(range: LongRange) = range.map { record(it) }

    @Serializable
    private class Stamp(val gid: Long, val time: Long)

    @Test
    fun buildAndReadBack() {
        val list = records(1L..2_500L)
        val manifest = cache.build(list, emptyList(), updatedAt = 5, deviceId = "a")
        assertEquals(3, manifest.shards.size)
        assertEquals(2_500, manifest.count)
        assertEquals(listOf(0L, 1_001L, 2_001L), manifest.shards.map { it.minGid })
        assertTrue(cache.hasAll(manifest))
        assertEquals(list, cache.read(manifest))
    }

    @Test
    fun aChangeTouchesOnlyItsShard() {
        val list = records(1L..2_500L)
        val before = cache.build(list, emptyList(), updatedAt = 1, deviceId = "a")
        // One record changed and one added at the end, as a new history entry does
        val changed = list.map { if (it.gid == 1_500L) record(1_500L, time = 99_999) else it } + record(9_000L)
        val after = cache.build(changed, before.shards.map { it.minGid }, updatedAt = 2, deviceId = "a")
        assertEquals(before.shards.map { it.minGid }, after.shards.map { it.minGid })
        assertEquals(before.shards[0].hash, after.shards[0].hash)
        assertTrue(before.shards[1].hash != after.shards[1].hash)
        assertTrue(before.shards[2].hash != after.shards[2].hash)
        assertEquals(changed, cache.read(after))
    }

    @Test
    fun sameRecordsGiveTheSameShardsWhateverTheTime() {
        val list = records(1L..1_200L)
        val first = cache.build(list, emptyList(), updatedAt = 1, deviceId = "a")
        val second = cache.build(list, first.shards.map { it.minGid }, updatedAt = 2, deviceId = "b")
        assertEquals(first.shards, second.shards)
    }

    @Test
    fun emptyListHasNoShards() {
        val manifest = cache.build(emptyList(), emptyList(), updatedAt = 1, deviceId = "a")
        assertTrue(manifest.shards.isEmpty())
        assertEquals(emptyList(), cache.read(manifest))
    }

    @Test
    fun baselineReadsOnlyStamps() {
        val manifest = cache.build(records(1L..10L), emptyList(), updatedAt = 1, deviceId = "a")
        val stamps = cache.read(manifest, Stamp.serializer())
        assertEquals((1L..10L).toList(), stamps.map { it.gid })
        assertEquals((1L..10L).toList(), stamps.map { it.time })
    }

    @Test
    fun manifestIsSavedAndUnreadableOnesAreNull() {
        assertNull(cache.manifest())
        val manifest = cache.build(records(1L..10L), emptyList(), updatedAt = 1, deviceId = "a")
        cache.saveManifest(manifest)
        assertEquals(manifest, cache.manifest())
        File(dir, ShardCache.MANIFEST_FILE).writeText("{")
        assertNull(cache.manifest())
    }

    @Test
    fun cleanUpKeepsTheListedShards() {
        val old = cache.build(records(1L..10L), emptyList(), updatedAt = 1, deviceId = "a")
        val new = cache.build(records(1L..11L), old.shards.map { it.minGid }, updatedAt = 2, deviceId = "a")
        File(dir, "shard-x.json.gz.download").writeText("partial")
        cache.cleanUp(new.hashes)
        assertTrue(cache.hasAll(new))
        assertFalse(cache.hasAll(old))
        assertFalse(File(dir, "shard-x.json.gz.download").exists())
    }

    @Test
    fun namesOfOtherFilesAreNoShards() {
        assertNull(ShardCache.hashOf("manifest.json"))
        assertNull(ShardCache.hashOf("shard-../x.json.gz"))
        assertEquals("0123456789abcdef0123456789abcdef", ShardCache.hashOf("shard-0123456789abcdef0123456789abcdef.json.gz"))
    }

    @Test
    fun categoriesHaveTheirOwnFiles() {
        assertEquals("favorites.manifest.json", ShardedCategory.FAVORITES.manifestName)
        assertEquals("history", ShardedCategory.HISTORY.folderName)
        assertNull(ShardedCategory.of(SyncCategory.PROGRESS))
    }
}
