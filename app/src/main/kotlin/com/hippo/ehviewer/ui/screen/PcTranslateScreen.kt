package com.hippo.ehviewer.ui.screen

import android.content.Context
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.pctranslate.PcItem
import com.hippo.ehviewer.pctranslate.PcState
import com.hippo.ehviewer.pctranslate.PcTranslate
import com.hippo.ehviewer.pctranslate.PcTranslateWork
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.connectPc
import com.hippo.ehviewer.ui.copyPcPrompt
import com.hippo.ehviewer.ui.editPcPromptTemplate
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.CoroutineScope
import moe.tarsin.snackbar
import moe.tarsin.string
import splitties.init.appCtx

/** Fork: 「电脑翻译」, the downloads sent to the PC to translate (see [PcTranslate]), from the menu of the download list. */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.PcTranslateScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val items by PcTranslate.items.collectAsState()
    val transfer by PcTranslate.transfer.collectAsState()
    var server by remember { mutableStateOf(PcTranslate.server) }
    var keepOriginal by remember { mutableStateOf(PcTranslate.keepOriginal) }
    val sections = remember(items) {
        listOf(
            R.string.pc_translate_section_queued to PcState.QUEUED,
            R.string.pc_translate_section_on_pc to PcState.ON_PC,
            R.string.pc_translate_section_failed to PcState.FAILED,
            R.string.pc_translate_section_done to PcState.DONE,
        ).map { (title, state) -> title to items.values.filter { it.state == state }.sortedByDescending { it.time } }
            .filter { it.second.isNotEmpty() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.pc_translate)) },
                navigationIcon = { NavigationIcon() },
                actions = {
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = true }) {
                        Icon(imageVector = Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(id = R.string.pc_translate_find_again)) },
                            onClick = {
                                expanded = false
                                launch {
                                    connectPc(force = true)?.let { server = it }
                                    PcTranslateWork.start(appCtx)
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(id = R.string.pc_translate_prompt_template)) },
                            onClick = {
                                expanded = false
                                launch { editPcPromptTemplate() }
                            },
                        )
                        if (server != null) {
                            DropdownMenuItem(
                                text = { Text(text = stringResource(id = R.string.pc_translate_unpair)) },
                                onClick = {
                                    expanded = false
                                    launch {
                                        PcTranslate.setServer(null)
                                        server = null
                                    }
                                },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = paddingValues,
        ) {
            item(key = "pc") {
                val pc = server
                ListItem(
                    headlineContent = { Text(text = stringResource(id = R.string.pc_translate_pc)) },
                    supportingContent = {
                        Text(text = pc?.let { "${it.name} · ${it.host}:${it.port}" } ?: stringResource(id = R.string.pc_translate_no_pc))
                    },
                    modifier = Modifier.clickable {
                        launch {
                            connectPc(force = pc == null)?.let { server = it }
                            PcTranslateWork.start(appCtx)
                        }
                    },
                )
            }
            item(key = "keep") {
                ListItem(
                    headlineContent = { Text(text = stringResource(id = R.string.pc_translate_keep_original)) },
                    supportingContent = { Text(text = stringResource(id = R.string.pc_translate_keep_original_summary)) },
                    trailingContent = {
                        Switch(checked = keepOriginal, onCheckedChange = {
                            keepOriginal = it
                            launch { PcTranslate.setKeepOriginal(it) }
                        })
                    },
                )
            }
            if (sections.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(id = R.string.pc_translate_empty),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            sections.forEach { (title, list) ->
                item(key = "h$title") {
                    Text(
                        text = stringResource(id = title, list.size),
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                items(list, key = { it.gid }) { item ->
                    val moving = transfer?.takeIf { it.first == item.gid }?.second
                    PcItemRow(item = item, moving = moving)
                }
            }
        }
    }
}

@Composable
context(_: Context, _: SnackbarHostState, _: DialogState, _: CoroutineScope)
private fun PcItemRow(item: PcItem, moving: Pair<Long, Long>?) {
    val info = remember(item.gid) { DownloadManager.getDownloadInfo(item.gid) }
    Column {
        ListItem(
            headlineContent = { Text(text = item.title, maxLines = 2) },
            supportingContent = {
                val parts = buildList {
                    add("→ ${item.folder}")
                    if (item.pages > 0 && item.state == PcState.ON_PC) add(stringResource(id = R.string.pc_translate_translated_of, item.translated, item.pages))
                    if (item.state == PcState.DONE) add(stringResource(id = R.string.pc_translate_pages, item.pages))
                    item.message?.let { add(it) }
                    if (item.state == PcState.DONE && info != null && PcTranslate.hasBackup(info, item)) add(stringResource(id = R.string.pc_translate_has_backup))
                }
                Text(text = parts.joinToString(" · "))
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (item.state) {
                        PcState.FAILED -> TextButton(onClick = { launch { PcTranslate.retry(item.gid) } }, shapes = ButtonDefaults.shapes()) {
                            Text(text = stringResource(id = R.string.pc_translate_retry))
                        }
                        PcState.DONE -> if (info != null && PcTranslate.hasBackup(info, item)) {
                            TextButton(
                                onClick = {
                                    launch {
                                        awaitConfirmationOrCancel(confirmText = R.string.pc_translate_restore) {
                                            Text(text = stringResource(id = R.string.pc_translate_restore_confirm))
                                        }
                                        if (PcTranslate.restore(item.gid)) snackbar(string(R.string.pc_translate_restored))
                                    }
                                },
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(text = stringResource(id = R.string.pc_translate_restore))
                            }
                        }
                        else -> Unit
                    }
                    IconButton(onClick = { launch { copyPcPrompt(item) } }) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = stringResource(id = R.string.pc_translate_prompt_copy))
                    }
                    TextButton(onClick = { launch { PcTranslate.remove(item.gid) } }, shapes = ButtonDefaults.shapes()) {
                        Text(text = stringResource(id = if (item.state == PcState.QUEUED || item.state == PcState.ON_PC) R.string.pc_translate_cancel else R.string.pc_translate_remove))
                    }
                }
            },
        )
        if (moving != null) {
            val (done, total) = moving
            LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
}
