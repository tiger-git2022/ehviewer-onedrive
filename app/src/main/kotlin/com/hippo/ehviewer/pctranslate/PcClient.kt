package com.hippo.ehviewer.pctranslate

import android.os.SystemClock
import com.ehviewer.core.files.read
import com.ehviewer.core.files.write
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.asInputStream
import kotlinx.io.asOutputStream
import okio.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

/** The PC answered, but refused: [code] is its HTTP status, [message] its reason (in Chinese, from EH 上传助手). */
open class PcHttpException(val code: Int, message: String) : IOException(message)

/** The pairing was dropped on the PC, or never made: pair again. */
class PcAuthException(message: String) : PcHttpException(401, message)

@Serializable
data class PcFound(val name: String, val host: String, val port: Int, val id: String)

@Serializable
data class PcFolder(val name: String, val count: Int = 0, val untranslated: Int = 0)

@Serializable
data class PcFolders(val folders: List<PcFolder> = emptyList(), val suggest: String = "", val root: String = "")

/** A work as the PC sees it: [state] is one of tablet_bridge.py (waiting, ready, …, or gone when it has none). */
@Serializable
data class PcRemoteItem(
    val gid: Long,
    val state: String? = null,
    val message: String? = null,
    val pages: Int? = null,
    val translated: Int? = null,
    @kotlinx.serialization.SerialName("translated_now") val translatedNow: Int? = null,
)

/**
 * Fork: talks to 「平板收发」 of EH 上传助手 (tiger-git2022/EHUploader, tablet_bridge.py) on the PC, over the local
 * network: found by a UDP broadcast on its port, paired once with the code it shows, then plain HTTP with the token.
 * Its own OkHttp client: no E-Hentai cookie or user agent goes to the PC.
 */
object PcClient {
    const val DEFAULT_PORT = 38765
    private const val DISCOVER = "EHV-TABLET-DISCOVER"
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val zipType = "application/zip".toMediaType()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Serializable
    private data class Discovered(val app: String = "", val name: String = "", val port: Int = DEFAULT_PORT, val id: String = "")

    @Serializable
    private data class Paired(val token: String, val name: String = "", val id: String = "")

    @Serializable
    private data class Items(val items: List<PcRemoteItem> = emptyList())

    @Serializable
    private data class Error(val error: String = "")

    /** The PCs answering a broadcast on the local network within [timeoutMillis]. */
    suspend fun discover(port: Int = DEFAULT_PORT, timeoutMillis: Int = 2500): List<PcFound> = withContext(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 400
            val data = DISCOVER.toByteArray()
            val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
            repeat(2) {
                targets.forEach { runCatching { socket.send(DatagramPacket(data, data.size, it, port)) } }
            }
            val found = LinkedHashMap<String, PcFound>()
            val end = SystemClock.elapsedRealtime() + timeoutMillis
            val buffer = ByteArray(1024)
            while (SystemClock.elapsedRealtime() < end) {
                ensureActive()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, 0, packet.length)
                val d = runCatching { json.decodeFromString<Discovered>(text) }.getOrNull() ?: continue
                if (d.app != "ehuploader") continue
                val host = packet.address.hostAddress ?: continue
                found.getOrPut(d.id.ifEmpty { host }) { PcFound(d.name.ifEmpty { host }, host, d.port, d.id) }
            }
            found.values.toList()
        }
    }

    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address }
            .mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())

    private fun base(host: String, port: Int) = "http://${if (':' in host) "[$host]" else host}:$port"

    private fun url(server: PcServer, path: String) = base(server.host, server.port) + path

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun <T> call(request: Request, block: (Response) -> T): T = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val reason = runCatching { json.decodeFromString<Error>(response.body.string()).error }.getOrNull()
                    ?.ifBlank { null } ?: "HTTP ${response.code}"
                if (response.code == 401) throw PcAuthException(reason)
                throw PcHttpException(response.code, reason)
            }
            block(response)
        }
    }

    private fun Request.Builder.auth(server: PcServer) = header("Authorization", "Bearer ${server.token}")

    /** Pairs with the PC at [host] using the [code] it shows. */
    suspend fun pair(host: String, port: Int, code: String, device: String): PcServer {
        val body = json.encodeToString(mapOf("code" to code.trim(), "device" to device)).toRequestBody(jsonType)
        val paired = call(Request.Builder().url(base(host, port) + "/pair").post(body).build()) {
            json.decodeFromString<Paired>(it.body.string())
        }
        return PcServer(host = host, port = port, token = paired.token, name = paired.name.ifEmpty { host }, id = paired.id)
    }

    suspend fun folders(server: PcServer): PcFolders = call(Request.Builder().url(url(server, "/api/folders")).auth(server).build()) {
        json.decodeFromString<PcFolders>(it.body.string())
    }

    /**
     * Sends one file of a download: [name] is "<gid>.cbz" or "ComicInfo.xml", [size] its exact size (the PC reads that
     * many bytes). [title] lets the PC show it while it comes in.
     */
    suspend fun upload(
        server: PcServer,
        gid: Long,
        name: String,
        folder: String,
        dir: String,
        title: String,
        file: Path,
        size: Long,
        onProgress: (Long) -> Unit,
    ) {
        val body = object : RequestBody() {
            override fun contentType() = zipType
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                file.read {
                    asInputStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            sink.write(buffer, 0, n)
                            done += n
                            onProgress(done)
                        }
                        if (done != size) throw IOException("$name 读到 $done 字节，和大小 $size 对不上")
                    }
                }
            }
        }
        val path = "/api/items/$gid/files/${enc(name)}?folder=${enc(folder)}&dir=${enc(dir)}&title=${enc(title.take(200))}"
        call(Request.Builder().url(url(server, path)).auth(server).put(body).build()) { }
    }

    /** All files sent: the PC extracts it now. */
    suspend fun received(server: PcServer, gid: Long, title: String) {
        val body = json.encodeToString(mapOf("title" to title)).toRequestBody(jsonType)
        call(Request.Builder().url(url(server, "/api/items/$gid/received")).auth(server).post(body).build()) { }
    }

    suspend fun status(server: PcServer, gids: Collection<Long>): List<PcRemoteItem> {
        val path = "/api/items?gids=${gids.joinToString(",")}"
        return call(Request.Builder().url(url(server, path)).auth(server).build()) {
            json.decodeFromString<Items>(it.body.string()).items
        }
    }

    /** Downloads the packed archive of [gid] to [target]; returns its size. */
    suspend fun download(server: PcServer, gid: Long, target: Path, onProgress: (Long, Long) -> Unit): Long =
        call(Request.Builder().url(url(server, "/api/items/$gid/result")).auth(server).build()) { response ->
            val total = response.body.contentLength()
            var done = 0L
            target.write {
                asOutputStream().let { output ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                    output.flush()
                }
            }
            if (total >= 0 && done != total) throw IOException("Download cut off at $done of $total bytes")
            done
        }

    suspend fun taken(server: PcServer, gid: Long) {
        call(Request.Builder().url(url(server, "/api/items/$gid/taken")).auth(server).post(ByteArray(0).toRequestBody()).build()) { }
    }

    /** Tells the PC that taking [gid] back failed, so its list shows why. */
    suspend fun takeFailed(server: PcServer, gid: Long, reason: String) {
        val body = json.encodeToString(mapOf("message" to reason)).toRequestBody(jsonType)
        call(Request.Builder().url(url(server, "/api/items/$gid/failed")).auth(server).post(body).build()) { }
    }

    suspend fun cancel(server: PcServer, gid: Long) {
        call(Request.Builder().url(url(server, "/api/items/$gid")).auth(server).delete().build()) { }
    }
}
