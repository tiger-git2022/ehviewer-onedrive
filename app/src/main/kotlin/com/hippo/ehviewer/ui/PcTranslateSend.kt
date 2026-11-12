package com.hippo.ehviewer.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.pctranslate.PcAuthException
import com.hippo.ehviewer.pctranslate.PcClient
import com.hippo.ehviewer.pctranslate.PcFolders
import com.hippo.ehviewer.pctranslate.PcFound
import com.hippo.ehviewer.pctranslate.PcHttpException
import com.hippo.ehviewer.pctranslate.PcServer
import com.hippo.ehviewer.pctranslate.PcState
import com.hippo.ehviewer.pctranslate.PcTranslate
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.EhAlertDialog
import com.hippo.ehviewer.ui.tools.awaitInputText
import com.hippo.ehviewer.ui.tools.awaitSelectItem
import com.hippo.ehviewer.ui.tools.dialog
import com.hippo.ehviewer.util.FileUtils
import java.io.IOException
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.tarsin.snackbar
import moe.tarsin.string

/**
 * Fork: 「发到电脑翻译」 from the download list: finds and pairs the PC the first time, asks which batch folder on the
 * PC to put them in, then queues them for [com.hippo.ehviewer.pctranslate.PcTranslateWork].
 */
context(_: Context, _: DialogState, _: SnackbarHostState, _: CoroutineScope)
suspend fun sendToPc(infos: Collection<DownloadInfo>) {
    val sendable = infos.filter(PcTranslate::sendable)
    if (sendable.isEmpty()) {
        snackbar(string(R.string.pc_translate_none_sendable))
        return
    }
    var server = connectPc() ?: return
    val folders = try {
        PcClient.folders(server)
    } catch (e: PcAuthException) {
        PcTranslate.setServer(null)
        server = connectPc() ?: return
        runCatching { PcClient.folders(server) }.getOrElse {
            snackbar(string(R.string.pc_translate_unreachable, it.message))
            return
        }
    } catch (e: IOException) {
        snackbar(string(R.string.pc_translate_unreachable, e.message))
        return
    }
    PcTranslate.setRoot(folders.root)
    val size = PcTranslate.sizeOf(sendable)
    val today = folders.suggest.ifEmpty { LocalDate.now().toString() }
    val folder = awaitPickFolder(server, folders, today, sendable.size, size)
    PcTranslate.send(sendable, folder, today)
    val skipped = infos.size - sendable.size
    snackbar(
        string(R.string.pc_translate_queued, sendable.size, folder) +
            if (skipped > 0) string(R.string.pc_translate_skipped, skipped) else "",
    )
}

/**
 * The PC paired before, or one found on the network and paired now with the code it shows ([force]: look again even
 * when paired). Null when none was found or picked.
 */
context(_: Context, _: DialogState, _: SnackbarHostState, scope: CoroutineScope)
suspend fun connectPc(force: Boolean = false): PcServer? {
    if (!force) PcTranslate.server?.let { return it }
    // Not waited for: a snackbar is up for seconds
    val searching = scope.launch { snackbar(string(R.string.pc_translate_searching)) }
    val found = runCatching { PcClient.discover() }.getOrDefault(emptyList())
    searching.cancel()
    val pc = when (found.size) {
        0 -> {
            val address = awaitInputText(
                initial = PcTranslate.server?.let { "${it.host}:${it.port}" }.orEmpty(),
                title = string(R.string.pc_translate_not_found),
                hint = string(R.string.pc_translate_address),
            ).trim()
            if (address.isEmpty()) return null
            val colon = address.lastIndexOf(':')
            val port = address.substring(colon + 1).toIntOrNull()?.takeIf { colon > 0 && address.count { it == ':' } == 1 }
            val host = if (port != null) address.substring(0, colon) else address
            PcFound(name = host, host = host, port = port ?: PcClient.DEFAULT_PORT, id = "")
        }
        1 -> found.single()
        else -> found[awaitSelectItem(found.map { "${it.name}（${it.host}）" }, R.string.pc_translate_pick_pc)]
    }
    val known = PcTranslate.server
    if (known != null && known.id.isNotEmpty() && known.id == pc.id) {
        // The same PC at a new address: no need to pair again
        return known.copy(host = pc.host, port = pc.port).also { PcTranslate.setServer(it) }
    }
    var error: String? = null
    while (true) {
        val code = awaitInputText(
            title = string(R.string.pc_translate_pair_title, pc.name),
            hint = error ?: string(R.string.pc_translate_pair_hint),
            isNumber = true,
        )
        try {
            val server = PcClient.pair(pc.host, pc.port, code, PcTranslate.deviceName())
            PcTranslate.setServer(server)
            scope.launch { snackbar(string(R.string.pc_translate_paired_with, server.name)) }
            return server
        } catch (e: PcHttpException) {
            error = string(R.string.pc_translate_pair_failed, e.message)
        } catch (e: IOException) {
            snackbar(string(R.string.pc_translate_unreachable, e.message))
            return null
        }
    }
}

/** The batch folder on the PC to put them in: a new one of [today] first (or today's, when there), then the others. */
context(_: Context, _: DialogState)
private suspend fun awaitPickFolder(server: PcServer, folders: PcFolders, today: String, count: Int, size: Long): String {
    val names = folders.folders.map { it.name }
    val other = string(R.string.pc_translate_other_folder)
    val rows = buildList {
        if (today !in names) add(today to string(R.string.pc_translate_new_folder, today) to string(R.string.pc_translate_new_folder_default))
        folders.folders.forEach { f ->
            val sub = if (f.untranslated > 0) {
                string(R.string.pc_translate_folder_untranslated, f.count, f.untranslated)
            } else {
                string(R.string.pc_translate_folder_count, f.count)
            }
            add(f.name to f.name to sub)
        }
        add("" to other to "")
    }
    val last = PcTranslate.lastFolder(today)
    val initial = rows.indexOfFirst { it.first.first == last }.takeIf { it >= 0 } ?: 0
    val summary = string(R.string.pc_translate_send_summary, server.name, count, FileUtils.humanReadableByteCount(size))
    val picked = dialog<Int> { cont ->
        var selected by remember { mutableIntStateOf(initial) }
        EhAlertDialog(
            onDismissRequest = { cont.cancel() },
            confirmButton = {
                TextButton(onClick = { cont.resume(selected) }, shapes = ButtonDefaults.shapes()) {
                    Text(text = stringResource(id = R.string.pc_translate_send_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { cont.cancel() }, shapes = ButtonDefaults.shapes()) {
                    Text(text = stringResource(id = android.R.string.cancel))
                }
            },
            title = { Text(text = stringResource(id = R.string.pc_translate_send_title)) },
            text = {
                Column {
                    Text(text = summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        rows.forEachIndexed { index, (title, sub) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(ShapeDefaults.Small).clickable { selected = index }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = index == selected, onClick = { selected = index })
                                Column {
                                    Text(text = title.second, style = MaterialTheme.typography.bodyLarge)
                                    if (sub.isNotEmpty()) Text(text = sub, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
        )
    }
    val name = rows[picked].first.first
    if (name.isNotEmpty()) return name
    return awaitInputText(initial = today, title = other, hint = string(R.string.pc_translate_folder_name)).trim().ifEmpty { today }
}

/** On a download card: where it is with the PC translation, with the percent while it moves; nothing when never sent. */
@Composable
fun PcTranslateBadge(gid: Long, modifier: Modifier = Modifier) {
    val items by PcTranslate.items.collectAsState()
    val transfer by PcTranslate.transfer.collectAsState()
    val item = items[gid] ?: return
    val moving = transfer?.takeIf { it.gid == gid }
    val colors = MaterialTheme.colorScheme
    val (text, container, content) = when {
        moving != null -> Triple(
            stringResource(id = if (moving.sending) R.string.pc_translate_badge_sending else R.string.pc_translate_badge_taking, moving.percent.coerceAtLeast(0)),
            colors.primaryContainer,
            colors.onPrimaryContainer,
        )
        item.state == PcState.QUEUED && item.message != null ->
            Triple(stringResource(id = R.string.pc_translate_badge_retrying), colors.errorContainer, colors.onErrorContainer)
        item.state == PcState.QUEUED -> Triple(stringResource(id = R.string.pc_translate_badge_queued), colors.secondaryContainer, colors.onSecondaryContainer)
        item.state == PcState.ON_PC -> Triple(stringResource(id = R.string.pc_translate_badge_on_pc), colors.primaryContainer, colors.onPrimaryContainer)
        item.state == PcState.DONE -> Triple(stringResource(id = R.string.pc_translate_badge_done), colors.tertiaryContainer, colors.onTertiaryContainer)
        item.state == PcState.FAILED -> Triple(stringResource(id = R.string.pc_translate_badge_failed), colors.errorContainer, colors.onErrorContainer)
        else -> return
    }
    Text(
        text = text,
        modifier = modifier.clip(ShapeDefaults.Small).background(container).padding(vertical = 1.dp, horizontal = 6.dp),
        color = content,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
    )
}
