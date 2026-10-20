package com.hippo.ehviewer.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.client.EhUtils

/**
 * Fork: the short name on the category tag of the list items and the downloads, two characters in Chinese (同人, 漫画, CG…),
 * so the tag leaves room for the language, the pages and the posted time.
 */
@Composable
fun categoryShortName(category: Int): String = when (category) {
    EhUtils.DOUJINSHI -> stringResource(R.string.category_short_doujinshi)
    EhUtils.MANGA -> stringResource(R.string.category_short_manga)
    EhUtils.ARTIST_CG -> stringResource(R.string.category_short_artist_cg)
    EhUtils.GAME_CG -> stringResource(R.string.category_short_game_cg)
    EhUtils.WESTERN -> stringResource(R.string.category_short_western)
    EhUtils.NON_H -> stringResource(R.string.category_short_non_h)
    EhUtils.IMAGE_SET -> stringResource(R.string.category_short_image_set)
    EhUtils.COSPLAY -> stringResource(R.string.category_short_cosplay)
    EhUtils.ASIAN_PORN -> stringResource(R.string.category_short_asian_porn)
    EhUtils.MISC -> stringResource(R.string.category_short_misc)
    else -> EhUtils.getCategory(category).uppercase()
}
