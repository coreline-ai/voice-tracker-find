package com.thinktank.recorder.ondevice.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.api.MainRecordingSource
import com.thinktank.recorder.ondevice.api.OnDeviceFailureStage
import com.thinktank.recorder.ondevice.api.OnDeviceSessionState
import com.thinktank.recorder.ondevice.api.SttEngineType
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import com.thinktank.recorder.ondevice.recording.LocalAudioFileManager
import java.io.File
import java.io.RandomAccessFile
import java.io.Closeable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnDeviceRepositoryTest : Closeable {
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
    fun sessionStaysLocalOnlyThroughTranscriptAndSummary() = runBlocking {
        val repository = OnDeviceRepository(database.sessionDao()) { now++ }
        val id = repository.begin(
            SttEngineType.ANDROID_ON_DEVICE,
            SummaryEngineType.EXTRACTIVE_KOTLIN,
        )

        repository.saveTranscript(id, "기기 안에서 처리합니다.")
        repository.markSummarizing(id)
        repository.saveSummary(
            id,
            LocalSummary(
                title = "기기 안에서 처리",
                bullets = listOf("기기 안에서 처리합니다."),
                actionItems = emptyList(),
            ),
        )

        val session = database.sessionDao().get(id)!!
        assertEquals(OnDeviceSessionEntity.DATA_POLICY_LOCAL_ONLY, session.dataPolicy)
        assertEquals(OnDeviceSessionState.COMPLETE.name, session.state)
        assertEquals("기기 안에서 처리합니다.", session.transcript)
        assertTrue(database.sessionDao().observeAll().first().any { it.id == id })
    }

    @Test
    fun interruptedLiveRecognitionBecomesCancelled() = runBlocking {
        val repository = OnDeviceRepository(database.sessionDao()) { now++ }
        val id = repository.begin(
            SttEngineType.ANDROID_ON_DEVICE,
            SummaryEngineType.NONE,
        )

        assertEquals(1, repository.recoverInterrupted())
        assertEquals(
            OnDeviceSessionState.CANCELLED.name,
            database.sessionDao().get(id)?.state,
        )
    }

    @Test
    fun interruptedTranscriptionWithValidWavBecomesAudioReady() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioFiles = LocalAudioFileManager(context)
        val repository = OnDeviceRepository(database.sessionDao(), audioFiles) { now++ }
        val id = "recovery-audio"
        val token = "token"
        val wav = audioFiles.recordingFile(id)
        writeSilentWav(wav)
        repository.begin(
            id,
            SttEngineType.ANDROID_ON_DEVICE,
            SummaryEngineType.NONE,
            OnDeviceSessionState.TRANSCRIBING,
            token,
        )
        assertTrue(repository.attachAudio(id, token, wav.absolutePath))

        assertEquals(1, repository.recoverInterrupted())
        val recovered = database.sessionDao().get(id)!!
        assertEquals(OnDeviceSessionState.AUDIO_READY.name, recovered.state)
        assertEquals(wav.absolutePath, recovered.audioPath)
        assertEquals(null, recovered.operationToken)
    }

    @Test
    fun interruptedSummaryPreservesTranscript() = runBlocking {
        val repository = OnDeviceRepository(database.sessionDao(), clock = { now++ })
        val id = "recovery-summary"
        repository.begin(
            id,
            SttEngineType.ANDROID_ON_DEVICE,
            SummaryEngineType.EXTRACTIVE_KOTLIN,
            OnDeviceSessionState.TRANSCRIPT_READY,
            null,
        )
        repository.saveTranscript(id, "보존할 전사")
        assertTrue(
            repository.startOperation(
                id,
                setOf(OnDeviceSessionState.TRANSCRIPT_READY),
                OnDeviceSessionState.SUMMARIZING,
                "summary-token",
            ),
        )

        assertEquals(1, repository.recoverInterrupted())
        val recovered = database.sessionDao().get(id)!!
        assertEquals(OnDeviceSessionState.TRANSCRIPT_READY.name, recovered.state)
        assertEquals("보존할 전사", recovered.transcript)
        assertEquals(null, recovered.operationToken)
    }

    @Test
    fun mainRecordingFileSessionStoresOnlySourceMetadataAndNeedsReselectionAfterRestart() = runBlocking {
        val repository = OnDeviceRepository(database.sessionDao(), clock = { now++ })
        repository.beginFromMainRecording(
            id = "main-recording-source",
            source = MainRecordingSource(
                id = "chunk-1",
                createdAt = 100L,
                durationMs = 20_000L,
                sizeBytes = 80_000L,
                sha256 = "a".repeat(64),
                extension = "m4a",
                storageState = "READY",
            ),
            sttEngine = SttEngineType.SENSEVOICE_LOCAL_FILE,
            summaryEngine = SummaryEngineType.NONE,
            operationToken = "file-token",
        )
        assertTrue(
            repository.advanceOperation(
                "main-recording-source",
                "file-token",
                setOf(OnDeviceSessionState.STARTING),
                OnDeviceSessionState.TRANSCRIBING,
            ),
        )

        val stored = requireNotNull(database.sessionDao().get("main-recording-source"))
        assertEquals(OnDeviceSessionEntity.SOURCE_TYPE_MAIN_RECORDER_CHUNK, stored.sourceType)
        assertEquals(SttEngineType.SENSEVOICE_LOCAL_FILE.name, stored.sttEngine)
        assertEquals("chunk-1", stored.sourceChunkId)
        assertEquals(null, stored.audioPath)

        assertEquals(1, repository.recoverInterrupted())
        val recovered = requireNotNull(database.sessionDao().get("main-recording-source"))
        assertEquals(OnDeviceSessionState.FAILED_RECOVERABLE.name, recovered.state)
        assertEquals(OnDeviceFailureStage.TRANSCRIBE.name, recovered.failureStage)
    }

    @Test
    fun staleTokenCannotOverwriteCurrentOperation() = runBlocking {
        val repository = OnDeviceRepository(database.sessionDao(), clock = { now++ })
        val id = "stale-token"
        repository.begin(
            id,
            SttEngineType.ANDROID_ON_DEVICE,
            SummaryEngineType.EXTRACTIVE_KOTLIN,
            OnDeviceSessionState.TRANSCRIPT_READY,
            null,
        )
        assertTrue(
            repository.startOperation(
                id,
                setOf(OnDeviceSessionState.TRANSCRIPT_READY),
                OnDeviceSessionState.SUMMARIZING,
                "new-token",
            ),
        )

        assertTrue(
            !repository.finishOperation(
                id,
                "old-token",
                OnDeviceSessionState.COMPLETE,
            ),
        )
        assertEquals(OnDeviceSessionState.SUMMARIZING.name, database.sessionDao().get(id)?.state)
        assertEquals("new-token", database.sessionDao().get(id)?.operationToken)
    }

    @Test
    fun qwenFallbackPersistsRequestedActualAndQualityMetadata() = runBlocking {
        val repository = OnDeviceRepository(database.sessionDao(), clock = { now++ })
        val id = "summary-audit"
        repository.begin(
            id,
            SttEngineType.SENSEVOICE_LOCAL_FILE,
            SummaryEngineType.QWEN_LOCAL,
            OnDeviceSessionState.TRANSCRIPT_READY,
            null,
        )
        assertTrue(
            repository.startOperation(
                id,
                setOf(OnDeviceSessionState.TRANSCRIPT_READY),
                OnDeviceSessionState.SUMMARIZING,
                "summary-audit-token",
            ),
        )

        assertTrue(
            repository.saveSummary(
                id = id,
                token = "summary-audit-token",
                requestedEngine = SummaryEngineType.QWEN_LOCAL,
                result = LocalSummary(
                    title = "쇼핑쇼츠 강의",
                    bullets = listOf("수강생 판매 경험을 원문에서 추출했습니다."),
                    actionItems = emptyList(),
                    engine = SummaryEngineType.EXTRACTIVE_KOTLIN,
                    sourceHash = "source-hash",
                    fallbackReason = "QWEN_QUALITY_REJECTED",
                    policyVersion = 2,
                    promptVersion = 2,
                    modelVersion = "qwen-test",
                    validationStatus = "FALLBACK_PASSED",
                    requestedModelId = "QWEN_SUMMARY_KO",
                    actualModelId = null,
                    runtimeType = "KOTLIN",
                    generationProfile = "qwen-greedy-json-v1",
                    violationCodes = "WEAK_SOURCE_EVIDENCE",
                    durationMs = 1_234,
                    inputChars = 556,
                    outputChars = 22,
                ),
            ),
        )

        val stored = requireNotNull(database.sessionDao().get(id))
        assertEquals(SummaryEngineType.QWEN_LOCAL.name, stored.requestedSummaryEngine)
        assertEquals(SummaryEngineType.EXTRACTIVE_KOTLIN.name, stored.summaryEngine)
        assertEquals("QWEN_QUALITY_REJECTED", stored.summaryFallbackReason)
        assertEquals(2, stored.summaryPolicyVersion)
        assertEquals(2, stored.summaryPromptVersion)
        assertEquals("qwen-test", stored.summaryModelVersion)
        assertEquals("FALLBACK_PASSED", stored.summaryValidationStatus)
        assertEquals("QWEN_SUMMARY_KO", stored.requestedSummaryModelId)
        assertEquals(null, stored.actualSummaryModelId)
        assertEquals("KOTLIN", stored.summaryRuntimeType)
        assertEquals("qwen-greedy-json-v1", stored.summaryGenerationProfile)
        assertEquals("WEAK_SOURCE_EVIDENCE", stored.summaryViolationCodes)
        assertEquals(1_234L, stored.summaryDurationMs)
        assertEquals(556, stored.summaryInputChars)
        assertEquals(22, stored.summaryOutputChars)
    }

    @Test
    fun cancelledSummaryReturnsToTranscriptReadyWithoutLosingTranscript() = runBlocking {
        val repository = OnDeviceRepository(database.sessionDao(), clock = { now++ })
        val id = repository.begin(
            SttEngineType.ANDROID_ON_DEVICE,
            SummaryEngineType.EXTRACTIVE_KOTLIN,
        )
        repository.saveTranscript(id, "취소 후에도 보존할 전사")
        assertTrue(
            repository.startOperation(
                id,
                setOf(OnDeviceSessionState.TRANSCRIPT_READY),
                OnDeviceSessionState.SUMMARIZING,
                "summary-cancel",
            ),
        )

        assertTrue(
            repository.finishOperation(
                id,
                "summary-cancel",
                OnDeviceSessionState.TRANSCRIPT_READY,
            ),
        )
        val session = database.sessionDao().get(id)!!
        assertEquals(OnDeviceSessionState.TRANSCRIPT_READY.name, session.state)
        assertEquals("취소 후에도 보존할 전사", session.transcript)
        assertEquals(null, session.operationToken)
    }

    @Test
    fun cancelledTranscriptionReturnsToAudioReadyWithSamePath() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioFiles = LocalAudioFileManager(context)
        val repository = OnDeviceRepository(database.sessionDao(), audioFiles) { now++ }
        val id = "transcribe-cancel"
        val wav = audioFiles.recordingFile(id)
        writeSilentWav(wav)
        repository.begin(
            id,
            SttEngineType.ANDROID_ON_DEVICE,
            SummaryEngineType.NONE,
            OnDeviceSessionState.AUDIO_READY,
            null,
        )
        repository.attachAudio(id, wav.absolutePath)
        assertTrue(
            repository.startOperation(
                id,
                setOf(OnDeviceSessionState.AUDIO_READY),
                OnDeviceSessionState.TRANSCRIBING,
                "transcribe-cancel-token",
            ),
        )

        assertTrue(
            repository.finishOperation(
                id,
                "transcribe-cancel-token",
                OnDeviceSessionState.AUDIO_READY,
            ),
        )
        val session = database.sessionDao().get(id)!!
        assertEquals(OnDeviceSessionState.AUDIO_READY.name, session.state)
        assertEquals(wav.absolutePath, session.audioPath)
    }

    @Test
    fun activeSessionDeleteDoesNotChangeDatabaseOrAudio() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioFiles = LocalAudioFileManager(context)
        val repository = OnDeviceRepository(database.sessionDao(), audioFiles) { now++ }
        val id = "active-delete"
        val wav = audioFiles.recordingFile(id).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        repository.begin(
            id,
            SttEngineType.ANDROID_ON_DEVICE,
            SummaryEngineType.NONE,
            OnDeviceSessionState.STARTING,
            "active-token",
        )
        assertTrue(repository.attachAudio(id, "active-token", wav.absolutePath))

        assertEquals(DeleteSessionResult.ActiveOrMissing, repository.delete(id))
        assertTrue(wav.exists())
        assertEquals(OnDeviceSessionState.STARTING.name, database.sessionDao().get(id)?.state)
    }

    @Test
    fun failedAudioDeleteKeepsRecoverableRow() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioFiles = LocalAudioFileManager(context)
        val repository = OnDeviceRepository(database.sessionDao(), audioFiles) { now++ }
        val id = "failed-delete"
        val nonEmptyDirectory = audioFiles.recordingFile(id).apply {
            mkdirs()
            File(this, "child").writeText("keep")
        }
        repository.begin(
            id,
            SttEngineType.ANDROID_ON_DEVICE,
            SummaryEngineType.NONE,
            OnDeviceSessionState.CANCELLED,
            null,
        )
        repository.attachAudio(id, nonEmptyDirectory.absolutePath)

        assertTrue(repository.delete(id) is DeleteSessionResult.FileDeleteFailed)
        val retained = database.sessionDao().get(id)!!
        assertEquals(OnDeviceSessionState.FAILED_RECOVERABLE.name, retained.state)
        assertEquals(OnDeviceFailureStage.DELETE.name, retained.failureStage)
    }

    private fun writeSilentWav(file: File) {
        val pcmBytes = 320
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { wav ->
            wav.writeBytes("RIFF")
            writeLeInt(wav, pcmBytes + 36)
            wav.writeBytes("WAVEfmt ")
            writeLeInt(wav, 16)
            writeLeShort(wav, 1)
            writeLeShort(wav, 1)
            writeLeInt(wav, 16_000)
            writeLeInt(wav, 32_000)
            writeLeShort(wav, 2)
            writeLeShort(wav, 16)
            wav.writeBytes("data")
            writeLeInt(wav, pcmBytes)
            wav.write(ByteArray(pcmBytes))
        }
    }

    private fun writeLeInt(file: RandomAccessFile, value: Int) {
        file.write(
            byteArrayOf(
                value.toByte(),
                (value ushr 8).toByte(),
                (value ushr 16).toByte(),
                (value ushr 24).toByte(),
            ),
        )
    }

    private fun writeLeShort(file: RandomAccessFile, value: Int) {
        file.write(byteArrayOf(value.toByte(), (value ushr 8).toByte()))
    }
}
