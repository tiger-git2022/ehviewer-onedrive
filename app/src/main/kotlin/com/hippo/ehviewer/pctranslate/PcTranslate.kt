package com.hippo.ehviewer.pctranslate

import android.os.ParcelFileDescriptor
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.find
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.files.moveTo
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.read
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.download.ArchivePages
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.archiveFile
import com.hippo.ehviewer.download.countZipPages
import com.hippo.ehviewer.download.downloadDir
import com.hippo.ehviewer.spider.COMIC_INFO_FILE
import com.hippo.ehviewer.sync.OneDriveSync
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.asInputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Path
import splitties.init.appCtx

/** The PC paired with: 「平板收发」 of EH 上传助手. [id] finds it again when its address changes. */
@Serializable
data class PcServer(val host: String, val port: Int, val token: String, val name: String, val id: String = "")

/** The download moving now: [sending] to the PC or taken back from it, [done] of [total] bytes (-1 when unknown). */
data class PcTransfer(val gid: Long, val sending: Boolean, val done: Long, val total: Long) {
    /** 0 to 100, -1 when the size is unknown. */
    val percent: Int get() = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
}

/** A download sent to the PC to be translated. */
@Serializable
data class PcItem(
    val gid: Long,
    val title: String,
    /** The batch folder on the PC. */
    val folder: String,
    val state: PcState,
    val time: Long,
    /** What the PC said last, or why it failed. */
    val message: String? = null,
    val pages: Int = 0,
    val translated: Int = 0,
    /** The name of the original archive kept in the download folder, once replaced. */
    val backup: String? = null,
    /** All sent: the PC has it. */
    val sent: Boolean = false,
    /** The folder of the work in the batch on the PC ("<gid>-<title>"). */
    val dir: String? = null,
)

@Serializable
enum class PcState {
    /** To be sent. */
    QUEUED,

    /** On the PC: being extracted, waiting for the translation, packed. */
    ON_PC,

    /** Translated archive taken back, the download replaced. */
    DONE,

    /** The original put back. */
    RESTORED,

    /** Could not be sent or taken back: see [PcItem.message]. */
    FAILED,
}

@Serializable
private data class PcData(
    val server: PcServer? = null,
    val lastFolder: String? = null,
    val lastFolderDay: String? = null,
    val keepOriginal: Boolean = true,
    val items: Map<Long, PcItem> = emptyMap(),
    /** The comic root folder on the PC, as EH 上传助手 said it last. */
    val root: String? = null,
    /** The template of 「复制提示词」, null for [PcPrompt.DEFAULT_TEMPLATE]. */
    val promptTemplate: String? = null,
)

/**
 * Fork: 「发到电脑翻译」. Downloads are sent over the local network to EH 上传助手 on the PC (see [PcClient]), which
 * extracts them for manga-translator-ui; once translated and packed there, the archive is taken back here and replaces
 * the one of the download, the original kept beside it as "<gid>.orig.cbz" to put back. The list lives in
 * files/pc_translate.json, not in the database, whose schema stays the one of EhViewer; [PcTranslateWork] sends and
 * takes back in the background.
 */
object PcTranslate {
    private const val TAG = "PcTranslate"
    private val file get() = File(appCtx.filesDir, "pc_translate.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private val data = MutableStateFlow(load())

    private val _items = MutableStateFlow(data.value.items)
    private val _transfer = MutableStateFlow<PcTransfer?>(null)

    /** The downloads sent, by gid. */
    val items: StateFlow<Map<Long, PcItem>> = _items.asStateFlow()

    /** The one moving now, with the bytes sent or taken back so far. */
    val transfer: StateFlow<PcTransfer?> = _transfer.asStateFlow()

    /** Something to do now: wakes up the work waiting for its next round. */
    val kicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val server: PcServer? get() = data.value.server
    val keepOriginal: Boolean get() = data.value.keepOriginal
    val root: String? get() = data.value.root
    val promptTemplate: String get() = data.value.promptTemplate ?: PcPrompt.DEFAULT_TEMPLATE

    private fun load(): PcData = runCatching { json.decodeFromString<PcData>(file.readText()) }
        .onFailure { if (file.exists()) logcat(TAG, it) }
        .getOrDefault(PcData())

    private suspend fun edit(block: (PcData) -> PcData) = mutex.withLock {
        val new = block(data.value)
        data.value = new
        _items.value = new.items
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(PcData.serializer(), new))
            tmp.renameTo(file)
        }.onFailure { logcat(TAG, it) }
    }

    suspend fun setServer(server: PcServer?) = edit { it.copy(server = server) }

    suspend fun setKeepOriginal(keep: Boolean) = edit { it.copy(keepOriginal = keep) }

    suspend fun setRoot(root: String) {
        if (root.isNotBlank() && root != data.value.root) edit { it.copy(root = root) }
    }

    /** Null or the default puts back [PcPrompt.DEFAULT_TEMPLATE]. */
    suspend fun setPromptTemplate(template: String?) =
        edit { it.copy(promptTemplate = template?.takeIf { t -> t.isNotBlank() && t != PcPrompt.DEFAULT_TEMPLATE }) }

    /** The prompt of [item], with its paths on the PC. */
    fun prompt(item: PcItem, root: String): String {
        val dir = item.dir ?: PcPrompt.pcDir(item.gid, DownloadManager.getDownloadInfo(item.gid)?.dirname)
        return PcPrompt.fill(promptTemplate, root, item, dir)
    }

    /** The folder picked last, when picked today: sent again the same day, they go together. */
    fun lastFolder(today: String): String? = data.value.takeIf { it.lastFolderDay == today }?.lastFolder

    fun deviceName(): String = OneDriveSync.deviceName

    /** The bytes of the archives of [infos], to show before sending. */
    suspend fun sizeOf(infos: Collection<DownloadInfo>): Long = withIOContext { infos.sumOf { it.archiveFile?.let(::sizeOf)?.coerceAtLeast(0) ?: 0L } }

    /**
     * The size of [path]: from its metadata, else from the file opened. A download folder picked with the system picker
     * (content://) has no size in its metadata: taken as 0, the upload was cut off before reaching the PC, and the
     * download stayed 「待发到电脑」 for ever.
     */
    private fun sizeOf(path: Path): Long = path.metadataOrNull()?.size?.takeIf { it >= 0 }
        ?: runCatching { path.openFileDescriptor("r").use { it.statSize } }.getOrDefault(-1L).takeIf { it >= 0 }
        ?: path.read {
            asInputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                var n = 0L
                while (true) {
                    val r = input.read(buffer)
                    if (r < 0) break
                    n += r
                }
                n
            }
        }

    /** Sets [_transfer], only when the percent moved: not a new state for each piece of 256 KB. */
    private fun progress(gid: Long, sending: Boolean, done: Long, total: Long) {
        val old = _transfer.value
        val new = PcTransfer(gid, sending, done, total)
        if (old == null || old.gid != gid || old.sending != sending || old.percent != new.percent ||
            (new.percent < 0 && done - old.done >= 1L shl 20) || done == total
        ) {
            _transfer.value = new
        }
    }

    /** Downloads that can be sent: finished, kept as an archive. */
    fun sendable(info: DownloadInfo) = info.state == DownloadInfo.STATE_FINISH && info.archiveFile != null

    /** Puts [infos] in the list to send to [folder], and starts sending. */
    suspend fun send(infos: Collection<DownloadInfo>, folder: String, today: String) {
        val now = System.currentTimeMillis()
        edit { d ->
            d.copy(
                lastFolder = folder,
                lastFolderDay = today,
                items = d.items + infos.associate {
                    val dir = PcPrompt.pcDir(it.gid, it.dirname)
                    it.gid to PcItem(gid = it.gid, title = EhUtils.getSuitableTitle(it), folder = folder, state = PcState.QUEUED, time = now, dir = dir)
                },
            )
        }
        PcTranslateWork.start(appCtx)
    }

    /** Takes [gid] off the list, telling the PC when it has it. The download stays as it is now. */
    suspend fun remove(gid: Long) {
        val item = items.value[gid] ?: return
        val server = server
        if (item.state == PcState.ON_PC && server != null) {
            runCatching { PcClient.cancel(server, gid) }.onFailure { logcat(TAG, it) }
        }
        edit { d -> d.copy(items = d.items - gid) }
    }

    /** Tries [gid] again after it failed: sends it again, or takes it back when the PC had it. */
    suspend fun retry(gid: Long) {
        update(gid) { it.copy(state = if (it.sent) PcState.ON_PC else PcState.QUEUED, message = null) }
        PcTranslateWork.start(appCtx)
    }

    private suspend fun update(gid: Long, block: (PcItem) -> PcItem) = edit { d ->
        val item = d.items[gid] ?: return@edit d
        d.copy(items = d.items + (gid to block(item)))
    }

    /** Anything left to do in the background: to send, or on the PC to take back. */
    fun hasWork() = items.value.values.any { it.state == PcState.QUEUED || it.state == PcState.ON_PC }

    // ---- the work ----

    /**
     * One round: sends what is queued, then asks the PC about those it has and takes back the packed ones. True when
     * the PC answered (the work waits longer before the next round when it did not).
     */
    suspend fun runOnce(onStatus: suspend (String?) -> Unit): Boolean {
        var server = server ?: return false
        var reached = false
        // One at a time, the oldest first; those queued meanwhile are sent in this round too
        val tried = HashSet<Long>()
        while (true) {
            val item = items.value.values.filter { it.state == PcState.QUEUED && it.gid !in tried }.minByOrNull { it.time } ?: break
            tried += item.gid
            val info = DownloadManager.getDownloadInfo(item.gid)
            if (info == null || !sendable(info)) {
                update(item.gid) { it.copy(state = PcState.FAILED, message = "下载不在了，或还没下载完") }
                continue
            }
            onStatus(item.title)
            try {
                sendOne(server, info, item)
                reached = true
                update(item.gid) { it.copy(state = PcState.ON_PC, message = "已发到电脑", sent = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PcAuthException) {
                throw e
            } catch (e: PcHttpException) {
                reached = true
                update(item.gid) { it.copy(state = PcState.FAILED, message = "电脑没收下：${e.message}") }
            } catch (e: Exception) {
                logcat(TAG, e)
                val reason = e.message ?: e.javaClass.simpleName
                if (e.isNetwork()) {
                    // Kept to send: the next round tries again, and says why it waits
                    update(item.gid) { it.copy(message = "连不上电脑（$reason），稍后自动重试") }
                    server = relocate(server) ?: return reached
                } else {
                    // Not the network: trying again would fail the same, so it shows and waits for 「重试」
                    update(item.gid) { it.copy(state = PcState.FAILED, message = "发送失败：$reason") }
                }
            } finally {
                _transfer.value = null
            }
        }
        val onPc = items.value.values.filter { it.state == PcState.ON_PC }
        if (onPc.isEmpty()) return true
        val remote = try {
            PcClient.status(server, onPc.map { it.gid })
        } catch (e: PcAuthException) {
            throw e
        } catch (e: IOException) {
            logcat(TAG, e)
            val moved = relocate(server) ?: return reached
            server = moved
            runCatching { PcClient.status(server, onPc.map { it.gid }) }.getOrElse { return reached }
        }
        for (r in remote) {
            val item = items.value[r.gid] ?: continue
            when (r.state) {
                "gone" -> update(r.gid) { it.copy(state = PcState.FAILED, message = "电脑上没有这一本了（被移出列表？）", sent = false) }
                "ready", "taken" -> {
                    onStatus(item.title)
                    try {
                        takeBack(server, item)
                    } catch (e: PcAuthException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(TAG, e)
                        update(r.gid) { it.copy(message = "取回失败，稍后再试：${e.message}") }
                    } finally {
                        _transfer.value = null
                    }
                }
                else -> update(r.gid) {
                    it.copy(
                        message = r.message,
                        pages = r.pages ?: it.pages,
                        translated = r.translatedNow ?: r.translated ?: it.translated,
                    )
                }
            }
        }
        return true
    }

    /** The PC did not answer at its address: maybe it got another one. Looks for it by its id on the network. */
    private suspend fun relocate(server: PcServer): PcServer? {
        val found = runCatching { PcClient.discover(server.port) }.getOrDefault(emptyList())
            .firstOrNull { server.id.isNotEmpty() && it.id == server.id && it.host != server.host } ?: return null
        val moved = server.copy(host = found.host, port = found.port)
        setServer(moved)
        return moved
    }

    private suspend fun sendOne(server: PcServer, info: DownloadInfo, item: PcItem) {
        val dir = checkNotNull(info.downloadDir) { "No download folder" }
        val archive = checkNotNull(info.archiveFile) { "No archive" }
        val dirname = info.dirname ?: "${info.gid}"
        val files = listOfNotNull(archive to "${info.gid}.cbz", dir.find(COMIC_INFO_FILE)?.let { it to COMIC_INFO_FILE })
        val sizes = withIOContext { files.map { (path, _) -> sizeOf(path) } }
        val total = sizes.sum()
        var before = 0L
        _transfer.value = PcTransfer(info.gid, true, 0, total)
        files.forEachIndexed { i, (path, name) ->
            PcClient.upload(server, info.gid, name, item.folder, dirname, item.title, path, sizes[i]) { done ->
                progress(info.gid, true, before + done, total)
            }
            before += sizes[i]
        }
        PcClient.received(server, info.gid, item.title)
    }

    /** Takes the packed archive back: checked to open, then in place of the download's, the original kept. */
    private suspend fun takeBack(server: PcServer, item: PcItem) {
        val info = DownloadManager.getDownloadInfo(item.gid)
        if (info == null) {
            update(item.gid) { it.copy(state = PcState.FAILED, message = "下载已经删了，没有取回") }
            return
        }
        val dir = checkNotNull(info.downloadDir) { "No download folder" }
        val archive = info.archiveFile
        val ext = archive?.name?.substringAfterLast('.', "cbz") ?: "cbz"
        val name = archive?.name ?: "${info.gid}.cbz"
        val temp = dir / "${info.gid}.pc.part"
        try {
            if (temp.exists()) temp.delete()
            _transfer.value = PcTransfer(item.gid, false, 0, -1)
            PcClient.download(server, item.gid, temp) { done, total -> progress(item.gid, false, done, total) }
            val pages = runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(temp.openFileDescriptor("r")).use { countZipPages(it.channel) }
            }.getOrNull()
            if (pages == null || pages <= 0) throw IOException("取回的文件打不开，或里面没有图片")
            var backup = item.backup
            if (archive != null) {
                val orig = "${info.gid}.orig.$ext"
                if (keepOriginal && (backup == null || dir.find(backup) == null) && dir.find(orig) == null) {
                    archive moveTo (dir / orig)
                    backup = orig
                } else {
                    archive.delete()
                }
            }
            temp moveTo (dir / name)
            update(item.gid) {
                it.copy(state = PcState.DONE, message = null, pages = pages, backup = backup ?: it.backup.takeIf { b -> b != null && dir.find(b) != null })
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
        ArchivePages.refresh(info)
        runCatching { PcClient.taken(server, item.gid) }.onFailure { logcat(TAG, it) }
    }

    /** Puts the original archive back in place of the translated one. */
    suspend fun restore(gid: Long): Boolean {
        val item = items.value[gid] ?: return false
        val info = DownloadManager.getDownloadInfo(gid) ?: return false
        val dir = info.downloadDir ?: return false
        val backup = item.backup?.let { dir.find(it) } ?: return false
        val ext = backup.name.substringAfterLast('.', "cbz")
        info.archiveFile?.delete()
        backup moveTo (dir / "$gid.$ext")
        update(gid) { it.copy(state = PcState.RESTORED, backup = null) }
        ArchivePages.refresh(info)
        return true
    }

    /** The network failed, not the file or the PC: worth trying again by itself. */
    private fun Exception.isNetwork() = this is SocketException || this is InterruptedIOException || this is UnknownHostException

    /** The download still has its original archive beside the translated one. */
    fun hasBackup(info: DownloadInfo, item: PcItem) = item.backup != null && info.downloadDir?.find(item.backup) != null
}
