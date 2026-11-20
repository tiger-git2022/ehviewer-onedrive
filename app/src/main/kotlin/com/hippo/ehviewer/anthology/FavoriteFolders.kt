package com.hippo.ehviewer.anthology

import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.sync.CLOUD_SLOT_COUNT
import com.hippo.ehviewer.sync.FavoritesIndexSync
import com.hippo.ehviewer.todownload.ToDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * Fork: resolves the favorite folder name(s) of galleries (cloud slot name, 「待下载」, or 「本地收藏」) from the local
 * database and the cached cloud favorites index without making E-Hentai requests.
 */
object FavoriteFolders {
    private const val TAG = "FavoriteFolders"

    /** Folder name for a cloud slot (0..9), or null when not a cloud slot. */
    fun cloudFolderName(slot: Int, slotNames: List<String> = emptyList()): String? {
        if (slot !in 0 until CLOUD_SLOT_COUNT) return null
        return Settings.favCat.getOrNull(slot)?.takeIf { it.isNotBlank() }
            ?: slotNames.getOrNull(slot)?.takeIf { it.isNotBlank() }
            ?: "Favorites $slot"
    }

    /** Folder name for a local favorite, distinguishing 「待下载」 from 「本地收藏」. */
    fun localFolderName(gid: Long, toDownload: Set<Long> = ToDownload.gids.value): String =
        if (gid in toDownload) appCtx.getString(R.string.local_favorites_to_download) else appCtx.getString(R.string.local_favorites)

    /** Folder name(s) known from [favoriteName], [favoriteSlot], [cloudSlot], and [isLocal]. */
    fun folderNamesOf(
        gid: Long,
        favoriteName: String? = null,
        favoriteSlot: Int = GalleryInfo.NOT_FAVORITED,
        cloudSlot: Int? = null,
        isLocal: Boolean = false,
        slotNames: List<String> = emptyList(),
        toDownload: Set<Long> = ToDownload.gids.value,
    ): List<String> {
        val result = LinkedHashSet<String>()
        val cloud = favoriteName?.takeIf { it.isNotBlank() }
            ?: cloudFolderName(favoriteSlot, slotNames)
            ?: cloudSlot?.let { cloudFolderName(it, slotNames) }
        if (cloud != null) result += cloud
        if (isLocal || favoriteSlot == GalleryInfo.LOCAL_FAVORITED) {
            result += localFolderName(gid, toDownload)
        }
        return result.toList()
    }

    /** Looks up favorite folder name(s) for each gid in [gids] across local favorites and cached cloud favorites. */
    suspend fun lookup(gids: Collection<Long>): Map<Long, List<String>> = withContext(Dispatchers.IO) {
        val wanted = gids.toSet()
        if (wanted.isEmpty()) return@withContext emptyMap()
        val cloud = runCatching { FavoritesIndexSync.cloudFavorites() }.onFailure { logcat(TAG, it) }.getOrNull()
        val cloudSlots = if (cloud != null && Settings.hasSignedIn.value) {
            HashMap<Long, Int>().apply {
                cloud.galleries.forEach { if (it.gid in wanted && it.slot in 0 until CLOUD_SLOT_COUNT) put(it.gid, it.slot) }
            }
        } else {
            emptyMap()
        }
        val slotNames = cloud?.slotNames.orEmpty()
        val localGids = runCatching {
            wanted.filterTo(HashSet()) { EhDB.containLocalFavorites(it) }
        }.onFailure { logcat(TAG, it) }.getOrDefault(emptySet())
        val toDownload = ToDownload.gids.value
        val result = HashMap<Long, List<String>>()
        for (gid in wanted) {
            val folders = folderNamesOf(
                gid = gid,
                cloudSlot = cloudSlots[gid],
                isLocal = gid in localGids,
                slotNames = slotNames,
                toDownload = toDownload,
            )
            if (folders.isNotEmpty()) result[gid] = folders
        }
        result
    }
}
