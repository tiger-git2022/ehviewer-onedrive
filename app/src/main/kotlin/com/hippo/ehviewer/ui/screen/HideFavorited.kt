package com.hippo.ehviewer.ui.screen

import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.hippo.ehviewer.client.parser.GalleryListResult

// Fork: with Settings.hideFavoritedInLists, the gallery lists leave out the galleries in the cloud or local favorites

/** A page of favorites only is skipped for the one after it, at most this many in a row. */
const val MAX_SKIPPED_FAVORITED_PAGES = 10

/** The page without the galleries in the cloud favorites (marked on the page) or the local favorites. */
fun GalleryListResult.withoutFavorited() = copy(
    galleryInfoList = galleryInfoList.filterTo(mutableListOf()) { it.favoriteSlot == NOT_FAVORITED },
)
