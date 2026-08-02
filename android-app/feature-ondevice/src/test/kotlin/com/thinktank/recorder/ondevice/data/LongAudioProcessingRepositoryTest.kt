package com.thinktank.recorder.ondevice.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.api.MainRecordingSource
import com.thinktank.recorder.ondevice.api.OnDeviceSessionState
import com.thinktank.recorder.ondevice.processing.LongAudioJobState
import com.thinktank.recorder.ondevice.processing.LongAudioStage
import com.thinktank.recorder.ondevice.processing.jobState
import java.io.Closeable
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LongAudioProcessingRepositoryTest : Closeable {
    private lateinit var database: OnDeviceDatabase
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OnDeviceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    override fun close() {
        database.close()
    }

    @Test
    fun jobAndSegmentCheckpointsAreIdempotentAndRecoverable() = runBlocking {
        val repository = LongAudioProcessingRepository(database) { now++ }
        val source = MainRecordingSource(
            id = "recording",
            createdAt = 1L,
            durationMs = 2L * 60L * 60L * 1_000L,
            sizeBytes = 123L,
            sha256 = "a".repeat(64),
            extension = "m4a",
            storageState = "READY",
        )
        val job = repository.createJob(
            source = source,
            sourceSnapshot = java.io.File("/tmp/source.m4a"),
            pcmFile = java.io.File("/tmp/source.pcm"),
            jobId = "job",
            sessionId = "session",
        )
        assertEquals(LongAudioJobState.QUEUED, job.jobState)
        assertNotNull(database.sessionDao().get("session"))
        assertEquals("job", database.sessionDao().get("session")?.activeProcessingJobId)

        assertNotNull(repository.claim("job", "service"))
        assertTrue(
            repository.checkpointSegment(
                job = job,
                passType = "PRIMARY",
                ordinal = 0,
                startMs = 0L,
                endMs = 28_000L,
                text = "첫 번째 장시간 전사 구간입니다.",
            ),
        )
        assertFalse(
            repository.checkpointSegment(
                job = job,
                passType = "PRIMARY",
                ordinal = 0,
                startMs = 0L,
                endMs = 28_000L,
                text = "첫 번째 장시간 전사 구간입니다.",
            ),
        )
        assertEquals(1, repository.primarySegments("job").size)

        assertEquals(1, repository.recoverInterrupted())
        assertEquals(LongAudioJobState.INTERRUPTED, repository.getJob("job")?.jobState)
        assertEquals(1, repository.primarySegments("job").size)
        assertEquals(
            OnDeviceSessionState.FAILED_RECOVERABLE.name,
            database.sessionDao().get("session")?.state,
        )
        assertEquals(null, database.sessionDao().get("session")?.operationToken)
    }

    @Test
    fun summaryOnlyJobKeepsTranscriptAndDoesNotRequireAudioSnapshot() = runBlocking {
        database.sessionDao().insert(
            OnDeviceSessionEntity(
                id = "existing-session",
                createdAt = 1L,
                updatedAt = 1L,
                state = OnDeviceSessionState.COMPLETE.name,
                sttEngine = "SENSEVOICE_LOCAL_FILE",
                summaryEngine = "GEMMA_LOCAL",
                transcript = "기존 STT 원문을 그대로 사용해 다시 요약합니다.",
                title = "기존 제목",
                summary = "기존 요약은 새 root가 통과할 때까지 유지합니다.",
                summaryRootNodeId = "existing-root",
            ),
        )
        val repository = LongAudioProcessingRepository(database) { now++ }

        val job = repository.createSummaryOnlyJob(
            sessionId = "existing-session",
            jobId = "summary-job",
        )

        assertEquals(LongAudioStage.PLANNING_SUMMARY.name, job.stage)
        assertEquals("", job.sourceSnapshotPath)
        assertEquals(
            "summary-job",
            database.sessionDao().get("existing-session")?.activeProcessingJobId,
        )
        assertEquals(
            "기존 STT 원문을 그대로 사용해 다시 요약합니다.",
            database.sessionDao().get("existing-session")?.transcript,
        )
        assertEquals(
            "기존 요약은 새 root가 통과할 때까지 유지합니다.",
            database.sessionDao().get("existing-session")?.summary,
        )
        assertEquals("existing-root", database.sessionDao().get("existing-session")?.summaryRootNodeId)
    }

    @Test
    fun oneHourJobIsAccepted() = runBlocking {
        val repository = LongAudioProcessingRepository(database) { now++ }

        val job = repository.createJob(
            source = MainRecordingSource(
                id = "one-hour",
                createdAt = 1L,
                durationMs = 60L * 60L * 1_000L,
                sizeBytes = 1L,
                sha256 = "e".repeat(64),
                extension = "m4a",
                storageState = "READY",
            ),
            sourceSnapshot = java.io.File("/tmp/one-hour.m4a"),
            pcmFile = java.io.File("/tmp/one-hour.pcm"),
        )

        assertEquals(60L * 60L * 1_000L, job.sourceDurationMs)
    }

    @Test
    fun inactivePausedJobCanBeClaimedAndFinishedAsCancelled() = runBlocking {
        val repository = LongAudioProcessingRepository(database) { now++ }
        val job = repository.createJob(
            source = MainRecordingSource(
                id = "cancel-source",
                createdAt = 1L,
                durationMs = 60_000L,
                sizeBytes = 10L,
                sha256 = "c".repeat(64),
                extension = "m4a",
                storageState = "READY",
            ),
            sourceSnapshot = java.io.File("/tmp/cancel-source.m4a"),
            pcmFile = java.io.File("/tmp/cancel-source.pcm"),
            jobId = "cancel-job",
            sessionId = "cancel-session",
        )
        val firstToken = "first-service"
        assertNotNull(repository.claim(job.id, firstToken))
        assertTrue(
            repository.finish(
                jobId = job.id,
                serviceToken = firstToken,
                state = LongAudioJobState.PAUSED,
                stage = LongAudioStage.TRANSCRIBING,
            ),
        )

        assertTrue(repository.requestCancel(job.id))
        val cancelToken = "cancel-service"
        assertNotNull(repository.claim(job.id, cancelToken))
        assertTrue(
            repository.finish(
                jobId = job.id,
                serviceToken = cancelToken,
                state = LongAudioJobState.CANCELLED,
                stage = LongAudioStage.TRANSCRIBING,
            ),
        )
        assertEquals(LongAudioJobState.CANCELLED, repository.getJob(job.id)?.jobState)
    }

    @Test
    fun rootProjectionAndJobCompletionCommitTogether() = runBlocking {
        val repository = LongAudioProcessingRepository(database) { now++ }
        val job = repository.createJob(
            source = MainRecordingSource(
                id = "final-source",
                createdAt = 1L,
                durationMs = 60_000L,
                sizeBytes = 10L,
                sha256 = "d".repeat(64),
                extension = "m4a",
                storageState = "READY",
            ),
            sourceSnapshot = java.io.File("/tmp/final-source.m4a"),
            pcmFile = java.io.File("/tmp/final-source.pcm"),
            jobId = "final-job",
            sessionId = "final-session",
        )
        val serviceToken = "final-service"
        assertNotNull(repository.claim(job.id, serviceToken))
        val session = requireNotNull(database.sessionDao().get(job.sessionId))
        val summaryToken = requireNotNull(session.operationToken)
        assertEquals(
            1,
            database.sessionDao().advanceOperation(
                id = job.sessionId,
                token = summaryToken,
                allowedStates = listOf(OnDeviceSessionState.STARTING.name),
                targetState = OnDeviceSessionState.SUMMARIZING.name,
                now = now++,
            ),
        )

        assertTrue(
            repository.finalizeSuccess(
                job = requireNotNull(repository.getJob(job.id)),
                serviceToken = serviceToken,
                summaryToken = summaryToken,
                rootNodeId = "root-node",
                processingVersion = 1,
                result = LocalSummary(
                    title = "최종 제목",
                    bullets = listOf("전체 장시간 내용을 한 건으로 요약합니다."),
                    actionItems = emptyList(),
                    sourceHash = "source-hash",
                ),
            ),
        )

        val completedSession = requireNotNull(database.sessionDao().get(job.sessionId))
        assertEquals(OnDeviceSessionState.COMPLETE.name, completedSession.state)
        assertEquals("root-node", completedSession.summaryRootNodeId)
        assertEquals(null, completedSession.activeProcessingJobId)
        assertEquals(LongAudioJobState.COMPLETE, repository.getJob(job.id)?.jobState)
    }

    @Test(expected = IllegalArgumentException::class)
    fun inputLongerThanTwoHoursIsRejectedBeforeJobCreation() = runBlocking {
        val repository = LongAudioProcessingRepository(database) { now++ }
        repository.createJob(
            source = MainRecordingSource(
                id = "too-long",
                createdAt = 1L,
                durationMs = 2L * 60L * 60L * 1_000L + 1L,
                sizeBytes = 1L,
                sha256 = "b".repeat(64),
                extension = "m4a",
                storageState = "READY",
            ),
            sourceSnapshot = java.io.File("/tmp/source.m4a"),
            pcmFile = java.io.File("/tmp/source.pcm"),
        )
        Unit
    }
}
