package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R

/**
 * Fork: a crossed-out heart next to "汉" on the search bar of the gallery lists. On, the lists leave out the galleries
 * in the cloud or local favorites (Settings.hideFavoritedInLists, see HideFavorited.kt).
 */
@Composable
fun HideFavoritedButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Same look as ChineseOnlyButton: on the liquid glass, a lens of glass while checked, see GlassToggleButton
    GlassToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
    ) {
        val color = LocalContentColor.current
        Icon(
            imageVector = Icons.Default.FavoriteBorder,
            contentDescription = stringResource(id = R.string.hide_favorited),
            modifier = Modifier.size(22.dp).drawWithContent {
                drawContent()
                val inset = 2.dp.toPx()
                drawLine(
                    color = color,
                    start = Offset(inset, inset),
                    end = Offset(size.width - inset, size.height - inset),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            },
        )
    }
}
