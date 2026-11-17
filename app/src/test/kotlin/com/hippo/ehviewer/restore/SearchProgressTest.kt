package com.hippo.ehviewer.restore

import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.client.parser.ParserUtils
import com.hippo.ehviewer.restore.BackupMerger.Load
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class SearchProgressTest {
    @Test
    fun eachPageFetchedIsToldForItsSite() = runBlocking {
        val progress = SearchProgress(listOf(SearchSite.EHentai, SearchSite.Wnacg, SearchSite.PandaChaika))
        val seen = mutableListOf<SiteStatus?>()
        val merger = BackupMerger<Unit>(
            accept = { true },
            sources = listOf { page ->
                seen += progress.statusOf(SearchSite.Wnacg)
                BackupPage(emptyList(), listOf(minutes(1000 - page * 100L)), hasNext = page < 2)
            },
            onFound = {},
            maxPages = 5,
        )
        val page = listOf(eh(10, 1000), eh(11, 850))
        withContext(progress) { searchingOn(SearchSite.Wnacg) { merger.merge(Load.Refresh, null, page, null, "11") } }
        assertEquals(listOf<SiteStatus?>(SiteStatus.Fetching(1), SiteStatus.Fetching(2)), seen)
        // The other sites are not told
        assertEquals(SiteStatus.Waiting, progress.statusOf(SearchSite.PandaChaika))
    }

    @Test
    fun aFailureIsTold() = runBlocking {
        val progress = SearchProgress(listOf(SearchSite.EHentai, SearchSite.PandaChaika))
        val merger = BackupMerger<Unit>(
            accept = { true },
            sources = listOf { error("down") },
            onFound = {},
            maxPages = 5,
        )
        val page = listOf(eh(10, 1000), eh(11, 900))
        val merged = withContext(progress) { searchingOn(SearchSite.PandaChaika) { merger.merge(Load.Refresh, null, page, null, null) } }
        assertEquals(page, merged)
        assertEquals(SiteStatus.Failed(blocked = false), progress.statusOf(SearchSite.PandaChaika))
    }

    @Test
    fun withoutProgressNothingIsTold() = runBlocking {
        // An append: no progress in the context
        searchingOn(SearchSite.Wnacg) { reportSearchStatus(SiteStatus.Fetching(1)) }
    }

    private fun eh(gid: Long, minutes: Long) = BaseGalleryInfo(
        gid = gid,
        token = "t$gid",
        title = "g$gid",
        posted = ParserUtils.formatDate(minutes(minutes)),
    )

    private fun minutes(minutes: Long) = 1_700_000_000_000L + minutes * 60_000
}
