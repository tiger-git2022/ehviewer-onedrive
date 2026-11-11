package com.hippo.ehviewer.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.pctranslate.PcClient
import com.hippo.ehviewer.pctranslate.PcItem
import com.hippo.ehviewer.pctranslate.PcPrompt
import com.hippo.ehviewer.pctranslate.PcTranslate
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.EhAlertDialog
import com.hippo.ehviewer.ui.tools.dialog
import com.hippo.ehviewer.util.copyTextToClipboard
import kotlin.coroutines.resume
import moe.tarsin.snackbar
import moe.tarsin.string

/**
 * Fork: 「复制提示词」 of a work sent to the PC: [PcTranslate.promptTemplate] with the paths of the work on the PC. The
 * root folder of the PC is the one it said when sending; asked again when not known yet.
 */
context(_: Context, _: SnackbarHostState)
suspend fun copyPcPrompt(item: PcItem) {
    var root = PcTranslate.root
    if (root == null) {
        root = PcTranslate.server?.let { server ->
            runCatching { PcClient.folders(server).root }.onFailure { logcat("PcPrompt", it) }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        root?.let { PcTranslate.setRoot(it) }
    }
    copyTextToClipboard(PcTranslate.prompt(item, root ?: PcPrompt.FALLBACK_ROOT), false)
    snackbar(if (root != null) string(R.string.pc_translate_prompt_copied) else string(R.string.pc_translate_prompt_copied_guess, PcPrompt.FALLBACK_ROOT))
}

/** Edits the template of 「复制提示词」: 「恢复默认」 puts the default back in the field, saved on confirm. */
context(_: Context, _: DialogState, _: SnackbarHostState)
suspend fun editPcPromptTemplate() {
    val legend = PcPrompt.placeholders.joinToString("\n") { (k, v) -> "$k　$v" }
    val text = dialog<String> { cont ->
        val state = rememberTextFieldState(PcTranslate.promptTemplate)
        EhAlertDialog(
            onDismissRequest = { cont.cancel() },
            confirmButton = {
                TextButton(onClick = { cont.resume(state.text.toString()) }, shapes = ButtonDefaults.shapes()) {
                    Text(text = stringResource(id = R.string.pc_translate_prompt_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { state.setTextAndPlaceCursorAtEnd(PcPrompt.DEFAULT_TEMPLATE) }, shapes = ButtonDefaults.shapes()) {
                    Text(text = stringResource(id = R.string.pc_translate_prompt_reset))
                }
                TextButton(onClick = { cont.cancel() }, shapes = ButtonDefaults.shapes()) {
                    Text(text = stringResource(id = android.R.string.cancel))
                }
            },
            title = { Text(text = stringResource(id = R.string.pc_translate_prompt_template)) },
            text = {
                Column {
                    OutlinedTextField(
                        state = state,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(id = R.string.pc_translate_prompt_placeholders) + "\n" + legend,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
        )
    }
    PcTranslate.setPromptTemplate(text)
    snackbar(string(R.string.pc_translate_prompt_saved))
}
