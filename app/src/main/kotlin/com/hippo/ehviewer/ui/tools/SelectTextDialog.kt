package com.hippo.ehviewer.ui.tools

import android.util.TypedValue
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ehviewer.core.i18n.R
import kotlin.coroutines.resume

/** Fork: a text in [SelectTextDialog], under a small label when there are several (null = no label). */
class SelectableSection(val label: String?, val text: CharSequence)

/**
 * Fork: shows texts (a comment and its translation, a title) so that part of them can be picked and copied: a long
 * press selects a word, the handles widen it, and the system's menu copies, shares or translates it.
 */
context(_: DialogState)
suspend fun awaitSelectText(sections: List<SelectableSection>) {
    if (sections.isEmpty()) return
    dialog { cont -> SelectTextDialog(sections, onDismiss = { if (cont.isActive) cont.resume(Unit) }) }
}

/** Fork: the dialog of [awaitSelectText], for a place that is already a dialog, such as the gallery info sheet. */
@Composable
fun BoxScope.SelectTextDialog(sections: List<SelectableSection>, onDismiss: () -> Unit) {
    EhAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(text = stringResource(id = R.string.select_text_close))
            }
        },
        title = { Text(text = stringResource(id = R.string.select_text)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(id = R.string.select_text_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sections.forEach { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        section.label?.let {
                            Text(text = it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        SelectableText(section.text)
                    }
                }
            }
        },
    )
}

/** A platform text view: its selection has the handles and the menu of the system, which Compose's does not all have. */
@Composable
private fun SelectableText(text: CharSequence, modifier: Modifier = Modifier) {
    val textColor = LocalContentColor.current.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f).toArgb()
    val style = MaterialTheme.typography.bodyLarge
    AndroidView(
        factory = { context ->
            AppCompatTextView(context).apply {
                gravity = Gravity.START
                setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
                setTextIsSelectable(true)
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) { view ->
        view.setTextColor(textColor)
        view.setLinkTextColor(linkColor)
        view.highlightColor = highlightColor
        if (view.text.toString() != text.toString()) view.text = text
    }
}
