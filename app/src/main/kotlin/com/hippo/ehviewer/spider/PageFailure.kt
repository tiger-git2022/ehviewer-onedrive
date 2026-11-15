package com.hippo.ehviewer.spider

import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.ui.main.previewFailureReason
import com.hippo.ehviewer.util.LowSpeedException
import com.hippo.ehviewer.util.displayString
import io.ktor.http.Url
import splitties.init.appCtx

/**
 * Fork: why a page failed to download, with the image servers it was tried on, e.g.
 * "超时（试过的服务器：abcdefg.hijklmn:50100、praogxqcch、kitztqqrsr）".
 *
 * Each page is served by an H@H client E-Hentai picks for the exit IP, and a retry with `nl` moves it to one of
 * E-Hentai's own servers. When all of them fail on one VPN node and work on another, this shows it was the servers
 * and not a retry that kept asking the same one.
 */
fun pageFailureMessage(t: Throwable, servers: List<String>): String {
    val text = t.displayString()
    val reason = if (t is LowSpeedException) text else previewFailureReason(appCtx, t)
    val tried = servers.distinct()
    return if (tried.isEmpty()) reason else appCtx.getString(R.string.page_failed_servers, reason, tried.joinToString("、"))
}

/** The host of an image url without ".hath.network", with its port when it is not 443 */
fun imageServerName(url: String): String = runCatching {
    val u = Url(url)
    val host = u.host.removeSuffix(".hath.network")
    if (u.port == u.protocol.defaultPort) host else "$host:${u.port}"
}.getOrDefault(url.take(40))
