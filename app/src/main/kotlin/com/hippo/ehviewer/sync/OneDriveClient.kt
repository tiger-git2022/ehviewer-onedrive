package com.hippo.ehviewer.sync

import android.app.Activity
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.R
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.exception.MsalException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import splitties.init.appCtx

data class OneDriveAccount(val id: String, val name: String)

/** A file in the app folder, as listed by Graph. */
data class RemoteItem(val name: String, val eTag: String?, val size: Long, val lastModified: String?)

open class OneDriveException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The file changed on the server since the ETag we sent: download, merge and retry. */
class OneDrivePreconditionException : OneDriveException("The file was changed by another device")

class OneDriveAuthException(message: String, cause: Throwable? = null) : OneDriveException(message, cause)

/**
 * Microsoft sign-in (MSAL, single account) and the Microsoft Graph calls of the OneDrive sync.
 *
 * Requests go through a dedicated OkHttp client so neither the E-Hentai cookies nor the custom user agent
 * of the app's own client ever reach Microsoft.
 */
object OneDriveClient {
    private const val TAG = "OneDriveClient"
    private const val GRAPH_ROOT = "https://graph.microsoft.com/v1.0/me/drive/special/approot"
    private const val SIMPLE_UPLOAD_LIMIT = 4L * 1024 * 1024

    // Upload session fragments must be a multiple of 320 KiB
    private const val UPLOAD_CHUNK_SIZE = 320 * 1024 * 16
    private val SCOPES = listOf("Files.ReadWrite")
    private val octetStream = "application/octet-stream".toMediaType()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Only the release build of the default flavor is registered in Azure (package name and signature),
     * debug, benchmark and marshmallow builds cannot sign in.
     */
    val isSupported = BuildConfig.APPLICATION_ID == "moe.tarsin.ehviewer"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val initMutex = Mutex()

    @Volatile
    private var application: ISingleAccountPublicClientApplication? = null

    private suspend fun application(): ISingleAccountPublicClientApplication {
        if (!isSupported) throw OneDriveAuthException("This build does not support OneDrive sync")
        application?.let { return it }
        return initMutex.withLock {
            application ?: try {
                withContext(Dispatchers.IO) {
                    PublicClientApplication.createSingleAccountPublicClientApplication(appCtx, R.raw.auth_config_single_account)
                }.also { application = it }
            } catch (e: MsalException) {
                throw OneDriveAuthException("Could not initialize Microsoft sign-in: ${e.message}", e)
            }
        }
    }

    suspend fun currentAccount(): OneDriveAccount? {
        if (!isSupported) return null
        val app = application()
        return withContext(Dispatchers.IO) { app.currentAccount.currentAccount }?.toOneDriveAccount()
    }

    suspend fun signIn(activity: Activity): OneDriveAccount {
        val app = application()
        currentAccount()?.let { return it }
        return suspendCancellableCoroutine { cont ->
            val parameters = SignInParameters.builder()
                .withActivity(activity)
                .withScopes(SCOPES)
                .withCallback(
                    object : AuthenticationCallback {
                        override fun onSuccess(authenticationResult: IAuthenticationResult) {
                            cont.resume(authenticationResult.account.toOneDriveAccount())
                        }

                        override fun onError(exception: MsalException) {
                            cont.resumeWithException(OneDriveAuthException("Microsoft sign-in failed: ${exception.message}", exception))
                        }

                        override fun onCancel() {
                            cont.resumeWithException(OneDriveAuthException("Microsoft sign-in was canceled"))
                        }
                    },
                )
                .build()
            app.signIn(parameters)
        }
    }

    suspend fun signOut() {
        val app = application()
        withContext(Dispatchers.IO) { app.signOut() }
    }

    private suspend fun accessToken(): String {
        val app = application()
        return withContext(Dispatchers.IO) {
            val account = app.currentAccount.currentAccount ?: throw OneDriveAuthException("Sign in to OneDrive first")
            try {
                app.acquireTokenSilent(
                    AcquireTokenSilentParameters.Builder()
                        .withScopes(SCOPES)
                        .forAccount(account)
                        .fromAuthority(account.authority)
                        .build(),
                ).accessToken
            } catch (e: MsalException) {
                throw OneDriveAuthException("Microsoft sign-in has expired, sign in again: ${e.message}", e)
            }
        }
    }

    private fun IAccount.toOneDriveAccount() = OneDriveAccount(id = id, name = username.ifBlank { id })

    private fun itemUrl(path: String) = "$GRAPH_ROOT:/$path"

    private suspend fun execute(request: Request.Builder, authorized: Boolean = true): Response {
        if (authorized) request.header("Authorization", "Bearer ${accessToken()}")
        val call = httpClient.newCall(request.build())
        return withContext(Dispatchers.IO) { call.execute() }
    }

    /** Lists a folder of the app folder, an absent folder is empty. */
    suspend fun list(folder: String): Map<String, RemoteItem> = withContext(Dispatchers.IO) {
        val items = mutableMapOf<String, RemoteItem>()
        var url: String? = "${itemUrl(folder)}:/children?\$select=name,eTag,size,lastModifiedDateTime&\$top=200"
        while (url != null) {
            val page = execute(Request.Builder().url(url).get()).use { response ->
                when {
                    response.code == 404 -> return@withContext items
                    !response.isSuccessful -> throw response.toGraphException("list $folder")
                    else -> json.decodeFromString<ChildrenPage>(response.body.string())
                }
            }
            page.value.forEach { items[it.name] = RemoteItem(it.name, it.eTag, it.size, it.lastModifiedDateTime) }
            url = page.nextLink
        }
        items
    }

    /** Downloads a file into [dest]. Returns false when the file does not exist. */
    suspend fun download(path: String, dest: File, onProgress: TransferProgress? = null): Boolean = withContext(Dispatchers.IO) {
        execute(Request.Builder().url("${itemUrl(path)}:/content").get()).use { response ->
            when {
                response.code == 404 -> false
                !response.isSuccessful -> throw response.toGraphException("download $path")
                else -> {
                    dest.parentFile?.mkdirs()
                    val size = response.body.contentLength()
                    dest.outputStream().use { out ->
                        response.body.byteStream().use { input ->
                            val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                            var done = 0L
                            onProgress?.invoke(0, size)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                                done += count
                                onProgress?.invoke(done, size)
                            }
                        }
                    }
                    true
                }
            }
        }
    }

    /**
     * Uploads [file] to [path] only if the server copy still has [eTag], or does not exist when [eTag] is null.
     *
     * @return the new ETag
     * @throws OneDrivePreconditionException when another device wrote the file in between
     */
    suspend fun upload(path: String, file: File, eTag: String?, onProgress: TransferProgress? = null): String? = withContext(Dispatchers.IO) {
        if (file.length() > SIMPLE_UPLOAD_LIMIT) return@withContext uploadLarge(path, file, eTag, onProgress)
        val conflict = if (eTag == null) "?@microsoft.graph.conflictBehavior=fail" else ""
        val total = file.length()
        val body = file.asRequestBody(octetStream).let { plain ->
            if (onProgress == null) {
                plain
            } else {
                object : RequestBody() {
                    override fun contentType() = octetStream
                    override fun contentLength() = total

                    override fun writeTo(sink: BufferedSink) {
                        file.inputStream().use { input ->
                            val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                            var done = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                sink.write(buffer, 0, count)
                                done += count
                                onProgress(done, total)
                            }
                        }
                    }
                }
            }
        }
        val request = Request.Builder()
            .url("${itemUrl(path)}:/content$conflict")
            .conditional(eTag)
            .put(body)
        execute(request).use { response ->
            response.ensureUploaded(path)
            json.decodeFromString<DriveItem>(response.body.string()).eTag
        }
    }

    private suspend fun uploadLarge(path: String, file: File, eTag: String?, onProgress: TransferProgress?): String? {
        val behavior = if (eTag == null) "fail" else "replace"
        val body = """{"item":{"@microsoft.graph.conflictBehavior":"$behavior"}}""".toRequestBody(jsonMediaType)
        val session = execute(Request.Builder().url("${itemUrl(path)}:/createUploadSession").conditional(eTag).post(body)).use { response ->
            response.ensureUploaded(path)
            json.decodeFromString<UploadSession>(response.body.string())
        }
        val total = file.length()
        RandomAccessFile(file, "r").use { raf ->
            var offset = 0L
            val buffer = ByteArray(UPLOAD_CHUNK_SIZE)
            while (offset < total) {
                val length = minOf(UPLOAD_CHUNK_SIZE.toLong(), total - offset).toInt()
                raf.seek(offset)
                raf.readFully(buffer, 0, length)
                val request = Request.Builder()
                    .url(session.uploadUrl)
                    .header("Content-Range", "bytes $offset-${offset + length - 1}/$total")
                    .put(buffer.toRequestBody(octetStream, 0, length))
                // The upload URL is pre-authenticated, it must not get the bearer token
                execute(request, authorized = false).use { response ->
                    response.ensureUploaded(path)
                    offset += length
                    onProgress?.invoke(offset, total)
                    if (offset >= total) return json.decodeFromString<DriveItem>(response.body.string()).eTag
                }
            }
        }
        return null
    }

    /** Deletes a file or a folder, an absent one counts as deleted. */
    suspend fun delete(path: String) {
        execute(Request.Builder().url(itemUrl(path)).delete()).use { response ->
            if (!response.isSuccessful && response.code != 404) throw response.toGraphException("delete $path")
        }
    }

    private fun Request.Builder.conditional(eTag: String?) = apply {
        if (eTag != null) header("If-Match", eTag) else header("If-None-Match", "*")
    }

    private fun Response.ensureUploaded(path: String) {
        when {
            code == 412 || code == 409 -> throw OneDrivePreconditionException()
            !isSuccessful -> throw toGraphException("upload $path")
        }
    }

    private fun Response.toGraphException(operation: String): OneDriveException {
        val error = runCatching { json.decodeFromString<GraphErrorResponse>(body.string()).error }.getOrNull()
        val details = listOfNotNull(error?.code, error?.message).filter(String::isNotBlank).joinToString(": ")
        val message = buildString {
            append("Could not $operation on OneDrive (HTTP $code)")
            if (details.isNotBlank()) append(": $details")
        }
        logcat(TAG) { message }
        return if (code == 401) OneDriveAuthException(message) else OneDriveException(message)
    }

    @Serializable
    private data class ChildrenPage(
        val value: List<DriveItem> = emptyList(),
        @SerialName("@odata.nextLink") val nextLink: String? = null,
    )

    @Serializable
    private data class DriveItem(
        val name: String = "",
        val eTag: String? = null,
        val size: Long = 0,
        val lastModifiedDateTime: String? = null,
    )

    @Serializable
    private data class UploadSession(val uploadUrl: String)

    @Serializable
    private data class GraphErrorResponse(val error: GraphError? = null)

    @Serializable
    private data class GraphError(val code: String? = null, val message: String? = null)
}

/** Bytes transferred so far and the size, -1 if unknown. */
typealias TransferProgress = (done: Long, total: Long) -> Unit

private const val TRANSFER_BUFFER_SIZE = 64 * 1024
