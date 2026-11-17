package com.hippo.ehviewer.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.restore.SearchProgress
import com.hippo.ehviewer.restore.SearchSite
import com.hippo.ehviewer.restore.SiteProgress
import com.hippo.ehviewer.restore.SiteStatus

/**
 * Fork: below the loading indicator of a new search, how far it has got on each site it looks on: the page of
 * E-Hentai, then 紳士漫畫 and PandaChika in turn, see SearchProgress. Nothing when it only looks on E-Hentai, unless
 * pages of favorites only are skipped.
 */
@Composable
fun SearchProgressList(progress: SearchProgress, modifier: Modifier = Modifier) {
    val sites = progress.sites
    val skipping = sites.any { (it.status as? SiteStatus.Fetching)?.skippingFavorited == true }
    if (sites.size < 2 && !skipping) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sites.forEach { SiteRow(it) }
    }
}

@Composable
private fun SiteRow(progress: SiteProgress) {
    val status = progress.status
    val colors = MaterialTheme.colorScheme
    val dim = status is SiteStatus.Waiting
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (status) {
                is SiteStatus.Done -> Icon(Icons.Default.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                is SiteStatus.Failed -> Icon(Icons.Default.Close, contentDescription = null, tint = colors.error, modifier = Modifier.size(18.dp))
                is SiteStatus.Fetching, SiteStatus.CheckingTitles -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                SiteStatus.Waiting -> Text("·", color = colors.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
        Text(
            text = when (progress.site) {
                SearchSite.EHentai -> "E-Hentai"
                SearchSite.Wnacg -> stringResource(R.string.search_site_wnacg)
                SearchSite.PandaChaika -> "PandaChaika"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (dim) colors.onSurfaceVariant.copy(alpha = 0.6f) else colors.onSurface,
            maxLines = 1,
            modifier = Modifier.width(SiteNameWidth).padding(start = 10.dp),
        )
        AnimatedContent(
            targetState = status,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.widthIn(min = StatusMinWidth),
            label = "searchSiteStatus",
        ) { shown ->
            Text(
                text = when (shown) {
                    SiteStatus.Waiting -> stringResource(R.string.search_site_waiting)
                    is SiteStatus.Fetching -> if (shown.skippingFavorited) {
                        stringResource(R.string.search_site_page_skipping, shown.page)
                    } else {
                        stringResource(R.string.search_site_page, shown.page)
                    }
                    SiteStatus.CheckingTitles -> stringResource(R.string.search_site_checking_titles)
                    is SiteStatus.Done -> stringResource(R.string.search_site_found, shown.found)
                    is SiteStatus.Failed -> stringResource(if (shown.blocked) R.string.search_site_blocked else R.string.search_site_failed)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when (shown) {
                    is SiteStatus.Done -> colors.primary
                    is SiteStatus.Failed -> colors.error
                    SiteStatus.Waiting -> colors.onSurfaceVariant.copy(alpha = 0.6f)
                    else -> colors.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}

private val SiteNameWidth = 120.dp
private val StatusMinWidth = 120.dp
