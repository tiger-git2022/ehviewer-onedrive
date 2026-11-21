package com.hippo.ehviewer.sync

import java.io.File
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Fork: the sharded export of the favorites index ([FavoritesManifest]) kept in [dir]: the manifest last uploaded or
 * taken from another device, the shard files (`shard-<hash>.json.gz`, the names they have on OneDrive) and the
 * [GidShards.Fingerprint] of each shard this device wrote, so an unchanged shard is not written again.
 */
class FavoritesExportCache(private val dir: File, private val json: Json) {
    private val manifestFile = File(dir, MANIFEST_FILE)
    private val fingerprintsFile = File(dir, "fingerprints.json")
    private val fingerprintsSerializer = MapSerializer(String.serializer(), Long.serializer())

    fun manifest(): FavoritesManifest? = runCatching { json.decodeFromString(FavoritesManifest.serializer(), manifestFile.readText()) }.getOrNull()

    fun saveManifest(manifest: FavoritesManifest) = GidShards.writeAtomically(manifestFile, json.encodeToString(FavoritesManifest.serializer(), manifest))

    fun shardFile(hash: String) = File(dir, GidShards.fileName(SHARD_PREFIX, hash))

    fun readShard(hash: String): List<IndexedGallery> = GidShards.readGzip(json, FavoritesShard.serializer(), shardFile(hash)).galleries

    private fun fingerprints(): MutableMap<String, Long> = runCatching { json.decodeFromString(fingerprintsSerializer, fingerprintsFile.readText()) }
        .getOrNull()
        .orEmpty()
        .toMutableMap()

    /**
     * The manifest of [index]: its galleries in the shard ranges of the last manifest (split and merged as needed),
     * writing only the shards whose content is not in a file yet.
     */
    fun build(index: FavoritesIndex): FavoritesManifest {
        // Fork: stable sort + in-place dedup avoids a HashSet<Long> and extra list copy on large collections
        val sorted = ArrayList(index.galleries).apply {
            sortBy { it.gid }
            var write = 0
            for (read in indices) {
                val item = this[read]
                if (write == 0 || this[write - 1].gid != item.gid) {
                    if (write != read) this[write] = item
                    write++
                }
            }
            if (write < size) subList(write, size).clear()
        }
        val gids = LongArray(sorted.size) { sorted[it].gid }
        val ranges = GidShards.split(gids, manifest()?.shards?.map { it.minGid }.orEmpty())
        val fingerprints = fingerprints()
        val byFingerprint = fingerprints.entries.associate { (hash, fingerprint) -> fingerprint to hash }
        val shards = ranges.map { range ->
            val galleries = sorted.subList(range.from, range.to)
            val fingerprint = GidShards.Fingerprint().apply {
                add(galleries.size)
                galleries.forEach { add(it) }
            }.value
            val known = byFingerprint[fingerprint]?.takeIf { shardFile(it).let { f -> f.exists() && f.length() > 0L } }
            val hash: String
            val size: Long
            if (known != null) {
                hash = known
                size = shardFile(known).length()
            } else {
                val hashed = GidShards.writeHashed(json, FavoritesShard.serializer(), FavoritesShard(galleries = galleries.toList()), dir, SHARD_PREFIX)
                hash = hashed.hash
                size = hashed.size
                fingerprints[hash] = fingerprint
            }
            ManifestShard(hash = hash, minGid = range.minGid, count = galleries.size, size = size)
        }.filter { it.count > 0 }
        val listed = shards.mapTo(HashSet()) { it.hash }
        fingerprints.keys.retainAll { it in listed || shardFile(it).let { f -> f.exists() && f.length() > 0L } }
        GidShards.writeAtomically(fingerprintsFile, json.encodeToString(fingerprintsSerializer, fingerprints))
        return FavoritesManifest(
            updatedAt = index.updatedAt,
            deviceId = index.deviceId,
            deviceName = index.deviceName,
            ehUserKey = index.ehUserKey,
            cloudUpdatedAt = index.cloudUpdatedAt,
            cloudRefreshedAt = index.cloudRefreshedAt,
            slotNames = index.slotNames,
            count = sorted.size,
            shards = shards,
        )
    }

    /** The index of a [manifest] whose shards are all in files here. */
    fun index(manifest: FavoritesManifest, galleries: List<IndexedGallery> = manifest.shards.flatMap { readShard(it.hash) }) = FavoritesIndex(
        updatedAt = manifest.updatedAt,
        deviceId = manifest.deviceId,
        deviceName = manifest.deviceName,
        ehUserKey = manifest.ehUserKey,
        cloudUpdatedAt = manifest.cloudUpdatedAt,
        cloudRefreshedAt = manifest.cloudRefreshedAt,
        slotNames = manifest.slotNames,
        galleries = galleries,
    )

    /** Deletes the shard files of hashes not in [keep], and files left by an interrupted write. */
    fun cleanUp(keep: Set<String>) {
        dir.listFiles()?.forEach { file ->
            val hash = hashOf(file.name)
            if (hash != null && hash !in keep || file.name.endsWith(".tmp") || file.name.endsWith(".download")) file.delete()
        }
        val fingerprints = fingerprints()
        if (fingerprints.keys.retainAll { it in keep }) {
            GidShards.writeAtomically(fingerprintsFile, json.encodeToString(fingerprintsSerializer, fingerprints))
        }
    }

    fun delete() = dir.deleteRecursively()

    companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val SHARD_PREFIX = "shard-"

        private val HASH = Regex("[0-9a-f]{32}")

        /** A hash as [GidShards.writeHashed] makes them: nothing else may become part of a file name. */
        fun isValidHash(hash: String) = HASH.matches(hash)

        /** The hash of a shard file name on OneDrive, null for another file. */
        fun hashOf(fileName: String): String? = fileName.takeIf { it.startsWith(SHARD_PREFIX) && it.endsWith(".json.gz") }
            ?.removePrefix(SHARD_PREFIX)
            ?.removeSuffix(".json.gz")
            ?.takeIf(::isValidHash)
    }
}
