package com.hippo.ehviewer.sync

import java.text.Normalizer

/** Pure functions behind the favorites index, kept apart for unit tests. */
object FavoritesIndexMerger {
    /** The list of the E-Hentai account [userKey]: an empty one if [cloud] belongs to another account. */
    fun forUser(cloud: CloudFavorites, userKey: String?): CloudFavorites = if (cloud.userKey == userKey) {
        cloud
    } else {
        // Titles describe galleries, not the account
        CloudFavorites(userKey = userKey, japaneseTitles = cloud.japaneseTitles, defaultTitles = cloud.defaultTitles, pandaTitles = cloud.pandaTitles)
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

    /** Galleries of a favorites page the known list does not have in that slot: favorites added on the website. */
    fun newGalleries(cloud: CloudFavorites, galleries: List<IndexedGallery>): List<IndexedGallery> {
        val known = cloud.galleries.associate { it.gid to it.slot }
        return galleries.filter { it.slot in 0 until CLOUD_SLOT_COUNT && known[it.gid] != it.slot }
    }

    /** Galleries added, removed or moved to another slot between [before] and [after]. */
    fun changedGids(before: CloudFavorites, after: CloudFavorites): Set<Long> {
        if (before.galleries === after.galleries) return emptySet()
        val old = before.galleries.associate { it.gid to it.slot }
        val new = after.galleries.associate { it.gid to it.slot }
        return (old.keys + new.keys).filterTo(HashSet()) { old[it] != new[it] }
    }

    /**
     * An unfinished refresh going on with the slot [counts] E-Hentai shows now: from the first slot of the refresh
     * already read (or being read) whose count changed since, the slots are read again and their galleries dropped.
     */
    fun resumeCheckpoint(checkpoint: CloudRefreshCheckpoint, counts: List<Int>): CloudRefreshCheckpoint {
        val changed = checkpoint.slots.filter { it <= checkpoint.slot }.firstOrNull { slot ->
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
     * End of a refresh: the pages read replace the known galleries of the [slots] read, the other slots keep theirs.
     * Galleries changed in the app meanwhile keep their known state ([touched]) or take the slot the user put them in
     * ([intended]).
     */
    fun completeRefresh(
        cloud: CloudFavorites,
        galleries: List<IndexedGallery>,
        touched: Set<Long>,
        slotNames: List<String>,
        now: Long,
        intended: Map<Long, Int> = emptyMap(),
        slots: Collection<Int> = ALL_CLOUD_SLOTS,
    ): CloudFavorites {
        val read = slots.toHashSet()
        val pages = correctedPages(galleries, touched, intended)
        val pageGids = pages.mapTo(HashSet()) { it.gid }
        val kept = cloud.galleries.filter { it.gid in touched || it.gid in intended || it.slot !in read && it.gid !in pageGids }
        return replaceAll(cloud, pages + kept, slotNames, now)
    }

    /**
     * Adds the titles looked up: Japanese ones and [defaults] (gid -> title, empty if the gallery has none), see
     * [CloudFavorites.defaultTitles].
     */
    fun withJapaneseTitles(cloud: CloudFavorites, titles: Map<Long, String>, defaults: Map<Long, String> = emptyMap()): CloudFavorites {
        val added = titles.filter { (gid, title) -> cloud.japaneseTitles[gid] != title }
        val addedDefaults = defaults.filter { (gid, title) -> cloud.defaultTitles[gid] != title }
        if (added.isEmpty() && addedDefaults.isEmpty()) return cloud
        return cloud.copy(japaneseTitles = cloud.japaneseTitles + added, defaultTitles = cloud.defaultTitles + addedDefaults)
    }

    /**
     * Galleries of [index] whose Japanese title is still unknown, or (with [defaultTitles]) a cloud favorite whose
     * default title is, at most [limit]: the local favorites, then the cloud slots in turn, so the newest favorites of
     * every slot come first. A local favorite has its default title from the database.
     */
    fun missingJapaneseTitles(
        index: FavoritesIndex,
        limit: Int,
        defaultTitles: Map<Long, String>? = null,
        fromEnd: Boolean = false,
    ): List<IndexedGallery> {
        val missing = index.galleries.filter { it.titleJpn == null || defaultTitles != null && !it.local && it.gid !in defaultTitles }
        val (local, cloud) = missing.partition { it.local || it.slot !in 0 until CLOUD_SLOT_COUNT }
        val bySlot = cloud.groupBy { it.slot }.values.map { it.iterator() }
        val interleaved = sequence {
            while (bySlot.any { it.hasNext() }) bySlot.forEach { if (it.hasNext()) yield(it.next()) }
        }
        // Two devices looking up at once start from both ends, see FavoritesIndexSync.lookUpJapaneseTitles
        val ordered = if (fromEnd) interleaved.toList().asReversed().asSequence() else interleaved
        return (local.asSequence() + ordered).take(limit).toList()
    }

    /** [galleries] whose titles [cloud] still lacks: another device may have looked them up meanwhile. */
    fun stillMissing(galleries: List<IndexedGallery>, cloud: CloudFavorites): List<IndexedGallery> = galleries.filter {
        it.titleJpn == null && it.gid !in cloud.japaneseTitles || !it.local && it.gid !in cloud.defaultTitles
    }

    /**
     * Takes the titles another device looked up and this one lacks, whichever list is newer and whatever the account:
     * titles describe galleries. Its Japanese titles, and both titles of the galleries it marks as looked up.
     */
    fun withRemoteTitles(cloud: CloudFavorites, remote: FavoritesIndex): CloudFavorites {
        val titles = HashMap<Long, String>()
        val defaults = HashMap<Long, String>()
        remote.galleries.forEach { gallery ->
            val jpn = gallery.titleJpn
            if (jpn != null && gallery.gid !in cloud.japaneseTitles) titles[gallery.gid] = jpn
            if (gallery.titlesLookedUp && gallery.gid !in cloud.defaultTitles) defaults[gallery.gid] = gallery.title.orEmpty()
        }
        return withJapaneseTitles(cloud, titles, defaults)
    }

    /**
     * Slots to read again page by page: all of them when the list was never read completely, otherwise the slots
     * whose count shown by E-Hentai differs from the known list (favorites removed or moved on the website).
     */
    fun slotsToRefresh(cloud: CloudFavorites, counts: List<Int>): List<Int> {
        if (cloud.refreshedAt == 0L) return ALL_CLOUD_SLOTS
        if (counts.size != CLOUD_SLOT_COUNT) return emptyList()
        val known = cloud.countBySlot()
        return ALL_CLOUD_SLOTS.filter { counts[it] != known[it] }
    }

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
            defaultTitles = cloud.defaultTitles,
            pandaTitles = cloud.pandaTitles,
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
            val withJpn = if (gallery.titleJpn.isNullOrEmpty()) gallery.copy(titleJpn = cloud.japaneseTitles[gallery.gid] ?: gallery.titleJpn) else gallery
            val default = cloud.defaultTitles[gallery.gid] ?: return@map withJpn
            if (default.isNotEmpty()) return@map withDefaultTitle(withJpn, default).copy(titlesLookedUp = true)
            // None from the API (removed galleries, a few): the one of its archived raw copy, if found. The empty
            // Japanese title only says the API had nothing, the list title stays as the Japanese one
            val panda = cloud.pandaTitles[gallery.gid]?.takeIf { it.isNotEmpty() } ?: return@map withJpn
            withDefaultTitle(withJpn.copy(titleJpn = withJpn.titleJpn?.ifEmpty { null }), panda)
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
        val keptDefaults = cloud.defaultTitles.filterKeys { it in gids }
        val keptPanda = cloud.pandaTitles.filterKeys { it in gids }
        if (kept.size == cloud.japaneseTitles.size && keptDefaults.size == cloud.defaultTitles.size && keptPanda.size == cloud.pandaTitles.size) {
            return cloud
        }
        return cloud.copy(japaneseTitles = kept, defaultTitles = keptDefaults, pandaTitles = keptPanda)
    }

    /** Galleries of [index] the API had no titles for and panda.chaika.moe was not asked about yet. */
    fun missingPandaTitles(index: FavoritesIndex, cloud: CloudFavorites): List<IndexedGallery> = index.galleries.filter {
        cloud.defaultTitles[it.gid] == "" && it.gid !in cloud.pandaTitles && !pandaQuery(it).isNullOrEmpty()
    }

    /** The core of the Japanese title of [gallery] to search panda.chaika.moe with, its bracket groups removed. */
    fun pandaQuery(gallery: IndexedGallery): String? {
        val title = gallery.titleJpn?.takeIf { it.isNotEmpty() } ?: gallery.title ?: return null
        return coreTitle(title).takeIf { compact(it).length >= MIN_PANDA_TITLE }
    }

    /**
     * The default title of the archive among [archives] (default title to Japanese title) whose Japanese title is the
     * one of [gallery] (bracket groups, spaces and punctuation aside: the raw copy of a translation has other groups),
     * "" if none is.
     */
    fun pandaDefaultTitle(gallery: IndexedGallery, archives: List<Pair<String?, String?>>): String {
        val wanted = compact(coreTitle(gallery.titleJpn?.takeIf { it.isNotEmpty() } ?: gallery.title.orEmpty()))
        return archives.firstOrNull { (title, titleJpn) ->
            !title.isNullOrBlank() && titleJpn?.let { compact(coreTitle(it)) } == wanted
        }?.first.orEmpty()
    }

    /** Adds the titles found on panda.chaika.moe (gid -> title, empty if none). */
    fun withPandaTitles(cloud: CloudFavorites, titles: Map<Long, String>): CloudFavorites {
        val added = titles.filter { (gid, title) -> cloud.pandaTitles[gid] != title }
        return if (added.isEmpty()) cloud else cloud.copy(pandaTitles = cloud.pandaTitles + added)
    }

    private const val MIN_PANDA_TITLE = 4
    private val BRACKET_GROUPS = Regex("""\[[^\]]*\]|\([^)]*\)|【[^】]*】|（[^）]*）|\{[^}]*\}|［[^］]*］""")

    private fun coreTitle(title: String): String {
        val first = title.substringBefore('|')
        return first.replace(BRACKET_GROUPS, " ").trim().ifEmpty { first.trim() }
    }

    private fun compact(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }

    /**
     * [gallery] listing [default] as its title, the one it had moving to the Japanese title when that is unknown: the
     * list pages of an account showing Japanese titles give only those, the index then has both.
     */
    fun withDefaultTitle(gallery: IndexedGallery, default: String?): IndexedGallery {
        if (default.isNullOrEmpty() || default == gallery.title) return gallery
        // Empty: the API said it has no Japanese title, the one the list showed is not one (renamed since, for one)
        val jpn = gallery.titleJpn ?: gallery.title
        return gallery.copy(title = default, titleJpn = jpn.orEmpty())
    }
}
