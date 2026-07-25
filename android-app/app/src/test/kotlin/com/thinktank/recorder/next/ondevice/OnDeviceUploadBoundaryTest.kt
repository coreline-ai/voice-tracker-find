package com.thinktank.recorder.next.ondevice

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thinktank.recorder.next.data.local.ThinkTankDatabase
import com.thinktank.recorder.next.data.remote.ReceiverApi
import com.thinktank.recorder.next.data.repository.NotesRepository
import com.thinktank.recorder.next.data.repository.SyncRepository
import com.thinktank.recorder.next.data.repository.SyncRunResult
import com.thinktank.recorder.next.data.settings.SettingsReader
import com.thinktank.recorder.next.data.settings.UserSettings
import com.thinktank.recorder.ondevice.api.OnDeviceSessionState
import com.thinktank.recorder.ondevice.api.SttEngineType
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import com.thinktank.recorder.ondevice.data.OnDeviceDatabase
import com.thinktank.recorder.ondevice.data.OnDeviceRepository
import java.io.Closeable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class OnDeviceUploadBoundaryTest : Closeable {
    private lateinit var mainDatabase: ThinkTankDatabase
    private lateinit var localAiDatabase: OnDeviceDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        mainDatabase = Room.inMemoryDatabaseBuilder(context, ThinkTankDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        localAiDatabase = Room.inMemoryDatabaseBuilder(context, OnDeviceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    override fun close() {
        mainDatabase.close()
        localAiDatabase.close()
    }

    @Test
    fun tenLocalAiSessionsNeverEnterRecordingUploadQueue() = runBlocking {
        createLocalAiSessions()

        assertEquals(10, localAiDatabase.sessionDao().observeAll().first().size)
        assertEquals(0, mainDatabase.recordingDao().observePendingCount().first())
        assertNull(mainDatabase.recordingDao().nextReady(Long.MAX_VALUE))
    }

    @Test
    fun syncAllowsNotesGetButNeverUploadsLocalAiSessions() = runBlocking {
        createLocalAiSessions()
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"notes":[]}"""),
            )
            server.start()
            val settings = UserSettings(
                serverUrl = server.url("/").toString().trimEnd('/'),
                userId = "local-user",
                token = "local-token",
            )
            val settingsReader = object : SettingsReader {
                override suspend fun current(): UserSettings = settings
            }
            val api = ReceiverApi(OkHttpClient())
            val notes = NotesRepository(mainDatabase.notesDao(), api, settingsReader)
            val sync = SyncRepository(mainDatabase.recordingDao(), api, settingsReader, notes)

            val result = sync.run("boundary-test")

            assertEquals(SyncRunResult.Success(uploaded = 0, notes = 0), result)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.path.orEmpty().startsWith("/api/v1/notes/local-user"))
            assertTrue(request.path.orEmpty().contains("/upload/").not())
        }
    }

    private suspend fun createLocalAiSessions() {
        val localAi = OnDeviceRepository(localAiDatabase.sessionDao())
        repeat(10) { index ->
            localAi.begin(
                id = "local-$index",
                sttEngine = SttEngineType.ANDROID_ON_DEVICE,
                summaryEngine = SummaryEngineType.NONE,
                state = OnDeviceSessionState.CANCELLED,
                operationToken = null,
            )
        }
    }
}
