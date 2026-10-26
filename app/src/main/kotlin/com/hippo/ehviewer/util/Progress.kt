package com.hippo.ehviewer.util

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.ui.LocalPageBackdrop
import com.hippo.ehviewer.ui.main.ShapeLoadingIndicator
import com.hippo.ehviewer.ui.rememberLiquidGlass
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.GlassDialog
import com.hippo.ehviewer.ui.tools.LocalGlobalDialogState
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * The "please wait" dialog. Fork: shown through the app-wide dialog state, so it is liquid glass over the pages like
 * the other dialogs, see [ProgressDialogContent].
 */
@Composable
fun ProgressDialog() {
    val state = LocalGlobalDialogState.current
    // Another app-wide dialog already up keeps its place: this one is a window then
    var asWindow by remember { mutableStateOf(false) }
    DisposableEffect(state) {
        val block: @Composable BoxScope.() -> Unit = { ProgressDialogContent() }
        if (state.value == null) state.value = block else asWindow = true
        onDispose { if (state.value === block) state.dismiss() }
    }
    if (asWindow) WindowProgressDialog()
}

@Composable
private fun WindowProgressDialog() = BasicAlertDialog(
    onDismissRequest = { },
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
) {
    Surface(
        modifier = Modifier.width(280.dp),
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
        content = { ProgressDialogRow() },
    )
}

@Composable
private fun ProgressDialogRow() = Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.padding(18.dp),
) {
    ShapeLoadingIndicator()
    Spacer(modifier = Modifier.size(18.dp))
    Text(text = stringResource(id = R.string.please_wait))
}

@Composable
private fun BoxScope.ProgressDialogContent() {
    val glass = rememberLiquidGlass(LocalPageBackdrop.current)
    if (glass != null) {
        GlassDialog(
            glass,
            onDismissRequest = {},
            modifier = Modifier.width(280.dp),
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            content = { ProgressDialogRow() },
        )
    } else {
        WindowProgressDialog()
    }
}

context(state: DialogState)
suspend fun <R> bgWork(work: suspend () -> R) = state.mutex.mutate {
    try {
        suspendCoroutineUninterceptedOrReturn { cont ->
            work.startCoroutineUninterceptedOrReturn(cont).also { r ->
                if (r == COROUTINE_SUSPENDED) state.value = { ProgressDialogContent() }
            }
        }
    } finally {
        state.dismiss()
    }
}
