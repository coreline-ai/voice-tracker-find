package com.thinktank.recorder.next.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinktank.recorder.next.data.local.ChunkState
import com.thinktank.recorder.next.data.local.RecordingDao
import com.thinktank.recorder.next.data.local.RecordingSessionEntity
import com.thinktank.recorder.next.data.local.RecordingState
import com.thinktank.recorder.next.data.local.ThinkTankDatabase
import com.thinktank.recorder.ondevice.audio.AndroidPcmNormalizer
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical-device regression for the service's FIFO START/STOP ownership.
 *
 * It refuses to interrupt an already active recording and removes only the files and database
 * rows created by this test after the assertions complete.
 */
@RunWith(AndroidJUnit4::class)
class RecorderServiceDeviceTest {
    @Test
    fun duplicateStartThenStopFinalizesExactlyOneSessionWithoutPartFile() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
        val database = Room.databaseBuilder(
            context,
            ThinkTankDatabase::class.java,
            DATABASE_NAME,
        ).build()
        val dao = database.recordingDao()
        var session: RecordingSessionEntity? = null
        try {
            assumeTrue(
                "이미 진행 중인 녹음이 있어 비파괴 device QA를 건너뜁니다",
                dao.unfinishedChunks().isEmpty(),
            )
            val beforeId = dao.latestSession()?.id
            val startedAt = System.currentTimeMillis()

            context.startRecorder(RecorderService.ACTION_START)
            session = awaitNewSession(dao, beforeId, startedAt)
            // The second START must update ownership without creating another capture runner.
            context.startRecorder(RecorderService.ACTION_START)
            delay(MINIMUM_RECORDING_MS)
            context.startRecorder(RecorderService.ACTION_STOP)

            val terminal = awaitTerminalSession(dao, session.id)
            val chunks = dao.chunksForSession(session.id)
            assertEquals(RecordingState.STOPPED, terminal.state)
            assertEquals(1, chunks.size)
            assertTrue(chunks.all { it.state == ChunkState.READY })
            assertTrue(chunks.all { File(it.path).isFile && File(it.path).length() > 0L })
            assertTrue(dao.unfinishedChunks().none { it.sessionId == session.id })
            assertFalse(
                File(context.filesDir, "recordings")
                    .walkTopDown()
                    .any { it.isFile && it.name.endsWith(".part") && it.lastModified() >= startedAt },
            )
            val normalized = verifyOnDevicePcmNormalization(context, chunks.single().path, session.id)
            println(
                "DEVICE_QA REC-01 terminal=${terminal.state} chunkCount=${chunks.size} " +
                    "pcmSamples=${normalized.sampleCount}",
            )
        } finally {
            // STOP is idempotent and ensures a failed assertion cannot leave a foreground service.
            context.startRecorder(RecorderService.ACTION_STOP)
            session?.let { cleanupTestSession(database, dao, it.id) }
            database.close()
        }
    }

    private fun Context.startRecorder(action: String) {
        val intent = Intent(this, RecorderService::class.java).setAction(action)
        if (action == RecorderService.ACTION_START) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private suspend fun awaitNewSession(
        dao: RecordingDao,
        previousId: String?,
        startedAt: Long,
    ): RecordingSessionEntity = withTimeout(START_TIMEOUT_MS) {
        while (currentCoroutineContext().isActive) {
            dao.latestSession()?.let { candidate ->
                if (candidate.id != previousId && candidate.startedAt >= startedAt - 1_000L) {
                    return@withTimeout candidate
                }
            }
            delay(POLL_MS)
        }
        error("Recorder session wait was cancelled")
    }

    private suspend fun awaitTerminalSession(
        dao: RecordingDao,
        sessionId: String,
    ): RecordingSessionEntity = withTimeout(STOP_TIMEOUT_MS) {
        while (currentCoroutineContext().isActive) {
            val current = checkNotNull(dao.session(sessionId))
            if (current.state == RecordingState.STOPPED || current.state == RecordingState.FAILED) {
                return@withTimeout current
            }
            delay(POLL_MS)
        }
        error("Recorder terminal wait was cancelled")
    }

    private suspend fun cleanupTestSession(
        database: ThinkTankDatabase,
        dao: RecordingDao,
        sessionId: String,
    ) {
        dao.chunksForSession(sessionId).forEach { chunk ->
            File(chunk.path).delete()
            File(chunk.path).parentFile?.let { parent ->
                File(parent, "quarantine/${File(chunk.path).name}").delete()
            }
        }
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM recording_sessions WHERE id = ?",
            arrayOf(sessionId),
        )
    }

    private suspend fun verifyOnDevicePcmNormalization(
        context: Context,
        sourcePath: String,
        sessionId: String,
    ): com.thinktank.recorder.ondevice.audio.NormalizedPcm {
        val output = File(context.cacheDir, "device-qa-$sessionId.pcm")
        try {
            val normalized = AndroidPcmNormalizer().normalize(File(sourcePath), output)
            assertTrue("정규화 PCM이 비어 있습니다", normalized.sampleCount > 0L)
            assertTrue("정규화 PCM 길이가 0입니다", normalized.durationMs > 0L)
            assertEquals(normalized.sampleCount * 2L, output.length())
            assertEquals(
                normalized.durationMs,
                normalized.sampleCount * 1_000L / 16_000L,
            )
            return normalized
        } finally {
            output.delete()
        }
    }

    private companion object {
        const val DATABASE_NAME = "thinktank-recorder.db"
        const val POLL_MS = 100L
        const val START_TIMEOUT_MS = 10_000L
        const val STOP_TIMEOUT_MS = 15_000L
        const val MINIMUM_RECORDING_MS = 2_000L
    }
}
