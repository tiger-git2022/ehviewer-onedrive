package com.hippo.ehviewer.ui.main

import android.content.Context
import android.net.http.NetworkException
import android.os.Build
import androidx.annotation.RequiresExtension
import coil3.network.HttpException
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.isAtLeastSExtension7
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Fork: a short reason why a preview (or its page of links) failed to load, for the preview grid and
 * "Refresh previews": a timeout, a server that cannot be found or reached, or the HTTP status.
 */
fun previewFailureReason(context: Context, t: Throwable): String {
    val cause = generateSequence(t) { it.cause }.take(5)
    cause.forEach { e ->
        val res = when (e) {
            is HttpException -> return httpReason(context, e.response.code)
            is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException, is java.net.SocketTimeoutException -> R.string.error_timeout
            is UnknownHostException -> R.string.preview_error_unknown_host
            is ConnectException, is NoRouteToHostException -> R.string.preview_error_unreachable
            is SSLException -> R.string.preview_error_ssl
            else -> if (isAtLeastSExtension7 && e is NetworkException) cronetReason(e) else null
        }
        if (res != null) return context.getString(res)
    }
    return t.message?.takeIf { it.isNotBlank() }?.take(80) ?: context.getString(R.string.error_unknown)
}

private fun httpReason(context: Context, code: Int) = when (code) {
    403, 404, 410 -> context.getString(R.string.preview_error_link_expired, code)
    else -> context.getString(R.string.preview_error_http, code)
}

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
private fun cronetReason(e: NetworkException) = when (e.errorCode) {
    NetworkException.ERROR_TIMED_OUT, NetworkException.ERROR_CONNECTION_TIMED_OUT -> R.string.error_timeout
    NetworkException.ERROR_HOSTNAME_NOT_RESOLVED -> R.string.preview_error_unknown_host
    NetworkException.ERROR_INTERNET_DISCONNECTED -> R.string.preview_error_offline
    NetworkException.ERROR_CONNECTION_REFUSED, NetworkException.ERROR_ADDRESS_UNREACHABLE -> R.string.preview_error_unreachable
    NetworkException.ERROR_CONNECTION_CLOSED, NetworkException.ERROR_CONNECTION_RESET -> R.string.preview_error_reset
    else -> R.string.error_socket
}
