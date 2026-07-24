package com.thinktank.recorder.next.data.remote

import com.thinktank.recorder.next.data.settings.UserSettings
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

data class UploadReceipt(
    val uploadId: String,
    val recordingId: String,
    val chunkId: String,
    val filename: String,
    val size: Long,
    val sha256: String,
    val status: String,
    val requestId: String?,
)

data class RemoteNote(
    val id: String,
    val folder: String,
    val name: String,
    val content: String,
    val revision: String,
    val updatedAt: String,
)

data class ApkInfo(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val size: Long,
    val releaseNotes: String,
)

class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val requestId: String? = null,
) : IOException(message)

@Singleton
class ReceiverApi @Inject constructor(
    private val client: OkHttpClient,
) : NotesRemoteGateway {
    suspend fun health(settings: UserSettings): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(settings, "api", "v1", "health"))
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            response.isSuccessful
        }
    }

    suspend fun upload(
        settings: UserSettings,
        file: File,
        uploadId: String,
        recordingId: String,
        chunkId: String,
        sha256: String,
    ): UploadReceipt = withContext(Dispatchers.IO) {
        val request = authenticated(
            settings,
            url(settings, "api", "v1", "upload", settings.userId, file.name),
        )
            .header("X-Content-SHA256", sha256)
            .header("Idempotency-Key", uploadId)
            .header("X-Recording-ID", recordingId)
            .header("X-Chunk-ID", chunkId)
            .put(file.asRequestBody(file.audioMediaType()))
            .build()
        executeJson(request) { json, response ->
            UploadReceipt(
                uploadId = json.getString("uploadId"),
                recordingId = json.getString("recordingId"),
                chunkId = json.getString("chunkId"),
                filename = json.getString("filename"),
                size = json.getLong("size"),
                sha256 = json.getString("sha256"),
                status = json.getString("status"),
                requestId = json.optString("requestId").ifBlank {
                    response.header("X-Request-ID")
                },
            )
        }
    }

    override suspend fun listNotes(settings: UserSettings): List<RemoteNote> =
        withContext(Dispatchers.IO) {
            val request = authenticated(
                settings,
                url(settings, "api", "v1", "notes", settings.userId),
            ).get().build()
            executeJson(request) { json, _ ->
                val array = json.optJSONArray("notes") ?: JSONArray()
                buildList {
                    for (index in 0 until array.length()) {
                        add(array.getJSONObject(index).toRemoteNote())
                    }
                }
            }
        }

    override suspend fun getNote(settings: UserSettings, id: String): RemoteNote =
        withContext(Dispatchers.IO) {
            val request = authenticated(
                settings,
                url(settings, "api", "v1", "notes", settings.userId, id),
            ).get().build()
            executeJson(request) { json, _ -> json.toRemoteNote() }
        }

    override suspend fun createNote(
        settings: UserSettings,
        folder: String,
        name: String,
        content: String,
    ): RemoteNote = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("folder", folder)
            .put("name", name)
            .put("content", content)
        val request = authenticated(
            settings,
            url(settings, "api", "v1", "notes", settings.userId),
        )
            .post(payload.toString().toRequestBody(JSON))
            .build()
        executeJson(request) { json, _ -> json.toRemoteNote() }
    }

    override suspend fun updateNote(
        settings: UserSettings,
        note: RemoteNote,
        content: String,
    ): RemoteNote = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("content", content)
        val request = authenticated(
            settings,
            url(settings, "api", "v1", "notes", settings.userId, note.id),
        )
            .header("If-Match", "\"${note.revision}\"")
            .put(payload.toString().toRequestBody(JSON))
            .build()
        executeJson(request) { json, _ -> json.toRemoteNote() }
    }

    override suspend fun archiveNote(settings: UserSettings, note: RemoteNote): Unit =
        withContext(Dispatchers.IO) {
            val request = authenticated(
                settings,
                url(settings, "api", "v1", "notes", settings.userId, note.id),
            )
                .header("If-Match", "\"${note.revision}\"")
                .delete()
                .build()
            executeJson(request) { json, _ -> json.optBoolean("archived") }
            Unit
        }

    suspend fun apkInfo(settings: UserSettings): ApkInfo = withContext(Dispatchers.IO) {
        val request = authenticated(
            settings,
            url(settings, "api", "v1", "apk", "info"),
        ).get().build()
        executeJson(request) { json, _ ->
            ApkInfo(
                versionCode = json.optInt("versionCode"),
                versionName = json.optString("versionName"),
                sha256 = json.optString("sha256"),
                size = json.optLong("size"),
                releaseNotes = json.optString("releaseNotes"),
            )
        }
    }

    private fun authenticated(
        settings: UserSettings,
        url: HttpUrl,
    ): Request.Builder {
        require(settings.token.isNotBlank()) { "서버 token이 필요합니다" }
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.token}")
            .header("Accept", "application/json")
    }

    private fun url(settings: UserSettings, vararg segments: String): HttpUrl {
        val base = (settings.serverUrl.trimEnd('/') + "/").toHttpUrl()
        return base.newBuilder().apply {
            segments.forEach(::addPathSegment)
        }.build()
    }

    private fun <T> executeJson(
        request: Request,
        mapper: (JSONObject, okhttp3.Response) -> T,
    ): T {
        client.newCall(request).execute().use { response ->
            val responseBody = response.body
            val length = responseBody?.contentLength() ?: 0
            if (length > MAX_JSON_BYTES) {
                throw ApiException(413, "RESPONSE_TOO_LARGE", "서버 응답이 너무 큽니다")
            }
            val body = readBoundedBody(responseBody)
            val json = runCatching { JSONObject(body) }.getOrElse {
                throw ApiException(
                    response.code,
                    "INVALID_RESPONSE",
                    "서버가 올바른 JSON을 반환하지 않았습니다",
                    response.header("X-Request-ID"),
                )
            }
            if (!response.isSuccessful) throw json.toApiException(response.code)
            return mapper(json, response)
        }
    }

    private fun JSONObject.toApiException(status: Int): ApiException {
        val error = optJSONObject("error")
        return ApiException(
            status = status,
            code = error?.optString("code").orEmpty().ifBlank { "HTTP_$status" },
            message = error?.optString("message").orEmpty().ifBlank { "서버 요청 실패" },
            requestId = optString("requestId").ifBlank { null },
        )
    }

    private fun JSONObject.toRemoteNote() = RemoteNote(
        id = getString("id"),
        folder = getString("folder"),
        name = getString("name"),
        content = getString("content"),
        revision = getString("revision"),
        updatedAt = getString("updatedAt"),
    )

    private fun readBoundedBody(body: okhttp3.ResponseBody?): String {
        if (body == null) return ""
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(
                buffer,
                minOf(8L * 1024, MAX_JSON_BYTES + 1 - total),
            )
            if (read == -1L) break
            total += read
            if (total > MAX_JSON_BYTES) {
                throw ApiException(
                    413,
                    "RESPONSE_TOO_LARGE",
                    "서버 응답이 너무 큽니다",
                )
            }
        }
        return buffer.readUtf8()
    }

    private fun File.audioMediaType() = when (extension.lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }.toMediaType()

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_JSON_BYTES = 10L * 1024 * 1024
    }
}
