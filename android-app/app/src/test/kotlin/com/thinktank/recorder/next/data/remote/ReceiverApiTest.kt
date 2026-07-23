package com.thinktank.recorder.next.data.remote

import com.thinktank.recorder.next.data.settings.UserSettings
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReceiverApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ReceiverApi
    private lateinit var settings: UserSettings

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ReceiverApi(OkHttpClient.Builder().followRedirects(false).build())
        settings = UserSettings(
            serverUrl = server.url("/").toString().trimEnd('/'),
            userId = "user 1",
            token = "secret-token",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun uploadSendsHashBearerAndIdempotency() = runBlocking {
        val file = File.createTempFile("receiver-api", ".m4a").apply {
            writeBytes("audio".toByteArray())
        }
        val hash = "a".repeat(64)
        val recordingId = "11111111-1111-4111-8111-111111111111"
        val chunkId = "22222222-2222-4222-8222-222222222222"
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"uploadId":"server-id","recordingId":"$recordingId","chunkId":"$chunkId",
                     "filename":"${file.name}","size":5,
                     "sha256":"$hash","status":"stored","requestId":"r1"}
                    """.trimIndent(),
                ),
        )

        val receipt = api.upload(
            settings = settings,
            file = file,
            uploadId = "client-upload",
            recordingId = recordingId,
            chunkId = chunkId,
            sha256 = hash,
        )
        val request = server.takeRequest()

        assertTrue(request.path!!.startsWith("/api/v1/upload/user%201/"))
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertEquals(hash, request.getHeader("X-Content-SHA256"))
        assertEquals("client-upload", request.getHeader("Idempotency-Key"))
        assertEquals(recordingId, request.getHeader("X-Recording-ID"))
        assertEquals(chunkId, request.getHeader("X-Chunk-ID"))
        assertEquals("server-id", receipt.uploadId)
        assertEquals(recordingId, receipt.recordingId)
        assertEquals(chunkId, receipt.chunkId)
        file.delete()
        Unit
    }

    @Test
    fun noteListParsesVersionedEnvelope() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"notes":[{"id":"n1","folder":"30-ideas","name":"idea.md",
                    "content":"# Idea","revision":"rev","updatedAt":"2026-07-23T00:00:00Z"}]}
                    """.trimIndent(),
                ),
        )

        val notes = api.listNotes(settings)

        assertEquals(1, notes.size)
        assertEquals("n1", notes.single().id)
        assertEquals("# Idea", notes.single().content)
    }

    @Test
    fun structuredConflictPreservesStatusAndCode() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(412)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"error":{"code":"REVISION_MISMATCH","message":"stale"},"requestId":"r2"}""",
                ),
        )
        val note = RemoteNote("n1", "30-ideas", "idea.md", "old", "rev1", "")

        val error = runCatching { api.updateNote(settings, note, "new") }.exceptionOrNull()

        assertTrue(error is ApiException)
        error as ApiException
        assertEquals(412, error.status)
        assertEquals("REVISION_MISMATCH", error.code)
        assertEquals("r2", error.requestId)
    }
}
