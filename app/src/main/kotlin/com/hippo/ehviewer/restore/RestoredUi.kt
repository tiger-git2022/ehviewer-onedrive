package com.hippo.ehviewer.restore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.CrystalCard

// Fork: how a gallery restored from PandaChika shows, see RestoredGalleries

/** The badge on the cover of [gid] when restored, or found by a search on PandaChika. */
@Composable
fun RestoredBadge(gid: Long, modifier: Modifier = Modifier) {
    val badged by RestoredGalleries.badged.collectAsState()
    if (gid in badged) {
        Text(
            text = stringResource(R.string.pandachaika_badge),
            modifier = modifier
                .padding(4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.tertiary)
                .padding(horizontal = 5.dp, vertical = 1.dp),
            color = MaterialTheme.colorScheme.onTertiary,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            maxLines = 1,
        )
    }
}

/** In the details of a restored gallery, in place of the previews: where they come from. */
fun LazyGridScope.restoredNote() = item(
    key = "pandachaika",
    span = { GridItemSpan(maxLineSpan) },
    contentType = "pandachaika",
) {
    CrystalCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.pandachaika_restored_note),
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
