package com.thinktank.recorder.ondevice.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thinktank.recorder.ondevice.api.MainRecordingSource
import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.api.OnDeviceFailureStage
import com.thinktank.recorder.ondevice.api.OnDeviceSessionState
import com.thinktank.recorder.ondevice.api.SttEngineType
import com.thinktank.recorder.ondevice.api.SttDiagnostics
import com.thinktank.recorder.ondevice.api.SttQualityStatus
import com.thinktank.recorder.ondevice.api.SttResult
import com.thinktank.recorder.ondevice.api.SttSegmentDiagnostic
import com.thinktank.recorder.ondevice.api.TranscriptSegment
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
    fun sessionStaysLocalOnlyThroughTranscriptCompletion() = runBlocking {
        val repository = OnDeviceRepository(database.sessionDao()) { now++ }
        val id = repository.begin(SttEngineType.ANDROID_ON_DEVICE)

        repository.saveTranscript(id, "기기 안에서 처리합니다.")
        assertTrue(repository.completeWithoutSummary(id))

        val session = database.sessionDao().get(id)!!
        assertEquals(OnDeviceSessionEntity.DATA_POLICY_LOCAL_ONLY, session.dataPolicy)
        assertEquals(OnDeviceSessionState.COMPLETE.name, session.state)
        assertEquals("기기 안에서 처리합니다.", session.transcript)
        assertTrue(database.sessionDao().observeAll().first().any { it.id == id })
    }

    @Test
    fun gemmaSummaryCompletesTranscriptSessionWithAuditMetadata() = runBlocking {
        val repository = OnDeviceRepository(database.sessionDao(), clock = { now++ })
        val id = "gemma-summary"
        repository.begin(
            id = id,
            sttEngine = SttEngineType.SENSEVOICE_LOCAL_FILE,
            state = OnDeviceSessionState.TRANSCRIPT_READY,
            operationToken = null,
        )
        repository.saveTranscript(id, "Gemma가 요약할 전사 원문")
        assertTrue(
            repository.startOperation(
                id = id,
                allowedStates = setOf(OnDeviceSessionState.TRANSCRIPT_READY),
                targetState = OnDeviceSessionState.SUMMARIZING,
                token = "gemma-token",
            ),
        )
        assertTrue(
            repository.saveGemmaSummary(
                id = id,
                token = "gemma-token",
                result = LocalSummary(
                    title = "Gemma 요약",
                    bullets = listOf("핵심 내용"),
                    actionItems = listOf("확인하기"),
                    sourceHash = "hash",
                    modelVersion = "3-1B-IT-int4-litertlm",
                    validationStatus = "PASSED",
                    requestedModelId = "GEMMA_SUMMARY_KO",
                    actualModelId = "GEMMA_SUMMARY_KO",
                    runtimeType = "LITERT_LM",
                    generationProfile = "test",
                    durationMs = 10,
                    inputChars = 20,
                    outputChars = 30,
                ),
            ),
        )

        val stored = requireNotNull(database.sessionDao().get(id))
        assertEquals(OnDeviceSessionState.COMPLETE.name, stored.state)
        assertEquals("GEMMA_LOCAL", stored.summaryEngine)
        assertEquals("Gemma 요약", stored.title)
        assertEquals("핵심 내용", stored.summary)
        assertEquals("확인하기", stored.actionItems)
        assertEquals("GEMMA_SUMMARY_KO", stored.actualSummaryModelId)
    }

    @Test
    fun interruptedLiveRecognitionBecomesCancelled() = runBlocking {
        val repository = OnDeviceRepository(database.sessionDao()) { now++ }
        val id = repository.begin(SttEngineType.ANDROID_ON_DEVICE)

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
    fun interruptedLegacySummaryStatePreservesTranscript() = runBlocking {
        val repository = OnDeviceRepository(database.sessionDao(), clock = { now++ })
        val id = "recovery-summary"
        repository.begin(
            id,
            SttEngineType.ANDROID_ON_DEVICE,
            OnDeviceSessionState.AUDIO_READY,
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
    fun passingFileTranscriptPersistsCoverageDiagnostics() = runBlocking {
        val repository = OnDeviceRepository(database.sessionDao(), clock = { now++ })
        repository.begin(
            id = "quality-pass",
            sttEngine = SttEngineType.SENSEVOICE_LOCAL_FILE,
            state = OnDeviceSessionState.TRANSCRIBING,
            operationToken = "quality-pass-token",
        )
        val diagnostics = SttDiagnostics(
            inputDurationMs = 45_184L,
            processedThroughMs = 45_184L,
            segmentCount = 2,
            recognizedSegmentCount = 2,
            retryCount = 1,
            meaningfulChars = 305,
            charsPerSecond = 6.75f,
            qualityStatus = SttQualityStatus.RETRIED_COMPLETE,
            segments = listOf(
                SttSegmentDiagnostic(0L, 28_000L, 190),
                SttSegmentDiagnostic(27_000L, 45_184L, 130),
            ),
        )

        assertTrue(
            repository.saveTranscript(
                "quality-pass",
                "quality-pass-token",
                SttResult(
                    text = "끝까지 처리한 전사",
                    segments = listOf(TranscriptSegment(0L, 45_184L, "끝까지 처리한 전사")),
                    diagnostics = diagnostics,
                ),
            ),
        )

        val stored = requireNotNull(database.sessionDao().get("quality-pass"))
        assertEquals(OnDeviceSessionState.TRANSCRIPT_READY.name, stored.state)
        assertEquals(SttQualityStatus.RETRIED_COMPLETE.name, stored.sttQualityStatus)
        assertEquals(45_184L, stored.sttProcessedThroughMs)
        assertEquals(1, stored.sttRetryCount)
        assertEquals("0-28000:190;27000-45184:130", stored.sttSegmentDiagnostics)
    }

    @Test
    fun insufficientFileTranscriptStoresDiagnosticsWithoutResult() = runBlocking {
        val repository = OnDeviceRepository(database.sessionDao(), clock = { now++ })
        repository.begin(
            id = "quality-fail",
            sttEngine = SttEngineType.SENSEVOICE_LOCAL_FILE,
            state = OnDeviceSessionState.TRANSCRIBING,
            operationToken = "quality-fail-token",
        )
        val diagnostics = SttDiagnostics(
            inputDurationMs = 45_184L,
            processedThroughMs = 45_184L,
            segmentCount = 2,
            recognizedSegmentCount = 1,
            retryCount = 1,
            meaningfulChars = 1,
            charsPerSecond = 0.02f,
            qualityStatus = SttQualityStatus.INSUFFICIENT,
        )

        assertTrue(
            repository.finishTranscriptQualityFailure(
                id = "quality-fail",
                token = "quality-fail-token",
                diagnostics = diagnostics,
                error = "전사 품질 부족",
            ),
        )

        val stored = requireNotNull(database.sessionDao().get("quality-fail"))
        assertEquals(OnDeviceSessionState.FAILED_RECOVERABLE.name, stored.state)
        assertEquals(OnDeviceFailureStage.TRANSCRIBE.name, stored.failureStage)
        assertEquals("", stored.transcript)
        assertEquals("", stored.summary)
        assertEquals(SttQualityStatus.INSUFFICIENT.name, stored.sttQualityStatus)
    }

    @Test
    fun staleTokenCannotOverwriteCurrentOperation() = runBlocking {
        val repository = OnDeviceRepository(database.sessionDao(), clock = { now++ })
        val id = "stale-token"
        repository.begin(
            id,
            SttEngineType.ANDROID_ON_DEVICE,
            OnDeviceSessionState.AUDIO_READY,
            null,
        )
        assertTrue(
            repository.startOperation(
                id,
                setOf(OnDeviceSessionState.AUDIO_READY),
                OnDeviceSessionState.TRANSCRIBING,
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
        assertEquals(OnDeviceSessionState.TRANSCRIBING.name, database.sessionDao().get(id)?.state)
        assertEquals("new-token", database.sessionDao().get(id)?.operationToken)
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
