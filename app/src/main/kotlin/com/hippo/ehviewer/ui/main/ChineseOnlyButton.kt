package com.hippo.ehviewer.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryInfo

/** Index of Chinese in GalleryInfo.S_LANG_TAGS, the language filter the button sets. */
const val CHINESE_LANGUAGE_FILTER = 1

/** A Chinese gallery, by its language tag or else its title; the favorites have no language search, see FavoritesViewModel. */
fun GalleryInfo.isChinese(): Boolean {
    if (simpleLanguage == null) generateSLang()
    return simpleLanguage == GalleryInfo.S_LANGS[CHINESE_LANGUAGE_FILTER]
}

/**
 * Fork: "汉" next to the search bar of the gallery list. On, searches are limited to Chinese galleries (the language
 * filter of the search options, `language:chinese` added to the query); off, to any language.
 */
@Composable
fun ChineseOnlyButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    // Off on lists the language filter cannot apply to (what's hot, toplists, image search)
    enabled: Boolean = true,
) {
    val description = stringResource(id = R.string.chinese_only)
    // Fork: on the liquid glass of the search bar, a lens of glass while checked, see GlassToggleButton
    GlassToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Text(text = "汉", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
