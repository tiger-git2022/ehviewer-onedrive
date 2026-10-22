/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer

import androidx.room.execSQL
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import arrow.fx.coroutines.resource
import arrow.fx.coroutines.resourceScope
import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.database.EhDatabase
import com.ehviewer.core.database.Schema17to18
import com.ehviewer.core.database.getDatabasePath
import com.ehviewer.core.database.model.DownloadArtist
import com.ehviewer.core.database.model.DownloadDirname
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.database.model.DownloadLabel
import com.ehviewer.core.database.model.Filter
import com.ehviewer.core.database.model.GalleryEntity
import com.ehviewer.core.database.model.HistoryInfo
import com.ehviewer.core.database.model.LocalFavoriteInfo
import com.ehviewer.core.database.model.ProgressInfo
import com.ehviewer.core.database.model.QuickSearch
import com.ehviewer.core.database.model.Search
import com.ehviewer.core.database.roomDb
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.sendTo
import com.hippo.ehviewer.EhApplication.Companion.searchDatabase
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.sync.OneDriveSync
import com.hippo.ehviewer.sync.PendingProgress
import com.hippo.ehviewer.sync.SyncCategory
import kotlinx.coroutines.flow.Flow
import okio.Path

object EhDB {
    private const val DB_NAME = "eh.db"
    private val db = roomDb<EhDatabase>(DB_NAME) {
        addMigrations(Schema17to18())
    }

    suspend fun putGalleryInfo(galleryInfo: GalleryEntity) {
        db.galleryDao().upsert(galleryInfo)
        // Fork: reading progress synced before this device had the gallery
        PendingProgress.onGalleryAdded(galleryInfo.gid)
    }

    private suspend fun deleteGalleryInfo(galleryInfo: GalleryEntity) {
        runCatching { db.galleryDao().delete(galleryInfo) }
    }

    suspend fun updateGalleryInfo(galleryInfoList: List<GalleryEntity>) {
        db.galleryDao().update(galleryInfoList)
    }

    fun getReadProgressFlow(gid: Long) = db.progressDao().getPageFlow(gid)
    suspend fun getReadProgress(gid: Long) = db.progressDao().getPage(gid)
    suspend fun putReadProgress(gid: Long, page: Int) {
        db.progressDao().upsert(ProgressInfo(gid, page))
        OneDriveSync.notifyChanged(SyncCategory.PROGRESS)
    }

    suspend fun clearProgressInfo() {
        db.progressDao().deleteAll()
        OneDriveSync.notifyChanged(SyncCategory.PROGRESS)
    }

    suspend fun getAllDownloadInfo() = db.downloadsDao().joinList().onEach {
        if (it.state == DownloadInfo.STATE_WAIT || it.state == DownloadInfo.STATE_DOWNLOAD) {
            it.state = DownloadInfo.STATE_NONE
        }
    }

    suspend fun updateDownloadInfo(downloadInfo: Collection<DownloadInfo>) {
        val dao = db.downloadsDao()
        dao.update(downloadInfo.map(DownloadInfo::downloadInfo))
        OneDriveSync.notifyDownloadsChanged()
    }

    suspend fun putDownloadInfo(downloadInfo: DownloadInfo) {
        putGalleryInfo(downloadInfo.galleryInfo)
        db.downloadsDao().upsert(downloadInfo.downloadInfo)
        OneDriveSync.notifyDownloadsChanged()
    }

    suspend fun removeDownloadInfo(downloadInfo: DownloadInfo) {
        val dao = db.downloadsDao()
        dao.delete(downloadInfo.downloadInfo)
        deleteGalleryInfo(downloadInfo.galleryInfo)
        OneDriveSync.notifyDownloadsChanged()
    }

    suspend fun randomLocalFav() = db.localFavoritesDao().random()

    // Fork: one transaction for all, not two per gallery: removing dozens at once was slow
    suspend fun removeDownloadInfo(downloadInfo: List<DownloadInfo>) {
        if (downloadInfo.isEmpty()) return
        db.useWriterConnection { conn ->
            conn.immediateTransaction {
                downloadInfo.chunked(SQL_CHUNK).forEach { chunk ->
                    db.downloadsDao().delete(chunk.map { it.downloadInfo })
                    // Kept while in the history or the favorites (and so is the reading progress)
                    db.galleryDao().deleteUnreferenced(chunk.map { it.gid })
                }
            }
        }
        OneDriveSync.notifyDownloadsChanged()
    }

    suspend fun getDownloadDirname(gid: Long): String? {
        val dao = db.downloadDirnameDao()
        val raw = dao.load(gid)
        return raw?.dirname
    }

    suspend fun putDownloadDirname(gid: Long, dirname: String) {
        val dao = db.downloadDirnameDao()
        dao.upsert(DownloadDirname(gid, dirname))
    }

    suspend fun removeDownloadDirname(gid: Long) {
        val dao = db.downloadDirnameDao()
        dao.deleteByKey(gid)
    }

    private suspend fun importDownloadDirname(downloadDirnameList: List<DownloadDirname>) {
        val dao = db.downloadDirnameDao()
        dao.insertOrIgnore(downloadDirnameList)
    }

    val downloadsCountByLabel
        get() = db.downloadsDao().countByLabel()

    val downloadsCountByArtist
        get() = db.downloadsDao().countByArtist()

    suspend fun getAllDownloadLabelList() = db.downloadLabelDao().list()

    suspend fun addDownloadLabel(raw: DownloadLabel): DownloadLabel {
        // Reset id
        raw.id = null
        val dao = db.downloadLabelDao()
        raw.id = dao.insert(raw)
        OneDriveSync.notifyDownloadsChanged()
        return raw
    }

    suspend fun updateDownloadLabel(raw: DownloadLabel) {
        val dao = db.downloadLabelDao()
        dao.update(raw)
        OneDriveSync.notifyDownloadsChanged()
    }

    suspend fun updateDownloadLabel(downloadLabels: List<DownloadLabel>) {
        val dao = db.downloadLabelDao()
        dao.update(downloadLabels)
        OneDriveSync.notifyDownloadsChanged()
    }

    suspend fun removeDownloadLabel(raw: DownloadLabel) {
        val dao = db.downloadLabelDao()
        dao.delete(raw)
        dao.fill(raw.position)
        OneDriveSync.notifyDownloadsChanged()
    }

    suspend fun searchDownloadLabel(keyword: String, limit: Int) = db.downloadLabelDao().search("%$keyword%", limit)

    suspend fun putDownloadArtist(gid: Long, artists: List<DownloadArtist>) {
        if (artists.isNotEmpty()) {
            val dao = db.downloadArtistDao()
            dao.deleteByGid(gid)
            dao.insertOrIgnore(artists)
            OneDriveSync.notifyDownloadsChanged()
        }
    }

    suspend fun removeLocalFavorites(galleryInfo: GalleryInfo) {
        db.localFavoritesDao().deleteByKey(galleryInfo.gid)
        deleteGalleryInfo(galleryInfo.asEntity())
        OneDriveSync.notifyChanged(SyncCategory.FAVORITES)
    }

    suspend fun removeLocalFavorites(galleryInfoList: Collection<GalleryInfo>) {
        galleryInfoList.forEach {
            removeLocalFavorites(it)
        }
    }

    suspend fun containLocalFavorites(gid: Long): Boolean {
        val dao = db.localFavoritesDao()
        return dao.contains(gid)
    }

    suspend fun putLocalFavorites(galleryInfo: GalleryInfo) {
        putGalleryInfo(galleryInfo.asEntity())
        db.localFavoritesDao().upsert(LocalFavoriteInfo(galleryInfo.gid))
        OneDriveSync.notifyChanged(SyncCategory.FAVORITES)
    }

    suspend fun putLocalFavorites(galleryInfoList: Collection<GalleryInfo>) {
        galleryInfoList.forEach {
            putLocalFavorites(it)
        }
    }

    private suspend fun importLocalFavorites(localFavorites: List<LocalFavoriteInfo>) {
        db.localFavoritesDao().insertOrIgnore(localFavorites)
    }

    suspend fun getAllQuickSearch() = db.quickSearchDao().list()

    suspend fun insertQuickSearch(quickSearch: QuickSearch) {
        val dao = db.quickSearchDao()
        quickSearch.id = dao.insert(quickSearch)
        OneDriveSync.notifyChanged(SyncCategory.QUICK_SEARCH)
    }

    private suspend fun importQuickSearch(quickSearchList: List<QuickSearch>) {
        val dao = db.quickSearchDao()
        dao.insert(quickSearchList)
    }

    suspend fun deleteQuickSearch(quickSearch: QuickSearch) {
        val dao = db.quickSearchDao()
        dao.delete(quickSearch)
        dao.fill(quickSearch.position)
        OneDriveSync.notifyChanged(SyncCategory.QUICK_SEARCH)
    }

    suspend fun updateQuickSearch(quickSearchList: List<QuickSearch>) {
        val dao = db.quickSearchDao()
        dao.update(quickSearchList)
        OneDriveSync.notifyChanged(SyncCategory.QUICK_SEARCH)
    }

    val historyLazyList
        get() = db.historyDao().joinListLazy()

    fun searchHistory(keyword: String) = db.historyDao().joinListLazy("*$keyword*")

    val localFavLazyList
        get() = db.localFavoritesDao().joinListLazy()

    val localFavCount: Flow<Int>
        get() = db.localFavoritesDao().count()

    fun searchLocalFav(keyword: String) = db.localFavoritesDao().joinListLazy("*$keyword*")

    suspend fun putHistoryInfo(galleryInfo: GalleryInfo) {
        putGalleryInfo(galleryInfo.asEntity())
        db.historyDao().upsert(HistoryInfo(galleryInfo.gid))
        OneDriveSync.notifyChanged(SyncCategory.HISTORY)
    }

    suspend fun updateFavoriteSlot(gid: Long, slot: Int) {
        val dao = db.galleryDao()
        dao.load(gid)?.let {
            it.favoriteSlot = slot
            dao.update(it)
        }
    }

    private suspend fun importHistoryInfo(historyInfoList: List<HistoryInfo>) {
        val dao = db.historyDao()
        dao.insertOrIgnore(historyInfoList)
    }

    suspend fun deleteHistoryInfo(galleryInfo: GalleryEntity) {
        val dao = db.historyDao()
        dao.deleteByKey(galleryInfo.gid)
        deleteGalleryInfo(galleryInfo)
        OneDriveSync.notifyChanged(SyncCategory.HISTORY)
    }

    suspend fun clearHistoryInfo() {
        val dao = db.historyDao()
        val historyList = dao.list()
        dao.deleteAll()
        historyList.forEach { runCatching { db.galleryDao().deleteByKey(it.gid) } }
        OneDriveSync.notifyChanged(SyncCategory.HISTORY)
    }

    suspend fun getAllFilter() = db.filterDao().list()

    suspend fun addFilter(filter: Filter): Boolean {
        val existFilter = runCatching { db.filterDao().load(filter.text, filter.mode.field) }.getOrNull()
        return if (existFilter == null) {
            filter.id = null
            filter.id = db.filterDao().insert(filter)
            OneDriveSync.notifyChanged(SyncCategory.QUICK_SEARCH)
            true
        } else {
            false
        }
    }

    suspend fun deleteFilter(filter: Filter) {
        db.filterDao().delete(filter)
        OneDriveSync.notifyChanged(SyncCategory.QUICK_SEARCH)
    }

    suspend fun updateFilter(filter: Filter) {
        db.filterDao().update(filter)
        OneDriveSync.notifyChanged(SyncCategory.QUICK_SEARCH)
    }

    suspend fun exportDB(file: Path) {
        db.useWriterConnection { conn ->
            conn.execSQL("PRAGMA wal_checkpoint(FULL)")
            conn.execSQL("VACUUM")
        }
        val dbFile = getDatabasePath(DB_NAME)
        dbFile sendTo file
    }

    suspend fun importDB(file: Path) = resourceScope {
        val tempDBName = "tmp.db"
        val dbFile = getDatabasePath(tempDBName)
        file sendTo dbFile
        val oldDB = resource {
            roomDb<EhDatabase>(tempDBName) {
                addMigrations(Schema17to18())
            }
        } release { db ->
            db.close()
            dbFile.delete()
        }

        db.galleryDao().insertOrIgnore(oldDB.galleryDao().list())
        db.progressDao().insertOrIgnore(oldDB.progressDao().list())

        val downloadLabelList = oldDB.downloadLabelDao().list()
        DownloadManager.addDownloadLabel(downloadLabelList)

        oldDB.downloadDirnameDao().list().let {
            importDownloadDirname(it)
        }

        val downloadInfoList = oldDB.downloadsDao().joinList().asReversed()
        DownloadManager.addDownload(downloadInfoList)

        val historyInfoList = oldDB.historyDao().list()
        importHistoryInfo(historyInfoList)

        val quickSearchList = oldDB.quickSearchDao().list()
        val currentQuickSearchList = db.quickSearchDao().list()
        val offset = currentQuickSearchList.size
        val importList = quickSearchList.filter { newQS ->
            currentQuickSearchList.none { it.name == newQS.name }
        }.onEachIndexed { index, q ->
            q.id = null
            q.position = index + offset
        }
        importQuickSearch(importList)

        oldDB.localFavoritesDao().list().let {
            importLocalFavorites(it)
        }

        oldDB.filterDao().list().forEach {
            addFilter(it)
        }
        listOf(SyncCategory.FAVORITES, SyncCategory.HISTORY, SyncCategory.PROGRESS, SyncCategory.QUICK_SEARCH).forEach {
            OneDriveSync.notifyChanged(it)
        }
    }

    // OneDrive sync: snapshots and diff writes. The writes do not notify the sync, they come from it.

    suspend fun getLocalFavoritesWithGallery(): List<Pair<LocalFavoriteInfo, GalleryEntity>> {
        val dao = db.localFavoritesDao()
        val galleries = dao.listGalleries().associateBy { it.gid }
        return dao.list().mapNotNull { info -> galleries[info.gid]?.let { info to it } }
    }

    suspend fun getHistoryWithGallery(): List<Pair<HistoryInfo, GalleryEntity>> {
        val dao = db.historyDao()
        val galleries = dao.listGalleries().associateBy { it.gid }
        return dao.list().mapNotNull { info -> galleries[info.gid]?.let { info to it } }
    }

    suspend fun getAllReadProgress() = db.progressDao().list()

    suspend fun loadGalleries(gids: Collection<Long>): List<GalleryEntity> = gids.chunked(SQL_CHUNK).flatMap { db.galleryDao().load(it) }

    /** Fork: which of [gids] have a gallery row, without loading the rows. */
    suspend fun existingGalleryGids(gids: Collection<Long>): Set<Long> = gids.chunked(SQL_CHUNK).flatMapTo(HashSet()) { db.galleryDao().existingGids(it) }

    suspend fun applySyncedFavorites(
        insertGalleries: List<GalleryEntity>,
        updateGalleries: List<GalleryEntity>,
        upsert: List<LocalFavoriteInfo>,
        delete: List<Long>,
    ) = db.useWriterConnection { conn ->
        conn.immediateTransaction {
            writeSyncedGalleries(insertGalleries, updateGalleries)
            val dao = db.localFavoritesDao()
            if (upsert.isNotEmpty()) dao.upsert(upsert)
            delete.chunked(SQL_CHUNK).forEach {
                dao.deleteByKeys(it)
                db.galleryDao().deleteUnreferenced(it)
            }
        }
    }

    suspend fun applySyncedHistory(
        insertGalleries: List<GalleryEntity>,
        updateGalleries: List<GalleryEntity>,
        upsert: List<HistoryInfo>,
        delete: List<Long>,
    ) = db.useWriterConnection { conn ->
        conn.immediateTransaction {
            writeSyncedGalleries(insertGalleries, updateGalleries)
            val dao = db.historyDao()
            if (upsert.isNotEmpty()) dao.upsert(upsert)
            delete.chunked(SQL_CHUNK).forEach {
                dao.deleteByKeys(it)
                db.galleryDao().deleteUnreferenced(it)
            }
        }
    }

    private suspend fun writeSyncedGalleries(insert: List<GalleryEntity>, update: List<GalleryEntity>) {
        val dao = db.galleryDao()
        if (insert.isNotEmpty()) dao.insertOrIgnore(insert)
        if (update.isNotEmpty()) dao.update(update)
    }

    /**
     * Fork: synced progress of galleries this device just got, where it has none or only the first page (the reader
     * opened and closed again), never over a page read further here.
     */
    suspend fun putSyncedProgressIfUnread(progress: List<ProgressInfo>) {
        if (progress.isEmpty()) return
        // One transaction: the reader saving a page in between is never written over
        db.useWriterConnection { conn ->
            conn.immediateTransaction {
                val dao = db.progressDao()
                val read = dao.list().filter { it.page > 0 }.mapTo(HashSet()) { it.gid }
                val write = progress.filter { it.gid !in read }
                if (write.isNotEmpty()) dao.upsert(write)
            }
        }
    }

    suspend fun applySyncedProgress(upsert: List<ProgressInfo>, delete: List<Long>) = db.useWriterConnection { conn ->
        conn.immediateTransaction {
            val dao = db.progressDao()
            if (upsert.isNotEmpty()) dao.upsert(upsert)
            delete.chunked(SQL_CHUNK).forEach { dao.deleteByKeys(it) }
        }
    }

    suspend fun applySyncedQuickSearch(
        insert: List<QuickSearch>,
        update: List<QuickSearch>,
        delete: List<QuickSearch>,
        insertFilters: List<Filter>,
        updateFilters: List<Filter>,
        deleteFilters: List<Filter>,
    ) = db.useWriterConnection { conn ->
        conn.immediateTransaction {
            val dao = db.quickSearchDao()
            if (delete.isNotEmpty()) dao.delete(delete)
            if (update.isNotEmpty()) dao.update(update)
            if (insert.isNotEmpty()) dao.insert(insert)
            val filterDao = db.filterDao()
            if (deleteFilters.isNotEmpty()) filterDao.delete(deleteFilters)
            if (updateFilters.isNotEmpty()) filterDao.update(updateFilters)
            if (insertFilters.isNotEmpty()) filterDao.insert(insertFilters)
        }
    }

    suspend fun getAllSuggestions() = searchDatabase.searchDao().listAll()

    suspend fun applySyncedSuggestions(upsert: List<Search>, delete: List<String>) = searchDatabase.useWriterConnection { conn ->
        conn.immediateTransaction {
            val dao = searchDatabase.searchDao()
            (delete + upsert.map { it.query }).chunked(SQL_CHUNK).forEach { dao.deleteQueries(it) }
            if (upsert.isNotEmpty()) dao.insert(upsert)
        }
    }

    // SQLite allows 999 host parameters before 3.32
    private const val SQL_CHUNK = 500
}
