package com.hippo.ehviewer.ui.screen

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.serialization.saved
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.data.ListUrlBuilder.Companion.MODE_TOPLIST
import com.hippo.ehviewer.ui.tools.foldToLoadResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.tarsin.coroutines.runSuspendCatching

class GalleryListViewModel(lub: ListUrlBuilder, fromGallery: Boolean, savedStateHandle: SavedStateHandle) : ViewModel() {
    private val mutex = Mutex()

    // Fork: a list opened from a gallery has its own filters, see Settings.linkedLanguageFilter
    private val languagePref = if (fromGallery) Settings.linkedLanguageFilter else Settings.languageFilter
    private val hideFavoritedPref = if (fromGallery) Settings.linkedHideFavorited else Settings.hideFavoritedInLists

    val urlBuilder by savedStateHandle.saved(MutableStateSerializer()) {
        // Fork: the language filter ("汉") of when the list opened
        mutableStateOf(lub.withLanguageFilter(languagePref.value))
    }

    // Fork: whether the pages loaded last hid the favorited galleries; the screen reloads when the setting differs, also
    // when it changed on another list while this one was in the back stack
    @Volatile
    var hideFavoritedApplied: Boolean? = null
        private set

    // Use a smaller prefetch distance to avoid prepending an additional page after jumping
    val data = Pager(PagingConfig(25, prefetchDistance = 20)) {
        object : PagingSource<String, BaseGalleryInfo>() {
            override fun getRefreshKey(state: PagingState<String, BaseGalleryInfo>): String? = null
            override suspend fun load(params: LoadParams<String>) = withIOContext {
                val urlBuilder = urlBuilder.value
                if (urlBuilder.mode == MODE_TOPLIST) {
                    // TODO: Since we know total pages, let pager support jump
                    var key: Int
                    val url = mutex.withLock {
                        with(urlBuilder) {
                            params.key?.let { page = it.toInt() }
                            key = page
                            build()
                        }
                    }
                    runSuspendCatching {
                        var result = EhEngine.getGalleryList(url)
                        // Fork: hide the favorited galleries; a page of favorites only goes on to the page after it
                        // (before it when prepending), as an empty page ended the list
                        val hide = hideFavoritedPref.value
                        hideFavoritedApplied = hide
                        if (hide) {
                            val forward = params !is LoadParams.Prepend
                            var skipped = 0
                            var shown = result.withoutFavorited()
                            while (shown.galleryInfoList.isEmpty() && skipped < MAX_SKIPPED_FAVORITED_PAGES) {
                                val other = if (forward) key + 1 else key - 1
                                if (other < 1 || other > TOPLIST_PAGES) break
                                key = other
                                val otherUrl = mutex.withLock {
                                    with(urlBuilder) {
                                        page = other
                                        build()
                                    }
                                }
                                result = EhEngine.getGalleryList(otherUrl)
                                shown = result.withoutFavorited()
                                skipped++
                            }
                            result = shown
                        }
                        result
                    }.foldToLoadResult { result ->
                        val prev = (key - 1).takeIf { it > 0 }
                        val next = (key + 1).takeIf { it <= TOPLIST_PAGES }
                        LoadResult.Page(result.galleryInfoList, prev?.toString(), next?.toString())
                    }
                } else {
                    val url = mutex.withLock {
                        with(urlBuilder) {
                            when (params) {
                                is LoadParams.Prepend -> setIndex(params.key, isNext = false)
                                is LoadParams.Append -> setIndex(params.key, isNext = true)
                                is LoadParams.Refresh -> params.key?.let { setIndex(it, false) }
                            }
                            build()
                        }
                    }
                    runSuspendCatching {
                        var result = EhEngine.getGalleryList(url)
                        // Fork: hide the favorited galleries; a page of favorites only goes on to the page after it
                        // (before it when prepending), as an empty page ended the list
                        val hide = hideFavoritedPref.value
                        hideFavoritedApplied = hide
                        if (hide) {
                            val forward = params !is LoadParams.Prepend
                            var skipped = 0
                            var shown = result.withoutFavorited()
                            while (shown.galleryInfoList.isEmpty() && skipped < MAX_SKIPPED_FAVORITED_PAGES) {
                                val key = (if (forward) result.next else result.prev) ?: break
                                val nextUrl = mutex.withLock {
                                    with(urlBuilder) {
                                        setIndex(key, isNext = forward)
                                        build()
                                    }
                                }
                                result = EhEngine.getGalleryList(nextUrl)
                                shown = result.withoutFavorited()
                                skipped++
                            }
                            result = shown
                        }
                        result
                    }.foldToLoadResult { result ->
                        LoadResult.Page(result.galleryInfoList, result.prev, result.next)
                    }
                }
            }
        }
    }.flow.cachedIn(viewModelScope)
}
