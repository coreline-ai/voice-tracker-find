package com.thinktank.recorder.ondevice.data

import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.api.OnDeviceFailureStage
import com.thinktank.recorder.ondevice.api.OnDeviceSessionState
import com.thinktank.recorder.ondevice.api.SttEngineType
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import com.thinktank.recorder.ondevice.recording.LocalAudioFileManager
import com.thinktank.recorder.ondevice.stt.Pcm16WavReader
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
                    if (hasUsableAudio) OnDeviceSessionState.AUDIO_READY
                    else OnDeviceSessionState.FAILED_RECOVERABLE
                OnDeviceSessionState.LISTENING ->
                    if (
                        session.sttEngine == SttEngineType.MOONSHINE_LOCAL.name &&
                        hasUsableAudio
                    ) {
                        OnDeviceSessionState.AUDIO_READY
                    } else {
                        OnDeviceSessionState.CANCELLED
                    }
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
            val discardInvalidCapture = session.sttEngine == SttEngineType.MOONSHINE_LOCAL.name &&
                !hasUsableAudio &&
                currentState in setOf(
                    OnDeviceSessionState.STARTING,
                    OnDeviceSessionState.LISTENING,
                    OnDeviceSessionState.TRANSCRIBING,
                    OnDeviceSessionState.CANCELLING,
            )
            if (discardInvalidCapture && transitioned == 1) {
                val path = session.audioPath ?: runCatching {
                    audioFiles?.recordingFile(session.id)?.absolutePath
                }.getOrNull()
                runCatching { audioFiles?.deleteRecording(path) }
                dao.clearAudio(session.id, clock())
            }
        }
        return recovered
    }

    suspend fun begin(
        sttEngine: SttEngineType,
        summaryEngine: SummaryEngineType,
    ): String {
        val id = UUID.randomUUID().toString()
        begin(
            id = id,
            sttEngine = sttEngine,
            summaryEngine = summaryEngine,
            state = OnDeviceSessionState.LISTENING,
            operationToken = null,
        )
        return id
    }

    suspend fun begin(
        id: String,
        sttEngine: SttEngineType,
        summaryEngine: SummaryEngineType,
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
                summaryEngine = summaryEngine.name,
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
        dao.saveTranscriptForOperation(id, token, transcript.trim(), clock()) == 1

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

    suspend fun markSummarizing(id: String) {
        updateState(id, OnDeviceSessionState.SUMMARIZING)
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

    suspend fun saveSummary(id: String, result: LocalSummary) {
        val current = requireNotNull(dao.get(id))
        dao.update(
            current.copy(
                updatedAt = clock(),
                state = OnDeviceSessionState.COMPLETE.name,
                title = result.title,
                summary = result.bullets.joinToString("\n"),
                actionItems = result.actionItems.joinToString("\n"),
                summaryEngine = result.engine.name,
                summarySourceHash = result.sourceHash,
                summaryGeneratedAt = clock(),
                error = null,
            ),
        )
    }

    suspend fun saveSummary(id: String, token: String, result: LocalSummary): Boolean {
        val now = clock()
        return dao.saveSummaryForOperation(
            id = id,
            token = token,
            title = result.title,
            summary = result.bullets.joinToString("\n"),
            actionItems = result.actionItems.joinToString("\n"),
            summaryEngine = result.engine.name,
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
}
