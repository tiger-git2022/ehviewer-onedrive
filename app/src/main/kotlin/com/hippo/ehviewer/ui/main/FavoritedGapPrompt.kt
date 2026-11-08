package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.ui.screen.FavoritedGapPause
import com.hippo.ehviewer.ui.screen.FavoritedGapProgress

// Fork: the end of a list paused by a run of favorites only, and the skipping of it, see ui/screen/FavoritedGap.kt

@Composable
fun FavoritedGapPrompt(pause: FavoritedGapPause, retry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.favorited_gap_paused, pause.days, pause.pages, pause.reached),
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.favorited_gap_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = {
                pause.resume()
                retry()
            },
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(text = stringResource(R.string.favorited_gap_continue))
        }
    }
}

@Composable
fun FavoritedGapSkipping(progress: FavoritedGapProgress, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.favorited_gap_skipping, progress.reached, progress.pages),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        LinearWavyProgressIndicator()
    }
}
