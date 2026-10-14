package com.hippo.ehviewer.sync

/** Pure functions behind the favorites index, kept apart for unit tests. */
object FavoritesIndexMerger {
    /** A full refresh is forced after this long even if the counts of E-Hentai still match. */
    const val REFRESH_MAX_AGE_MILLIS = 7 * 24 * 60 * 60 * 1000L

    /** The list of the E-Hentai account [userKey]: an empty one if [cloud] belongs to another account. */
    fun forUser(cloud: CloudFavorites, userKey: String?): CloudFavorites = if (cloud.userKey == userKey) {
        cloud
    } else {
        // Japanese titles describe galleries, not the account
        CloudFavorites(userKey = userKey, japaneseTitles = cloud.japaneseTitles)
    }

    /**
     * The slot of a gallery of the "all" favorites page, which names the slot (the title of its posted date) but not
     * its number. [NO_CLOUD_SLOT] if the name is missing or used by several slots.
     */
    fun slotOf(favoriteName: String?, slotNames: List<String>): Int {
        favoriteName ?: return NO_CLOUD_SLOT
        val slot = slotNames.indexOf(favoriteName)
        return if (slot >= 0 && slotNames.lastIndexOf(favoriteName) == slot) slot else NO_CLOUD_SLOT
    }

    /** Keeps the fields [new] does not know (the list pages have no Japanese title). */
    private fun IndexedGallery.updatedWith(new: IndexedGallery) = new.copy(
        token = new.token.ifEmpty { token },
        title = new.title?.takeIf { it.isNotEmpty() } ?: title,
        titleJpn = new.titleJpn ?: titleJpn,
        uploader = new.uploader ?: uploader,
        category = if (new.category != 0) new.category else category,
    )

    fun upsert(cloud: CloudFavorites, galleries: List<IndexedGallery>, now: Long): CloudFavorites {
        val valid = galleries.filter { it.slot in 0 until CLOUD_SLOT_COUNT }
        if (valid.isEmpty()) return cloud
        val byGid = cloud.galleries.associateByTo(LinkedHashMap()) { it.gid }
        var changed = false
        valid.forEach { new ->
            val old = byGid[new.gid]
            val merged = old?.updatedWith(new) ?: new
            if (merged != old) {
                byGid[new.gid] = merged
                changed = true
            }
        }
        return if (changed) cloud.copy(updatedAt = now, galleries = byGid.values.toList()) else cloud
    }

    /** Moves known galleries to [slot]; unknown ones are left to the next refresh. */
    fun move(cloud: CloudFavorites, gids: Collection<Long>, slot: Int, now: Long): CloudFavorites {
        if (slot !in 0 until CLOUD_SLOT_COUNT) return remove(cloud, gids, now)
        val set = gids.toHashSet()
        var changed = false
        val galleries = cloud.galleries.map {
            if (it.gid in set && it.slot != slot) {
                changed = true
                it.copy(slot = slot)
            } else {
                it
            }
        }
        return if (changed) cloud.copy(updatedAt = now, galleries = galleries) else cloud
    }

    fun remove(cloud: CloudFavorites, gids: Collection<Long>, now: Long): CloudFavorites {
        val set = gids.toHashSet()
        val galleries = cloud.galleries.filterNot { it.gid in set }
        return if (galleries.size != cloud.galleries.size) cloud.copy(updatedAt = now, galleries = galleries) else cloud
    }

    fun withSlotNames(cloud: CloudFavorites, slotNames: List<String>, now: Long): CloudFavorites = if (slotNames.size == CLOUD_SLOT_COUNT && slotNames != cloud.slotNames) {
        cloud.copy(updatedAt = now, slotNames = slotNames)
    } else {
        cloud
    }

    /** Result of a full refresh: [galleries] replace the known list, the Japanese titles already known are kept. */
    fun replaceAll(cloud: CloudFavorites, galleries: List<IndexedGallery>, slotNames: List<String>, now: Long): CloudFavorites {
        val old = cloud.galleries.associateBy { it.gid }
        val merged = galleries.filter { it.slot in 0 until CLOUD_SLOT_COUNT }
            .distinctBy { it.gid }
            .map { new -> old[new.gid]?.updatedWith(new) ?: new }
        val names = slotNames.takeIf { it.size == CLOUD_SLOT_COUNT } ?: cloud.slotNames
        return cloud.copy(updatedAt = now, refreshedAt = now, slotNames = names, galleries = merged)
    }

    /** Galleries added, removed or moved to another slot between [before] and [after]. */
    fun changedGids(before: CloudFavorites, after: CloudFavorites): Set<Long> {
        if (before.galleries === after.galleries) return emptySet()
        val old = before.galleries.associate { it.gid to it.slot }
        val new = after.galleries.associate { it.gid to it.slot }
        return (old.keys + new.keys).filterTo(HashSet()) { old[it] != new[it] }
    }

    /**
     * An unfinished refresh going on with the slot [counts] E-Hentai shows now: from the first slot already read (or
     * being read) whose count changed since, the slots are read again and their galleries dropped.
     */
    fun resumeCheckpoint(checkpoint: CloudRefreshCheckpoint, counts: List<Int>): CloudRefreshCheckpoint {
        val changed = (0..minOf(checkpoint.slot, CLOUD_SLOT_COUNT - 1)).firstOrNull { slot ->
            checkpoint.counts.getOrNull(slot) != counts.getOrNull(slot)
        } ?: return checkpoint.copy(counts = counts)
        return checkpoint.copy(
            slot = changed,
            next = null,
            galleries = checkpoint.galleries.filter { it.slot < changed },
            counts = counts,
        )
    }

    /**
     * The galleries of refresh pages as they are now: moved to the slot the user [intended] or dropped when removed,
     * and dropped when [touched] otherwise (the known list is right about them).
     */
    private fun correctedPages(galleries: List<IndexedGallery>, touched: Set<Long>, intended: Map<Long, Int>) = galleries.mapNotNull { gallery ->
        val slot = intended[gallery.gid]
        when {
            slot != null -> gallery.copy(slot = slot).takeIf { slot in 0 until CLOUD_SLOT_COUNT }
            gallery.gid in touched -> null
            else -> gallery
        }
    }

    /** Pages of an unfinished refresh: their galleries are added to the known list, corrected by the user's changes. */
    fun withRefreshPages(
        cloud: CloudFavorites,
        galleries: List<IndexedGallery>,
        touched: Set<Long>,
        now: Long,
        intended: Map<Long, Int> = emptyMap(),
    ): CloudFavorites = upsert(cloud, correctedPages(galleries, touched, intended), now)

    /**
     * End of a full refresh: the pages read replace the known list. Galleries changed in the app meanwhile keep their
     * known state ([touched]) or take the slot the user put them in ([intended]).
     */
    fun completeRefresh(
        cloud: CloudFavorites,
        galleries: List<IndexedGallery>,
        touched: Set<Long>,
        slotNames: List<String>,
        now: Long,
        intended: Map<Long, Int> = emptyMap(),
    ): CloudFavorites {
        val kept = cloud.galleries.filter { it.gid in touched || it.gid in intended }
        return replaceAll(cloud, correctedPages(galleries, touched, intended) + kept, slotNames, now)
    }

    /** Adds the Japanese titles looked up (gid -> title, empty if the gallery has none). */
    fun withJapaneseTitles(cloud: CloudFavorites, titles: Map<Long, String>): CloudFavorites {
        val added = titles.filter { (gid, title) -> cloud.japaneseTitles[gid] != title }
        return if (added.isEmpty()) cloud else cloud.copy(japaneseTitles = cloud.japaneseTitles + added)
    }

    /**
     * Galleries of [index] whose Japanese title is still unknown, at most [limit]: the local favorites, then the
     * cloud slots in turn, so the newest favorites of every slot come first.
     */
    fun missingJapaneseTitles(index: FavoritesIndex, limit: Int): List<IndexedGallery> {
        val missing = index.galleries.filter { it.titleJpn == null }
        val (local, cloud) = missing.partition { it.local || it.slot !in 0 until CLOUD_SLOT_COUNT }
        val bySlot = cloud.groupBy { it.slot }.values.map { it.iterator() }
        val interleaved = sequence {
            while (bySlot.any { it.hasNext() }) bySlot.forEach { if (it.hasNext()) yield(it.next()) }
        }
        return (local.asSequence() + interleaved).take(limit).toList()
    }

    /** True when the counts shown by E-Hentai differ from the known list, or the list is too old or was never read. */
    fun needsRefresh(cloud: CloudFavorites, counts: List<Int>, now: Long): Boolean = cloud.refreshedAt == 0L ||
        now - cloud.refreshedAt > REFRESH_MAX_AGE_MILLIS ||
        counts.size == CLOUD_SLOT_COUNT && counts != cloud.countBySlot()

    /** The cloud part another device uploaded, if it is newer than the copy of this device. */
    fun adopt(cloud: CloudFavorites, remote: FavoritesIndex): CloudFavorites {
        if (remote.cloudUpdatedAt <= cloud.updatedAt) return cloud
        // Another E-Hentai account, or none
        if (remote.ehUserKey == null || remote.ehUserKey != cloud.userKey) return cloud
        val old = cloud.galleries.associateBy { it.gid }
        val galleries = remote.galleries.filter { it.slot in 0 until CLOUD_SLOT_COUNT }.map { new ->
            val gallery = new.copy(local = false)
            old[gallery.gid]?.updatedWith(gallery) ?: gallery
        }
        return CloudFavorites(
            userKey = cloud.userKey,
            updatedAt = remote.cloudUpdatedAt,
            refreshedAt = maxOf(cloud.refreshedAt, remote.cloudRefreshedAt),
            slotNames = remote.slotNames.takeIf { it.size == CLOUD_SLOT_COUNT } ?: cloud.slotNames,
            galleries = galleries,
            japaneseTitles = cloud.japaneseTitles + remoteTitles(remote),
        )
    }

    private fun remoteTitles(remote: FavoritesIndex) = remote.galleries.mapNotNull { gallery -> gallery.titleJpn?.let { gallery.gid to it } }.toMap()

    /**
     * One entry per gallery: the local favorites, then the cloud favorites that are not local ones. Japanese titles
     * come from the gallery or from the titles looked up; titles of galleries no longer listed are dropped.
     */
    fun buildIndex(local: List<IndexedGallery>, cloud: CloudFavorites, deviceId: String, deviceName: String, now: Long): FavoritesIndex {
        val cloudByGid = cloud.galleries.associateBy { it.gid }
        val localGids = HashSet<Long>()
        val galleries = buildList {
            local.forEach { gallery ->
                if (!localGids.add(gallery.gid)) return@forEach
                val inCloud = cloudByGid[gallery.gid]
                val merged = inCloud?.updatedWith(gallery)?.copy(slot = inCloud.slot) ?: gallery.copy(slot = NO_CLOUD_SLOT)
                add(merged.copy(local = true))
            }
            cloud.galleries.forEach { if (it.gid !in localGids) add(it.copy(local = false)) }
        }.map { gallery ->
            if (gallery.titleJpn.isNullOrEmpty()) gallery.copy(titleJpn = cloud.japaneseTitles[gallery.gid] ?: gallery.titleJpn) else gallery
        }
        return FavoritesIndex(
            updatedAt = now,
            deviceId = deviceId,
            deviceName = deviceName,
            ehUserKey = cloud.userKey,
            cloudUpdatedAt = cloud.updatedAt,
            cloudRefreshedAt = cloud.refreshedAt,
            slotNames = cloud.slotNames,
            galleries = galleries,
        )
    }

    /** Drops the looked up titles of galleries the index no longer lists. */
    fun pruneTitles(cloud: CloudFavorites, index: FavoritesIndex): CloudFavorites {
        val gids = index.galleries.mapTo(HashSet()) { it.gid }
        val kept = cloud.japaneseTitles.filterKeys { it in gids }
        return if (kept.size == cloud.japaneseTitles.size) cloud else cloud.copy(japaneseTitles = kept)
    }
}
