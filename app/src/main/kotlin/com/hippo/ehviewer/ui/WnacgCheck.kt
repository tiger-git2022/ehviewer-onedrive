package com.hippo.ehviewer.ui

import android.webkit.CookieManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ehviewer.core.i18n.R
import com.google.accompanist.web.WebView
import com.google.accompanist.web.rememberWebViewState
import com.hippo.ehviewer.chapters.Wnacg
import com.hippo.ehviewer.ktor.currentUserAgent
import com.hippo.ehviewer.util.setDefaultSettings

/**
 * Fork: 紳士漫畫 in a page of the app, where its check (Cloudflare) is passed by hand; its cookie is then kept, and the
 * thumbnails are fetched with it and the same user agent. [url] is a search of the site, which shows thumbnails too.
 */
@Composable
fun WnacgCheck(url: String, onCancel: () -> Unit, onDone: () -> Unit) = Dialog(
    onDismissRequest = onCancel,
    properties = DialogProperties(usePlatformDefaultWidth = false),
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.chapter_wnacg_check_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
                Button(onClick = {
                    CookieManager.getInstance().flush()
                    onDone()
                }) { Text(stringResource(R.string.chapter_wnacg_check_done)) }
            }
            val state = rememberWebViewState(url = url)
            WebView(
                state = state,
                modifier = Modifier.fillMaxSize(),
                onCreated = {
                    it.setDefaultSettings()
                    it.settings.domStorageEnabled = true
                    it.settings.userAgentString = currentUserAgent()
                    CookieManager.getInstance().setAcceptThirdPartyCookies(it, true)
                },
            )
        }
    }
}

/** The search of [title] on 紳士漫畫, to open in [WnacgCheck]. */
fun wnacgCheckUrl(title: String?): String = if (title.isNullOrBlank()) {
    "${Wnacg.BASE}/"
} else {
    "${Wnacg.BASE}/search/index.php?q=" + java.net.URLEncoder.encode(title, "UTF-8") + "&syn=yes&f=_all&s=create_time_DESC"
}
