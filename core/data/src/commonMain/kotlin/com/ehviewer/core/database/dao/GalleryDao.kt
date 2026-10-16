package com.ehviewer.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.ehviewer.core.database.model.GalleryEntity

@Dao
interface GalleryDao {
    @Query("SELECT * FROM GALLERIES WHERE GID = :gid")
    suspend fun load(gid: Long): GalleryEntity?

    @Query("SELECT * FROM GALLERIES")
    suspend fun list(): List<GalleryEntity>

    @Query("SELECT * FROM GALLERIES WHERE GID IN (:gids)")
    suspend fun load(gids: List<Long>): List<GalleryEntity>

    // Fork: the gids alone, for the reading progress of a sync
    @Query("SELECT GID FROM GALLERIES WHERE GID IN (:gids)")
    suspend fun existingGids(gids: List<Long>): List<Long>

    @Query(
        """DELETE FROM GALLERIES WHERE GID IN (:gids) AND GID NOT IN (SELECT GID FROM LOCAL_FAVORITES)
        AND GID NOT IN (SELECT GID FROM HISTORY) AND GID NOT IN (SELECT GID FROM DOWNLOADS)""",
    )
    suspend fun deleteUnreferenced(gids: List<Long>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(galleryInfoList: List<GalleryEntity>)

    @Update
    suspend fun update(galleryInfo: GalleryEntity)

    @Update
    suspend fun update(galleryInfoList: List<GalleryEntity>)

    @Upsert
    suspend fun upsert(galleryInfo: GalleryEntity)

    @Delete
    suspend fun delete(galleryInfo: GalleryEntity)

    @Query("DELETE FROM GALLERIES WHERE GID = :gid")
    suspend fun deleteByKey(gid: Long)
}
