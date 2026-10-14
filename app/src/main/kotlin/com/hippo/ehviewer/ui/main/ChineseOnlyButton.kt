package com.hippo.ehviewer.ui.main

import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.ehviewer.core.i18n.R

/** Index of Chinese in GalleryInfo.S_LANG_TAGS, the language filter the button sets. */
const val CHINESE_LANGUAGE_FILTER = 1

/**
 * Fork: "汉" next to the search bar of the gallery list. On, searches are limited to Chinese galleries (the language
 * filter of the search options, `language:chinese` added to the query); off, to any language.
 */
@Composable
fun ChineseOnlyButton(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(id = R.string.chinese_only)
    IconToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = IconButtonDefaults.toggleableShapes(),
        colors = IconButtonDefaults.iconToggleButtonColors(
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Text(text = "汉", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
