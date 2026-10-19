package com.hippo.ehviewer.ui

import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.LOCAL_FAVORITED
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.util.FavouriteStatusRouter
import kotlinx.coroutines.CancellationException

/**
 * Fork: a gallery opened that is in no favorites (cloud or local) goes to the local favorites, with
 * [Settings.autoLocalFavorite] on. Each gallery only once per app run: removed from the favorites after that, it
 * stays removed when opened again.
 */
object AutoLocalFavorite {
    private const val TAG = "AutoLocalFavorite"
    private val handled = mutableSetOf<Long>()

    /** [listed] is the gallery as the list it was opened from showed it, newer than a detail kept in the cache. */
    suspend fun onOpened(info: GalleryInfo, listed: GalleryInfo? = null) {
        if (!Settings.autoLocalFavorite.value || info.favoriteSlot != NOT_FAVORITED) return
        if (listed != null && listed.favoriteSlot != NOT_FAVORITED) return
        if (!synchronized(handled) { handled.add(info.gid) }) return
        var done = false
        try {
            if (!EhDB.containLocalFavorites(info.gid)) EhDB.putLocalFavorites(info)
            // Also when an earlier attempt added it but was left before this: the gallery still read "not favorited"
            info.favoriteSlot = LOCAL_FAVORITED
            // Same as adding it with the favorite button: the button, the lists and the sync learn of it
            FavouriteStatusRouter.notify(info)
            done = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(TAG, e)
        } finally {
            // Left before it was added, or failed: the next opening tries again
            if (!done) synchronized(handled) { handled.remove(info.gid) }
        }
    }
}
