package com.hippo.ehviewer.sync

import java.io.File
import kotlinx.serialization.json.Json

/**
 * Fork: [CloudFavorites] kept in [dir] in shards by gid range (see [GidShards]): `meta.json` and the content addressed
 * `part-<hash>.json.gz`. Saving a list with one favorite changed rewrites one part of at most [GidShards.MAX_COUNT]
 * entries instead of the whole list. The order of the list is kept with order numbers that do not change when entries
 * are added or removed elsewhere. Not thread safe: callers hold a lock.
 */
class CloudFavoritesStore(private val dir: File, private val json: Json) {
    private val metaFile = File(dir, META_FILE)

    /** What is on disk: the meta, the order numbers of its galleries. Null before the first load or save. */
    private var meta: CloudFavoritesMeta? = null
    private var orders: Map<Long, Long> = emptyMap()

    /** The saved list, null if there is none or it is unreadable or of a newer version. */
    fun load(): CloudFavorites? {
        if (!metaFile.exists()) return null
        val meta = json.decodeFromString(CloudFavoritesMeta.serializer(), metaFile.readText())
        if (meta.version > FAVORITES_INDEX_VERSION) return null
        val ordered = ArrayList<Pair<Long, IndexedGallery>>()
        val japaneseTitles = HashMap<Long, String>()
        val defaultTitles = HashMap<Long, String>()
        val pandaTitles = HashMap<Long, String>()
        meta.parts.forEach { part ->
            val content = GidShards.readGzip(json, CloudFavoritesPart.serializer(), File(dir, GidShards.fileName(PART_PREFIX, part.hash)))
            content.galleries.forEachIndexed { i, gallery -> ordered += content.orders.getOrElse(i) { Long.MAX_VALUE } to gallery }
            japaneseTitles += content.japaneseTitles
            defaultTitles += content.defaultTitles
            pandaTitles += content.pandaTitles
        }
        ordered.sortBy { it.first }
        this.meta = meta
        orders = ordered.associate { it.second.gid to it.first }
        return CloudFavorites(
            userKey = meta.userKey,
            updatedAt = meta.updatedAt,
            refreshedAt = meta.refreshedAt,
            fullReadAt = meta.fullReadAt,
            fullReadCount = meta.fullReadCount,
            slotNames = meta.slotNames,
            galleries = ordered.map { it.second },
            japaneseTitles = japaneseTitles,
            defaultTitles = defaultTitles,
            pandaTitles = pandaTitles,
        )
    }

    /** Saves [cloud], writing only the parts whose content changed since the last load or save. */
    fun save(cloud: CloudFavorites) {
        val galleryOrders = GidShards.orders(cloud.galleries.map { it.gid }, orders)
        val gids = sortedSetOf<Long>().apply {
            cloud.galleries.mapTo(this) { it.gid }
            addAll(cloud.japaneseTitles.keys)
            addAll(cloud.defaultTitles.keys)
            addAll(cloud.pandaTitles.keys)
        }.toLongArray()
        val previous = meta?.parts.orEmpty()
        val ranges = GidShards.split(gids, previous.map { it.minGid })
        val minGids = LongArray(ranges.size) { ranges[it].minGid }
        val parts = Array(ranges.size) { PartBuilder() }
        cloud.galleries.forEachIndexed { i, gallery ->
            parts[GidShards.rangeOf(minGids, gallery.gid)].galleries += galleryOrders[i] to gallery
        }
        cloud.japaneseTitles.forEach { (gid, title) -> parts[GidShards.rangeOf(minGids, gid)].japaneseTitles[gid] = title }
        cloud.defaultTitles.forEach { (gid, title) -> parts[GidShards.rangeOf(minGids, gid)].defaultTitles[gid] = title }
        cloud.pandaTitles.forEach { (gid, title) -> parts[GidShards.rangeOf(minGids, gid)].pandaTitles[gid] = title }
        val known = previous.associateBy { it.minGid }
        val written = ranges.mapIndexed { i, range ->
            val content = parts[i].build()
            val fingerprint = fingerprint(content)
            val old = known[range.minGid]
            if (old != null && old.fingerprint == fingerprint && File(dir, GidShards.fileName(PART_PREFIX, old.hash)).exists()) {
                old
            } else {
                val hashed = GidShards.writeHashed(json, CloudFavoritesPart.serializer(), content, dir, PART_PREFIX)
                StorePart(range.minGid, hashed.hash, fingerprint)
            }
        }
        val newMeta = CloudFavoritesMeta(
            userKey = cloud.userKey,
            updatedAt = cloud.updatedAt,
            refreshedAt = cloud.refreshedAt,
            fullReadAt = cloud.fullReadAt,
            fullReadCount = cloud.fullReadCount,
            slotNames = cloud.slotNames,
            parts = written,
        )
        GidShards.writeAtomically(metaFile, json.encodeToString(CloudFavoritesMeta.serializer(), newMeta))
        meta = newMeta
        orders = cloud.galleries.withIndex().associate { (i, gallery) -> gallery.gid to galleryOrders[i] }
        // Parts no longer listed, after the meta no longer points at them
        val kept = written.mapTo(HashSet()) { GidShards.fileName(PART_PREFIX, it.hash) }
        dir.listFiles()?.forEach { file ->
            if (file.name.startsWith(PART_PREFIX) && file.name !in kept) file.delete()
        }
    }

    fun delete() {
        dir.deleteRecursively()
        meta = null
        orders = emptyMap()
    }

    private class PartBuilder {
        val galleries = ArrayList<Pair<Long, IndexedGallery>>()
        val japaneseTitles = HashMap<Long, String>()
        val defaultTitles = HashMap<Long, String>()
        val pandaTitles = HashMap<Long, String>()

        // Sorted, so the same content is always written the same way
        fun build(): CloudFavoritesPart {
            val sorted = galleries.sortedBy { it.second.gid }
            return CloudFavoritesPart(
                galleries = sorted.map { it.second },
                orders = sorted.map { it.first },
                japaneseTitles = japaneseTitles.toSortedMap(),
                defaultTitles = defaultTitles.toSortedMap(),
                pandaTitles = pandaTitles.toSortedMap(),
            )
        }
    }

    private fun fingerprint(part: CloudFavoritesPart): Long {
        val fingerprint = GidShards.Fingerprint()
        fingerprint.add(part.galleries.size)
        part.galleries.forEach { fingerprint.add(it) }
        part.orders.forEach { fingerprint.add(it) }
        listOf(part.japaneseTitles, part.defaultTitles, part.pandaTitles).forEach { titles ->
            fingerprint.add(titles.size)
            titles.forEach { (gid, title) -> fingerprint.add(gid).add(title) }
        }
        return fingerprint.value
    }

    companion object {
        const val META_FILE = "meta.json"
        private const val PART_PREFIX = "part-"
    }
}

/**
 * Fork: a [CloudRefreshCheckpoint] kept in [dir]: `meta.json` with everything but the galleries, which are appended to
 * `pages-<n>.json.gz` as the refresh reads them. Saving a checkpoint whose galleries go on from the saved ones writes
 * only the new galleries; anything else (a refresh resumed from an earlier slot) rewrites them. Not thread safe.
 */
class CheckpointStore(private val dir: File, private val json: Json) {
    private val metaFile = File(dir, CloudFavoritesStore.META_FILE)

    /** Galleries in the page files, in order, and the page files. */
    private var saved: List<IndexedGallery> = emptyList()
    private var pages: List<String> = emptyList()

    fun load(): CloudRefreshCheckpoint? {
        if (!metaFile.exists()) return null
        val meta = json.decodeFromString(CheckpointMeta.serializer(), metaFile.readText())
        val galleries = meta.pages.flatMap { GidShards.readGzip(json, CheckpointPage.serializer(), File(dir, it)).galleries }
        saved = galleries
        pages = meta.pages
        return meta.checkpoint.copy(galleries = galleries)
    }

    fun save(checkpoint: CloudRefreshCheckpoint?) {
        if (checkpoint == null) {
            delete()
            return
        }
        val galleries = checkpoint.galleries
        val appended = galleries.size >= saved.size && galleries.subList(0, saved.size) == saved
        val newPages = if (appended) pages.toMutableList() else ArrayList()
        val from = if (appended) saved.size else 0
        if (from < galleries.size) {
            val name = "$PAGES_PREFIX${System.nanoTime()}.json.gz"
            val page = CheckpointPage(galleries.subList(from, galleries.size).toList())
            GidShards.writeGzip(json, CheckpointPage.serializer(), page, File(dir, name))
            newPages += name
        }
        val meta = CheckpointMeta(checkpoint.copy(galleries = emptyList()), newPages)
        GidShards.writeAtomically(metaFile, json.encodeToString(CheckpointMeta.serializer(), meta))
        saved = galleries.toList()
        pages = newPages
        val kept = newPages.toHashSet()
        dir.listFiles()?.forEach { file -> if (file.name.startsWith(PAGES_PREFIX) && file.name !in kept) file.delete() }
    }

    fun delete() {
        dir.deleteRecursively()
        saved = emptyList()
        pages = emptyList()
    }

    private companion object {
        const val PAGES_PREFIX = "pages-"
    }
}
