package com.hippo.ehviewer.sync

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.ehviewer.core.database.model.Filter
import com.ehviewer.core.database.model.FilterMode
import com.ehviewer.core.database.model.GalleryEntity
import com.ehviewer.core.database.model.HistoryInfo
import com.ehviewer.core.database.model.LocalFavoriteInfo
import com.ehviewer.core.database.model.ProgressInfo
import com.ehviewer.core.database.model.QuickSearch
import com.ehviewer.core.database.model.Search
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhFilter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** What applying a merge result changed locally. */
data class SyncCounts(val added: Int = 0, val updated: Int = 0, val removed: Int = 0) {
    val isEmpty: Boolean
        get() = added == 0 && updated == 0 && removed == 0

    operator fun plus(other: SyncCounts) = SyncCounts(added + other.added, updated + other.updated, removed + other.removed)
}

/**
 * Local side of one synced category: read the current state, merge, and write back only the difference.
 * The writes go through dedicated EhDB functions that do not mark the data as changed.
 */
sealed class SyncSection<T>(val category: SyncCategory, val serializer: KSerializer<T>, val empty: T) {
    abstract suspend fun collect(baseline: T?, changedAt: Long): T
    abstract fun merge(local: T, cloud: T, baseline: T?): T
    abstract suspend fun apply(local: T, merged: T): SyncCounts

    companion object {
        val all: List<SyncSection<*>> by lazy {
            listOf(SettingsSection, FavoritesSection, HistorySection, ProgressSection, QuickSearchSection, SuggestionsSection)
        }
    }
}

fun GalleryInfo.toSyncGallery() = SyncGallery(
    gid = gid,
    token = token,
    title = title,
    titleJpn = titleJpn,
    thumbKey = thumbKey,
    category = category,
    posted = posted,
    uploader = uploader,
    rating = rating,
    simpleTags = simpleTags,
    pages = pages,
    simpleLanguage = simpleLanguage,
    favoriteSlot = favoriteSlot,
)

fun SyncGallery.toEntity() = GalleryEntity(
    gid = gid,
    token = token,
    title = title,
    titleJpn = titleJpn,
    thumbKey = thumbKey,
    category = category,
    posted = posted,
    uploader = uploader,
    rating = rating,
    simpleTags = simpleTags,
    pages = pages,
    simpleLanguage = simpleLanguage,
    favoriteSlot = favoriteSlot,
)

/** Galleries to insert (missing here) and to update (the synced copy is more complete). */
private suspend fun galleryWrites(records: Collection<SyncGalleryRecord>): Pair<List<GalleryEntity>, List<GalleryEntity>> {
    if (records.isEmpty()) return emptyList<GalleryEntity>() to emptyList()
    val existing = EhDB.loadGalleries(records.map { it.gid }).associateBy { it.gid }
    val insert = mutableListOf<GalleryEntity>()
    val update = mutableListOf<GalleryEntity>()
    records.forEach { record ->
        val local = existing[record.gid]
        when {
            local == null -> insert += record.gallery.toEntity()
            record.gallery.completeness > local.toSyncGallery().completeness -> update += record.gallery.toEntity()
        }
    }
    return insert to update
}

object SettingsSection : SyncSection<Map<String, SyncSetting>>(
    SyncCategory.SETTINGS,
    MapSerializer(String.serializer(), SyncSetting.serializer()),
    emptyMap(),
) {
    override suspend fun collect(baseline: Map<String, SyncSetting>?, changedAt: Long): Map<String, SyncSetting> {
        val values = Settings.rawData.first().asMap()
        val modifiedAt = OneDriveSyncState.readLongMap(OneDriveSyncState.settingsModifiedAt)
        val result = sortedMapOf<String, SyncSetting>()
        values.forEach { (key, value) ->
            if (OneDriveSyncMerger.isSyncableSetting(key.name)) {
                value.toSyncSetting(modifiedAt[key.name] ?: 0)?.let { result[key.name] = it }
            }
        }
        // Keys reset to their default since they were last synced
        modifiedAt.forEach { (key, time) ->
            if (key !in result && OneDriveSyncMerger.isSyncableSetting(key)) {
                result[key] = SyncSetting(SyncSetting.TYPE_REMOVED, null, time)
            }
        }
        return result
    }

    override fun merge(local: Map<String, SyncSetting>, cloud: Map<String, SyncSetting>, baseline: Map<String, SyncSetting>?) = OneDriveSyncMerger.mergeSettings(local, cloud)

    override suspend fun apply(local: Map<String, SyncSetting>, merged: Map<String, SyncSetting>): SyncCounts {
        val writes = merged.filter { (key, setting) ->
            val current = local[key]
            val currentRemoved = current == null || current.type == SyncSetting.TYPE_REMOVED
            if (setting.type == SyncSetting.TYPE_REMOVED) {
                !currentRemoved
            } else {
                current == null || current.type != setting.type || current.value != setting.value
            }
        }
        if (writes.isEmpty()) return SyncCounts()
        OneDriveSync.applySettings(writes)
        return SyncCounts(
            added = writes.count { (key, setting) -> local[key] == null && setting.type != SyncSetting.TYPE_REMOVED },
            updated = writes.count { (key, setting) -> local[key] != null && setting.type != SyncSetting.TYPE_REMOVED },
            removed = writes.count { (_, setting) -> setting.type == SyncSetting.TYPE_REMOVED },
        )
    }

    private fun Any.toSyncSetting(modifiedAt: Long): SyncSetting? = when (this) {
        is Boolean -> SyncSetting(SyncSetting.TYPE_BOOLEAN, JsonPrimitive(this), modifiedAt)
        is Int -> SyncSetting(SyncSetting.TYPE_INT, JsonPrimitive(this), modifiedAt)
        is Long -> SyncSetting(SyncSetting.TYPE_LONG, JsonPrimitive(this), modifiedAt)
        is Float -> SyncSetting(SyncSetting.TYPE_FLOAT, JsonPrimitive(this), modifiedAt)
        is Double -> SyncSetting(SyncSetting.TYPE_DOUBLE, JsonPrimitive(this), modifiedAt)
        is String -> SyncSetting(SyncSetting.TYPE_STRING, JsonPrimitive(this), modifiedAt)
        is Set<*> -> SyncSetting(SyncSetting.TYPE_STRING_SET, JsonArray(filterIsInstance<String>().sorted().map { JsonPrimitive(it) }), modifiedAt)
        else -> null
    }
}

/** The DataStore key and value of a synced setting, null value for a removed key. */
fun SyncSetting.toPreference(key: String): Pair<Preferences.Key<*>, Any?>? {
    val primitive = value as? JsonPrimitive
    val prefKey: Preferences.Key<*>
    val prefValue: Any?
    when (type) {
        SyncSetting.TYPE_BOOLEAN -> {
            prefKey = booleanPreferencesKey(key)
            prefValue = primitive?.booleanOrNull ?: return null
        }
        SyncSetting.TYPE_INT -> {
            prefKey = intPreferencesKey(key)
            prefValue = primitive?.intOrNull ?: return null
        }
        SyncSetting.TYPE_LONG -> {
            prefKey = longPreferencesKey(key)
            prefValue = primitive?.longOrNull ?: return null
        }
        SyncSetting.TYPE_FLOAT -> {
            prefKey = floatPreferencesKey(key)
            prefValue = primitive?.floatOrNull ?: return null
        }
        SyncSetting.TYPE_DOUBLE -> {
            prefKey = doublePreferencesKey(key)
            prefValue = primitive?.doubleOrNull ?: return null
        }
        SyncSetting.TYPE_STRING -> {
            prefKey = stringPreferencesKey(key)
            prefValue = primitive?.takeIf { it.isString }?.content ?: return null
        }
        SyncSetting.TYPE_STRING_SET -> {
            prefKey = stringSetPreferencesKey(key)
            prefValue = (value as? JsonArray)?.mapNotNullTo(HashSet()) { (it as? JsonPrimitive)?.content } ?: return null
        }
        SyncSetting.TYPE_REMOVED -> {
            prefKey = stringPreferencesKey(key)
            prefValue = null
        }
        else -> return null
    }
    return Pair(prefKey, prefValue)
}

object FavoritesSection : SyncSection<List<SyncGalleryRecord>>(
    SyncCategory.FAVORITES,
    ListSerializer(SyncGalleryRecord.serializer()),
    emptyList(),
) {
    override suspend fun collect(baseline: List<SyncGalleryRecord>?, changedAt: Long) = EhDB.getLocalFavoritesWithGallery().map { (info, gallery) ->
        SyncGalleryRecord(info.gid, info.time, gallery.toSyncGallery())
    }

    override fun merge(local: List<SyncGalleryRecord>, cloud: List<SyncGalleryRecord>, baseline: List<SyncGalleryRecord>?) = OneDriveSyncMerger.mergeFavorites(local, cloud, baseline)

    override suspend fun apply(local: List<SyncGalleryRecord>, merged: List<SyncGalleryRecord>): SyncCounts {
        val localByGid = local.associateBy { it.gid }
        val mergedGids = merged.mapTo(HashSet()) { it.gid }
        val upsert = merged.filter { localByGid[it.gid]?.time != it.time }
        val delete = local.filter { it.gid !in mergedGids }.map { it.gid }
        if (upsert.isEmpty() && delete.isEmpty()) return SyncCounts()
        val (insertGalleries, updateGalleries) = galleryWrites(upsert)
        EhDB.applySyncedFavorites(insertGalleries, updateGalleries, upsert.map { LocalFavoriteInfo(it.gid, it.time) }, delete)
        val added = upsert.count { it.gid !in localByGid }
        return SyncCounts(added = added, updated = upsert.size - added, removed = delete.size)
    }
}

object HistorySection : SyncSection<List<SyncGalleryRecord>>(
    SyncCategory.HISTORY,
    ListSerializer(SyncGalleryRecord.serializer()),
    emptyList(),
) {
    override suspend fun collect(baseline: List<SyncGalleryRecord>?, changedAt: Long) = EhDB.getHistoryWithGallery().map { (info, gallery) ->
        SyncGalleryRecord(info.gid, info.time, gallery.toSyncGallery())
    }

    override fun merge(local: List<SyncGalleryRecord>, cloud: List<SyncGalleryRecord>, baseline: List<SyncGalleryRecord>?) = OneDriveSyncMerger.mergeHistory(local, cloud, baseline)

    override suspend fun apply(local: List<SyncGalleryRecord>, merged: List<SyncGalleryRecord>): SyncCounts {
        val localByGid = local.associateBy { it.gid }
        val mergedGids = merged.mapTo(HashSet()) { it.gid }
        val upsert = merged.filter { localByGid[it.gid]?.time != it.time }
        val delete = local.filter { it.gid !in mergedGids }.map { it.gid }
        if (upsert.isEmpty() && delete.isEmpty()) return SyncCounts()
        val (insertGalleries, updateGalleries) = galleryWrites(upsert)
        EhDB.applySyncedHistory(insertGalleries, updateGalleries, upsert.map { HistoryInfo(it.gid, it.time) }, delete)
        val added = upsert.count { it.gid !in localByGid }
        return SyncCounts(added = added, updated = upsert.size - added, removed = delete.size)
    }
}

/**
 * Reading progress needs the gallery row (foreign key), so progress of galleries this device does not know
 * cannot be stored here. Such entries are carried over from the baseline instead of counting as deleted, and wait in
 * [PendingProgress] until the gallery is added. Fork: progress still pending is carried over too once the gallery is
 * there, it was never written; before, it was taken for a deletion and removed on every device.
 */
object ProgressSection : SyncSection<List<SyncProgress>>(
    SyncCategory.PROGRESS,
    ListSerializer(SyncProgress.serializer()),
    emptyList(),
) {
    // Fork: gids collect carried over from the baseline, without a row here
    @Volatile
    private var carried: Set<Long> = emptySet()

    override suspend fun collect(baseline: List<SyncProgress>?, changedAt: Long): List<SyncProgress> {
        carried = emptySet()
        val baselineByGid = baseline.orEmpty().associateBy { it.gid }
        val rows = EhDB.getAllReadProgress()
        val missing = missingProgress(baselineByGid.values, rows.associate { it.gid to it.page })
        val kept = if (missing.isEmpty()) {
            emptySet()
        } else {
            carriedProgress(missing, EhDB.existingGalleryGids(missing), PendingProgress.get()?.keys).toSet()
        }
        carried = kept
        val local = rows.filter { it.gid !in kept }.map { row ->
            val base = baselineByGid[row.gid]
            SyncProgress(row.gid, row.page, if (base != null && base.page == row.page) base.modifiedAt else changedAt)
        }
        return local + kept.map { baselineByGid.getValue(it) }
    }

    /**
     * Fork: the gids of the [baseline] progress this device has no row for, or one at the first page where the baseline
     * went further ([pageByGid], the rows here): opening the reader writes that, so if the progress was still pending,
     * it is no progress of this device.
     */
    internal fun missingProgress(baseline: Collection<SyncProgress>, pageByGid: Map<Long, Int>): List<Long> = baseline.filter { base ->
        val page = pageByGid[base.gid]
        page == null || page == 0 && base.page > 0
    }.map { it.gid }

    /**
     * Fork: of the progress the baseline has and this device has no row for, or one at the first page ([missing]), the
     * progress to carry over
     * instead of taking it for a deletion: that of galleries this device does not have, and progress still pending,
     * never written here (all of it before the first sync that kept the pending progress, [pending] null).
     */
    internal fun carriedProgress(missing: Collection<Long>, galleries: Set<Long>, pending: Set<Long>?): List<Long> = missing.filter { it !in galleries || pending == null || it in pending }

    override fun merge(local: List<SyncProgress>, cloud: List<SyncProgress>, baseline: List<SyncProgress>?) = OneDriveSyncMerger.mergeProgress(local, cloud, baseline)

    override suspend fun apply(local: List<SyncProgress>, merged: List<SyncProgress>): SyncCounts {
        val localByGid = local.associateBy { it.gid }
        val mergedGids = merged.mapTo(HashSet()) { it.gid }
        val changed = merged.filter { localByGid[it.gid]?.page != it.page }
        // Fork: all galleries of the progress, to keep what waits for its gallery
        val galleries = EhDB.existingGalleryGids(mergedGids)
        val upsert = changed.filter { it.gid in galleries }
        // Fork: carried over, unchanged in the cloud, and the gallery is here now: written unless read further here
        val arrived = merged.filter { it.gid in carried && it.gid in galleries && localByGid[it.gid]?.page == it.page }
        val delete = local.filter { it.gid !in mergedGids }.map { it.gid }
        PendingProgress.replace(merged.filter { it.gid !in galleries }.associate { it.gid to it.page })
        EhDB.putSyncedProgressIfUnread(arrived.map { ProgressInfo(it.gid, it.page) })
        if (upsert.isEmpty() && delete.isEmpty()) return SyncCounts(added = arrived.size)
        EhDB.applySyncedProgress(upsert.map { ProgressInfo(it.gid, it.page) }, delete)
        val added = upsert.count { it.gid !in localByGid }
        return SyncCounts(added = added + arrived.size, updated = upsert.size - added, removed = delete.size)
    }
}

object QuickSearchSection : SyncSection<SyncQuickSearchSection>(
    SyncCategory.QUICK_SEARCH,
    SyncQuickSearchSection.serializer(),
    SyncQuickSearchSection(),
) {
    override suspend fun collect(baseline: SyncQuickSearchSection?, changedAt: Long): SyncQuickSearchSection {
        val baseQuickSearches = baseline?.quickSearches.orEmpty().associateBy { it.name }
        val baseFilters = baseline?.filters.orEmpty().associateBy { it.key }
        val quickSearches = EhDB.getAllQuickSearch().map { row ->
            val item = row.toSync(0)
            val base = baseQuickSearches[item.name]
            item.copy(modifiedAt = if (base != null && base.sameContent(item)) base.modifiedAt else changedAt)
        }.distinctBy { it.name }
        val filters = EhDB.getAllFilter().map { row ->
            val base = baseFilters[row.mode.field to row.text]
            SyncFilter(row.mode.field, row.text, row.enable, if (base != null && base.enable == row.enable) base.modifiedAt else changedAt)
        }.distinctBy { it.key }
        return SyncQuickSearchSection(quickSearches, filters)
    }

    override fun merge(local: SyncQuickSearchSection, cloud: SyncQuickSearchSection, baseline: SyncQuickSearchSection?) = OneDriveSyncMerger.mergeQuickSearch(local, cloud, baseline)

    override suspend fun apply(local: SyncQuickSearchSection, merged: SyncQuickSearchSection): SyncCounts {
        val rows = EhDB.getAllQuickSearch()
        val rowsByName = rows.groupBy { it.name }
        val mergedNames = merged.quickSearches.mapTo(HashSet()) { it.name }
        val insert = mutableListOf<QuickSearch>()
        val update = mutableListOf<QuickSearch>()
        merged.quickSearches.forEachIndexed { position, item ->
            val row = rowsByName[item.name]?.first()
            if (row == null) {
                insert += item.toEntity(null, position)
            } else {
                val updated = item.toEntity(row.id, position)
                if (updated != row) update += updated
            }
        }
        // Rows no longer in the synced list, and duplicate names
        val delete = rows.filter { it.name !in mergedNames } + rowsByName.values.flatMap { it.drop(1) }

        val filterRows = EhDB.getAllFilter()
        val filterRowsByKey = filterRows.associateBy { it.mode.field to it.text }
        val mergedFilterKeys = merged.filters.mapTo(HashSet()) { it.key }
        val insertFilters = mutableListOf<Filter>()
        val updateFilters = mutableListOf<Filter>()
        merged.filters.forEach { item ->
            val row = filterRowsByKey[item.key]
            val mode = FilterMode.entries.firstOrNull { it.field == item.mode } ?: return@forEach
            when {
                row == null -> insertFilters += Filter(mode, item.text, item.enable)
                row.enable != item.enable -> updateFilters += row.copy(enable = item.enable)
            }
        }
        val deleteFilters = filterRows.filter { (it.mode.field to it.text) !in mergedFilterKeys }

        val counts = SyncCounts(
            added = insert.size + insertFilters.size,
            updated = update.size + updateFilters.size,
            removed = delete.size + deleteFilters.size,
        )
        if (counts.isEmpty) return counts
        EhDB.applySyncedQuickSearch(insert, update, delete, insertFilters, updateFilters, deleteFilters)
        if (insertFilters.isNotEmpty() || updateFilters.isNotEmpty() || deleteFilters.isNotEmpty()) EhFilter.reload()
        return counts
    }

    private fun QuickSearch.toSync(modifiedAt: Long) = SyncQuickSearch(
        name = name,
        mode = mode,
        category = category,
        keyword = keyword,
        advanceSearch = advanceSearch,
        minRating = minRating,
        pageFrom = pageFrom,
        pageTo = pageTo,
        modifiedAt = modifiedAt,
    )

    private fun SyncQuickSearch.toEntity(id: Long?, position: Int) = QuickSearch(
        id = id,
        name = name,
        mode = mode,
        category = category,
        keyword = keyword,
        advanceSearch = advanceSearch,
        minRating = minRating,
        pageFrom = pageFrom,
        pageTo = pageTo,
        position = position,
    )
}

object SuggestionsSection : SyncSection<List<SyncSuggestion>>(
    SyncCategory.SUGGESTIONS,
    ListSerializer(SyncSuggestion.serializer()),
    emptyList(),
) {
    override suspend fun collect(baseline: List<SyncSuggestion>?, changedAt: Long) = EhDB.getAllSuggestions()
        .groupBy { it.query }
        .map { (query, rows) -> SyncSuggestion(query, rows.maxOf { it.date }) }

    override fun merge(local: List<SyncSuggestion>, cloud: List<SyncSuggestion>, baseline: List<SyncSuggestion>?) = OneDriveSyncMerger.mergeSuggestions(local, cloud, baseline)

    override suspend fun apply(local: List<SyncSuggestion>, merged: List<SyncSuggestion>): SyncCounts {
        val localByQuery = local.associateBy { it.query }
        val mergedQueries = merged.mapTo(HashSet()) { it.query }
        val upsert = merged.filter { localByQuery[it.query]?.date != it.date }
        val delete = local.filter { it.query !in mergedQueries }.map { it.query }
        if (upsert.isEmpty() && delete.isEmpty()) return SyncCounts()
        EhDB.applySyncedSuggestions(upsert.map { Search(it.date, it.query) }, delete)
        val added = upsert.count { it.query !in localByQuery }
        return SyncCounts(added = added, updated = upsert.size - added, removed = delete.size)
    }
}
