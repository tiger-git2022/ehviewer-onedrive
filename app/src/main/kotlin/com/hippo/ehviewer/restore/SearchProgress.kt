package com.hippo.ehviewer.restore

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

// Fork: how far a new search has got, shown below the loading indicator of the list: the page of E-Hentai fetched, and
// the searches on 紳士漫畫 and PandaChika (see BackupMerger), each in turn

/** A site a search of the list looks on. */
enum class SearchSite { EHentai, Wnacg, PandaChaika }

/** Where the search on a site is. */
@Immutable
sealed interface SiteStatus {
    /** Not yet asked: the sites before it go first. */
    data object Waiting : SiteStatus

    /** Fetching its [page]th page (from 1) of the search; [skippingFavorited] when the pages before had favorites only. */
    data class Fetching(val page: Int, val skippingFavorited: Boolean = false) : SiteStatus

    /** Asking E-Hentai the Japanese titles of its galleries, to leave out the same works. */
    data object CheckingTitles : SiteStatus

    /** Done, with [found] galleries in the list from it. */
    data class Done(val found: Int) : SiteStatus

    /** Failed: the list shows without it. [blocked] when the site asks for its check first. */
    data class Failed(val blocked: Boolean) : SiteStatus
}

@Immutable
data class SiteProgress(val site: SearchSite, val status: SiteStatus)

/**
 * The progress of one load of a list, in the context of its coroutine: the code fetching the pages tells it where it is
 * with [reportSearchStatus], for the site it is on ([site]).
 */
class SearchProgress(sites: List<SearchSite>) : AbstractCoroutineContextElement(SearchProgress) {
    companion object Key : CoroutineContext.Key<SearchProgress>

    var sites by mutableStateOf(sites.map { SiteProgress(it, SiteStatus.Waiting) })
        private set

    fun statusOf(site: SearchSite) = sites.firstOrNull { it.site == site }?.status

    fun update(site: SearchSite, status: SiteStatus) {
        sites = sites.map { if (it.site == site) it.copy(status = status) else it }
    }
}

/** The site the code below in this coroutine searches on, telling [SearchProgress] where it is. */
private class CurrentSite(val site: SearchSite) : AbstractCoroutineContextElement(CurrentSite) {
    companion object Key : CoroutineContext.Key<CurrentSite>
}

/** Runs [block], searching on [site]: what it reports is for that site. */
suspend fun <T> searchingOn(site: SearchSite, block: suspend () -> T): T = withContext(CurrentSite(site)) { block() }

/** Tells the progress of this load, if shown, where the search on the current site is. */
suspend fun reportSearchStatus(status: SiteStatus) {
    val context = currentCoroutineContext()
    val site = context[CurrentSite]?.site ?: return
    context[SearchProgress]?.update(site, status)
}
