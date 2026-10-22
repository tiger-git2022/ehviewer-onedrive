package com.hippo.ehviewer.sync

import java.io.File
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Fork: the local favorites and the history are kept on OneDrive in shards by gid range (see [GidShards]) instead of
 * one file each: `ehviewer/v1/<name>.manifest.json` ([SectionManifest]) lists the shards of the category, stored in
 * `ehviewer/v1/<name>/shard-<hash>.json.gz` and named by their content. A change rewrites and uploads only the shards it
 * touches, plus the small manifest; another device downloads only the shards it does not have yet. With one file, every
 * change uploaded the whole list and every other device downloaded it whole.
 *
 * The single file of before (`<name>.json.gz`) is read once, when no manifest is there yet, and then replaced by a stub
 * of format [SHARDED_STUB_VERSION] with no items, which older builds refuse as written by a newer version: they cannot
 * write the old file over and miss the changes.
 */
const val SHARDED_STUB_VERSION = 2

/** A category kept in shards, records keyed by gid. */
class ShardedCategory<R>(val category: SyncCategory, val serializer: KSerializer<R>, val gidOf: (R) -> Long) {
    /** `favorites` for `favorites.json.gz`. */
    val name = category.fileName.removeSuffix(".json.gz")

    /** File of the manifest, next to the single files of the other categories. */
    val manifestName = "$name.manifest.json"

    /** Folder of the shards, in the folder of the manifest. */
    val folderName = name

    companion object {
        val FAVORITES = ShardedCategory(SyncCategory.FAVORITES, SyncGalleryRecord.serializer()) { it.gid }
        val HISTORY = ShardedCategory(SyncCategory.HISTORY, SyncGalleryRecord.serializer()) { it.gid }

        fun of(category: SyncCategory): ShardedCategory<*>? = when (category) {
            SyncCategory.FAVORITES -> FAVORITES
            SyncCategory.HISTORY -> HISTORY
            else -> null
        }
    }
}

/** `<name>.manifest.json`: the shards of a category, by [ManifestShard.minGid]. */
@Serializable
data class SectionManifest(
    @EncodeDefault
    val version: Int = SYNC_FORMAT_VERSION,
    val updatedAt: Long = 0,
    val deviceId: String = "",
    /** Records of all shards. */
    val count: Int = 0,
    val shards: List<ManifestShard> = emptyList(),
) {
    val hashes: Set<String>
        get() = shards.mapTo(HashSet()) { it.hash }
}

/** A shard: the records of a gid range, sorted by gid. No time in it, so the same records always give the same file. */
@Serializable
data class RecordShard<T>(
    @EncodeDefault
    val version: Int = SYNC_FORMAT_VERSION,
    val items: List<T>,
)

/**
 * The shards of a category as last synced, kept in [dir]: the manifest and its shard files. They are the cloud copy the
 * next sync compares with (the baseline of the merge), and the shards a newer manifest still lists are not downloaded
 * again.
 */
class ShardCache<R>(private val dir: File, private val json: Json, private val sharded: ShardedCategory<R>) {
    private val manifestFile = File(dir, MANIFEST_FILE)

    fun manifest(): SectionManifest? = runCatching { json.decodeFromString(SectionManifest.serializer(), manifestFile.readText()) }.getOrNull()

    fun saveManifest(manifest: SectionManifest) = GidShards.writeAtomically(manifestFile, json.encodeToString(SectionManifest.serializer(), manifest))

    fun shardFile(hash: String) = File(dir, GidShards.fileName(SHARD_PREFIX, hash))

    /** All shards of [manifest] are here. */
    fun hasAll(manifest: SectionManifest) = manifest.shards.all { shardFile(it.hash).exists() }

    /** The records of [manifest], read as [serializer] (the records, or only what a baseline needs of them). */
    fun <T> read(manifest: SectionManifest, serializer: KSerializer<T>): List<T> {
        val shardSerializer = RecordShard.serializer(serializer)
        val result = ArrayList<T>(manifest.count)
        manifest.shards.forEach { shard ->
            val file = GidShards.readGzip(json, shardSerializer, shardFile(shard.hash))
            OneDriveSyncMerger.checkVersion(file.version)
            result += file.items
        }
        return result
    }

    fun read(manifest: SectionManifest): List<R> = read(manifest, sharded.serializer)

    /**
     * The records of [manifest] when some of its shards are gone for good: those of the shards here, and for the gid
     * ranges of the missing ones the records of [fallback] (the copy last synced, all of its shards here), so a shard
     * deleted by hand does not make the records of every other shard look deleted.
     */
    fun readRecovering(manifest: SectionManifest, fallback: SectionManifest?): List<R> {
        val shards = manifest.shards.sortedBy { it.minGid }
        val result = ArrayList<R>(manifest.count)
        val missing = ArrayList<LongRange>()
        shards.forEachIndexed { i, shard ->
            if (shardFile(shard.hash).exists()) {
                result += read(SectionManifest(shards = listOf(shard)))
            } else {
                val upper = shards.getOrNull(i + 1)?.minGid ?: Long.MAX_VALUE
                missing += shard.minGid until upper
            }
        }
        if (missing.isNotEmpty() && fallback != null) {
            read(fallback).filterTo(result) { record -> missing.any { sharded.gidOf(record) in it } }
        }
        return result.distinctBy(sharded.gidOf).sortedBy(sharded.gidOf)
    }

    /**
     * The manifest of [records] (sorted by gid, one per gid), written in shards here: in the ranges of [bounds] (the min
     * gids of the cloud manifest), so the shards the change does not touch come out the same and need no upload.
     */
    fun build(records: List<R>, bounds: List<Long>, updatedAt: Long, deviceId: String): SectionManifest {
        val gids = LongArray(records.size) { sharded.gidOf(records[it]) }
        val shardSerializer = RecordShard.serializer(sharded.serializer)
        val shards = GidShards.split(gids, bounds).filter { it.count > 0 }.map { range ->
            val hashed = GidShards.writeHashed(json, shardSerializer, RecordShard(items = records.subList(range.from, range.to).toList()), dir, SHARD_PREFIX)
            ManifestShard(hash = hashed.hash, minGid = range.minGid, count = range.count, size = hashed.size)
        }
        return SectionManifest(updatedAt = updatedAt, deviceId = deviceId, count = records.size, shards = shards)
    }

    /** Deletes the shard files of hashes not in [keep], and files left by an interrupted write. */
    fun cleanUp(keep: Set<String>) {
        dir.listFiles()?.forEach { file ->
            val hash = hashOf(file.name)
            if (hash != null && hash !in keep || file.name.endsWith(".tmp") || file.name.endsWith(".download")) file.delete()
        }
    }

    fun delete() = dir.deleteRecursively()

    companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val SHARD_PREFIX = "shard-"

        /** The hash of a shard file name, null for another file. Hashes are those of [GidShards.writeHashed]. */
        fun hashOf(fileName: String): String? = fileName.takeIf { it.startsWith(SHARD_PREFIX) && it.endsWith(".json.gz") }
            ?.removePrefix(SHARD_PREFIX)
            ?.removeSuffix(".json.gz")
            ?.takeIf(FavoritesExportCache::isValidHash)
    }
}
