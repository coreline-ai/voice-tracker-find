package com.thinktank.recorder.ondevice.data

import com.thinktank.recorder.ondevice.api.OnDeviceFailureStage
import com.thinktank.recorder.ondevice.api.OnDeviceSessionState
import com.thinktank.recorder.ondevice.api.SttEngineType
import com.thinktank.recorder.ondevice.api.SttDiagnostics
import com.thinktank.recorder.ondevice.api.SttResult
import com.thinktank.recorder.ondevice.api.MainRecordingSource
import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.recording.LocalAudioFileManager
import com.thinktank.recorder.ondevice.stt.Pcm16WavReader
import com.thinktank.recorder.ondevice.stt.SttRecognitionQualityEvaluator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow

sealed interface DeleteSessionResult {
    data object Deleted : DeleteSessionResult
    data object ActiveOrMissing : DeleteSessionResult
    data class FileDeleteFailed(val message: String) : DeleteSessionResult
}

class OnDeviceRepository(
    private val dao: OnDeviceSessionDao,
    private val audioFiles: LocalAudioFileManager? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val recognitionQualityEvaluator = SttRecognitionQualityEvaluator()
    val sessions: Flow<List<OnDeviceSessionEntity>> = dao.observeAll()

    suspend fun recoverInterrupted(): Int {
        var recovered = 0
        dao.interrupted().forEach { session ->
            val currentState = runCatching { OnDeviceSessionState.valueOf(session.state) }
                .getOrDefault(OnDeviceSessionState.FAILED_RECOVERABLE)
            val hasTranscript = session.transcript.isNotBlank()
            val hasUsableAudio = isUsableAudio(session.audioPath)
            val target = when (currentState) {
                OnDeviceSessionState.SUMMARIZING ->
                    if (hasTranscript) OnDeviceSessionState.TRANSCRIPT_READY
                    else OnDeviceSessionState.FAILED_RECOVERABLE
                OnDeviceSessionState.TRANSCRIBING ->
                    if (session.sourceType == OnDeviceSessionEntity.SOURCE_TYPE_MAIN_RECORDER_CHUNK) {
                        OnDeviceSessionState.FAILED_RECOVERABLE
                    } else if (hasUsableAudio) OnDeviceSessionState.AUDIO_READY
                    else OnDeviceSessionState.FAILED_RECOVERABLE
                OnDeviceSessionState.LISTENING -> OnDeviceSessionState.CANCELLED
                OnDeviceSessionState.DELETING -> OnDeviceSessionState.FAILED_RECOVERABLE
                else -> OnDeviceSessionState.CANCELLED
            }
            val stage = when {
                currentState == OnDeviceSessionState.DELETING -> OnDeviceFailureStage.DELETE
                target == OnDeviceSessionState.AUDIO_READY -> null
                target == OnDeviceSessionState.TRANSCRIPT_READY -> null
                currentState == OnDeviceSessionState.TRANSCRIBING -> OnDeviceFailureStage.TRANSCRIBE
                currentState == OnDeviceSessionState.SUMMARIZING -> OnDeviceFailureStage.SUMMARIZE
                else -> OnDeviceFailureStage.CAPTURE
            }
            val error = if (
                target in setOf(
                    OnDeviceSessionState.AUDIO_READY,
                    OnDeviceSessionState.TRANSCRIPT_READY,
                    OnDeviceSessionState.CANCELLED,
                )
            ) {
                null
            } else {
                "앱이 종료되어 작업이 중단되었습니다. 다시 시도할 수 있습니다."
            }
            val transitioned = dao.recover(
                id = session.id,
                expectedState = session.state,
                expectedToken = session.operationToken,
                targetState = target.name,
                now = clock(),
                failureStage = stage?.name,
                error = error,
            )
            recovered += transitioned
        }
        return recovered
    }

    suspend fun begin(
        sttEngine: SttEngineType,
    ): String {
        val id = UUID.randomUUID().toString()
        begin(
            id = id,
            sttEngine = sttEngine,
            state = OnDeviceSessionState.LISTENING,
            operationToken = null,
        )
        return id
    }

    suspend fun begin(
        id: String,
        sttEngine: SttEngineType,
        state: OnDeviceSessionState,
        operationToken: String?,
    ) {
        val now = clock()
        dao.insert(
            OnDeviceSessionEntity(
                id = id,
                createdAt = now,
                updatedAt = now,
                state = state.name,
                sttEngine = sttEngine.name,
                summaryEngine = SUMMARY_ENGINE_DEFAULT,
                requestedSummaryEngine = SUMMARY_ENGINE_DEFAULT,
                operationToken = operationToken,
            ),
        )
    }

    suspend fun beginFromMainRecording(
        id: String,
        source: MainRecordingSource,
        sttEngine: SttEngineType,
        operationToken: String,
    ) {
        val now = clock()
        dao.insert(
            OnDeviceSessionEntity(
                id = id,
                createdAt = now,
                updatedAt = now,
                state = OnDeviceSessionState.STARTING.name,
                sttEngine = sttEngine.name,
                summaryEngine = SUMMARY_ENGINE_DEFAULT,
                requestedSummaryEngine = SUMMARY_ENGINE_DEFAULT,
                sourceType = OnDeviceSessionEntity.SOURCE_TYPE_MAIN_RECORDER_CHUNK,
                sourceChunkId = source.id,
                sourceDisplayName = "${source.extension.uppercase()} · ${source.durationMs / 1_000}초",
                sourceDurationMs = source.durationMs,
                operationToken = operationToken,
            ),
        )
    }

    suspend fun get(id: String): OnDeviceSessionEntity? = dao.get(id)

    suspend fun startOperation(
        id: String,
        allowedStates: Set<OnDeviceSessionState>,
        targetState: OnDeviceSessionState,
        token: String,
    ): Boolean = dao.startOperation(
        id = id,
        allowedStates = allowedStates.map(OnDeviceSessionState::name),
        targetState = targetState.name,
        token = token,
        now = clock(),
    ) == 1

    suspend fun advanceOperation(
        id: String,
        token: String,
        allowedStates: Set<OnDeviceSessionState>,
        targetState: OnDeviceSessionState,
    ): Boolean = dao.advanceOperation(
        id = id,
        token = token,
        allowedStates = allowedStates.map(OnDeviceSessionState::name),
        targetState = targetState.name,
        now = clock(),
    ) == 1

    suspend fun saveTranscript(id: String, transcript: String) {
        val current = requireNotNull(dao.get(id))
        dao.update(
            current.copy(
                updatedAt = clock(),
                state = OnDeviceSessionState.TRANSCRIPT_READY.name,
                transcript = transcript.trim(),
                error = null,
            ),
        )
    }

    suspend fun saveTranscript(id: String, token: String, transcript: String): Boolean =
        dao.saveTranscriptForOperation(
            id = id,
            token = token,
            transcript = transcript.trim(),
            inputDurationMs = null,
            processedThroughMs = null,
            segmentCount = null,
            recognizedSegmentCount = null,
            retryCount = null,
            meaningfulChars = null,
            charsPerSecond = null,
            qualityStatus = null,
            segmentDiagnostics = null,
            coverageStatus = null,
            recognitionQualityStatus = null,
            recognitionDiagnostics = null,
            now = clock(),
        ) == 1

    suspend fun saveTranscript(id: String, token: String, result: SttResult): Boolean {
        val diagnostics = requireNotNull(result.diagnostics) {
            "파일 STT 결과에 처리 범위 진단이 없습니다."
        }
        require(diagnostics.passed) { "품질 기준을 통과하지 못한 전사는 저장할 수 없습니다." }
        val recognition = recognitionQualityEvaluator.evaluate(result.text, diagnostics)
        return dao.saveTranscriptForOperation(
            id = id,
            token = token,
            transcript = result.text.trim(),
            inputDurationMs = diagnostics.inputDurationMs,
            processedThroughMs = diagnostics.processedThroughMs,
            segmentCount = diagnostics.segmentCount,
            recognizedSegmentCount = diagnostics.recognizedSegmentCount,
            retryCount = diagnostics.retryCount,
            meaningfulChars = diagnostics.meaningfulChars,
            charsPerSecond = diagnostics.charsPerSecond,
            qualityStatus = diagnostics.qualityStatus.name,
            segmentDiagnostics = diagnostics.encodedSegments(),
            coverageStatus = recognition.coverage.name,
            recognitionQualityStatus = recognition.recognitionQuality.name,
            recognitionDiagnostics = recognition.encodedDiagnostics,
            now = clock(),
        ) == 1
    }

    suspend fun finishTranscriptQualityFailure(
        id: String,
        token: String,
        diagnostics: SttDiagnostics,
        error: String,
        transcript: String = "",
    ): Boolean {
        require(!diagnostics.passed) { "통과한 전사를 품질 실패로 저장할 수 없습니다." }
        val recognition = recognitionQualityEvaluator.evaluate(transcript, diagnostics)
        return dao.finishTranscriptQualityFailureForOperation(
            id = id,
            token = token,
            inputDurationMs = diagnostics.inputDurationMs,
            processedThroughMs = diagnostics.processedThroughMs,
            segmentCount = diagnostics.segmentCount,
            recognizedSegmentCount = diagnostics.recognizedSegmentCount,
            retryCount = diagnostics.retryCount,
            meaningfulChars = diagnostics.meaningfulChars,
            charsPerSecond = diagnostics.charsPerSecond,
            qualityStatus = diagnostics.qualityStatus.name,
            segmentDiagnostics = diagnostics.encodedSegments(),
            coverageStatus = recognition.coverage.name,
            recognitionQualityStatus = recognition.recognitionQuality.name,
            recognitionDiagnostics = recognition.encodedDiagnostics,
            error = error,
            now = clock(),
        ) == 1
    }

    suspend fun attachAudio(id: String, audioPath: String) {
        val current = requireNotNull(dao.get(id))
        dao.update(
            current.copy(
                updatedAt = clock(),
                audioPath = audioPath,
                error = null,
            ),
        )
    }

    suspend fun attachAudio(id: String, token: String, audioPath: String): Boolean =
        dao.attachAudioForOperation(id, token, audioPath, clock()) == 1

    suspend fun markTranscribing(id: String) {
        updateState(id, OnDeviceSessionState.TRANSCRIBING)
    }

    suspend fun finishOperation(
        id: String,
        token: String,
        targetState: OnDeviceSessionState,
        failureStage: OnDeviceFailureStage? = null,
        error: String? = null,
    ): Boolean = dao.finishOperation(
        id = id,
        token = token,
        targetState = targetState.name,
        now = clock(),
        failureStage = failureStage?.name,
        error = error,
    ) == 1

    suspend fun finishCaptureWithoutAudio(
        id: String,
        token: String,
        targetState: OnDeviceSessionState,
        failureStage: OnDeviceFailureStage? = null,
        error: String? = null,
    ): Boolean = dao.finishCaptureWithoutAudio(
        id = id,
        token = token,
        targetState = targetState.name,
        now = clock(),
        failureStage = failureStage?.name,
        error = error,
    ) == 1

    suspend fun saveGemmaSummary(id: String, token: String, result: LocalSummary): Boolean {
        val now = clock()
        return dao.saveGemmaSummaryForOperation(
            id = id,
            token = token,
            title = result.title,
            summary = result.bullets.joinToString("\n"),
            actionItems = result.actionItems.joinToString("\n"),
            modelVersion = result.modelVersion,
            validationStatus = result.validationStatus,
            requestedModelId = result.requestedModelId,
            actualModelId = result.actualModelId,
            runtimeType = result.runtimeType,
            generationProfile = result.generationProfile,
            durationMs = result.durationMs,
            inputChars = result.inputChars,
            outputChars = result.outputChars,
            sourceHash = result.sourceHash,
            generatedAt = now,
            now = now,
        ) == 1
    }

    suspend fun completeWithoutSummary(id: String): Boolean =
        dao.completeWithoutSummary(id, clock()) == 1

    suspend fun fail(id: String, message: String, recoverable: Boolean = true) {
        val current = dao.get(id) ?: return
        dao.update(
            current.copy(
                updatedAt = clock(),
                state = if (recoverable) {
                    OnDeviceSessionState.FAILED_RECOVERABLE.name
                } else {
                    OnDeviceSessionState.FAILED_PERMANENT.name
                },
                error = message,
            ),
        )
    }

    suspend fun cancel(id: String) {
        updateState(id, OnDeviceSessionState.CANCELLED)
    }

    suspend fun delete(id: String): DeleteSessionResult {
        val current = dao.get(id) ?: return DeleteSessionResult.ActiveOrMissing
        if (dao.markDeleting(id, clock()) != 1) return DeleteSessionResult.ActiveOrMissing
        if (!current.audioPath.isNullOrBlank()) {
            val deleted = runCatching {
                audioFiles?.deleteRecording(current.audioPath) ?: false
            }.getOrDefault(false)
            if (!deleted) {
                val message = "오디오 파일을 삭제하지 못했습니다. 다시 시도하세요."
                dao.recover(
                    id = id,
                    expectedState = OnDeviceSessionState.DELETING.name,
                    expectedToken = null,
                    targetState = OnDeviceSessionState.FAILED_RECOVERABLE.name,
                    now = clock(),
                    failureStage = OnDeviceFailureStage.DELETE.name,
                    error = message,
                )
                return DeleteSessionResult.FileDeleteFailed(message)
            }
        }
        dao.delete(id)
        return DeleteSessionResult.Deleted
    }

    private suspend fun updateState(id: String, state: OnDeviceSessionState) {
        val current = dao.get(id) ?: return
        dao.update(
            current.copy(
                updatedAt = clock(),
                state = state.name,
                error = null,
            ),
        )
    }

    private fun isUsableAudio(path: String?): Boolean {
        val file = path?.takeIf(String::isNotBlank)?.let(::File) ?: return false
        return runCatching {
            file.isFile &&
                file.length() > 44 &&
                Pcm16WavReader.inspect(file).durationMs > 0
        }.getOrDefault(false)
    }

    private companion object {
        const val SUMMARY_ENGINE_DEFAULT = "GEMMA_LOCAL"
    }
}

private fun SttDiagnostics.encodedSegments(): String =
    segments.joinToString(separator = ";") { segment ->
        "${segment.startMs}-${segment.endMs}:${segment.meaningfulChars}"
    }
