package com.thinktank.recorder.ondevice.ui

import android.app.Application
import android.net.Uri
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.thinktank.recorder.ondevice.api.MainRecordingSource
import com.thinktank.recorder.ondevice.api.MainRecordingSourceGateway
import com.thinktank.recorder.ondevice.api.OnDeviceFailureStage
import com.thinktank.recorder.ondevice.api.OnDeviceOperationKind
import com.thinktank.recorder.ondevice.api.OnDeviceSessionState
import com.thinktank.recorder.ondevice.api.SpeechEvent
import com.thinktank.recorder.ondevice.api.SttCaptureProfile
import com.thinktank.recorder.ondevice.api.SttEngineType
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import com.thinktank.recorder.ondevice.audio.AndroidPcmNormalizer
import com.thinktank.recorder.ondevice.data.DeleteSessionResult
import com.thinktank.recorder.ondevice.data.OnDeviceDatabase
import com.thinktank.recorder.ondevice.data.OnDeviceRepository
import com.thinktank.recorder.ondevice.data.OnDeviceSessionEntity
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import com.thinktank.recorder.ondevice.modelpack.ModelDescriptor
import com.thinktank.recorder.ondevice.modelpack.ModelDownloadManager
import com.thinktank.recorder.ondevice.modelpack.ModelDownloadWorker
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.modelpack.ModelStore
import com.thinktank.recorder.ondevice.recording.LocalAudioFileManager
import com.thinktank.recorder.ondevice.runtime.ActiveOperation
import com.thinktank.recorder.ondevice.runtime.MicrophoneArbiter
import com.thinktank.recorder.ondevice.runtime.MicrophoneOwner
import com.thinktank.recorder.ondevice.runtime.NativeWorkload
import com.thinktank.recorder.ondevice.runtime.NativeRuntimeCapabilities
import com.thinktank.recorder.ondevice.runtime.OnDeviceOperationCoordinator
import com.thinktank.recorder.ondevice.runtime.ResourceArbiter
import com.thinktank.recorder.ondevice.stt.AndroidOnDeviceSpeechEngine
import com.thinktank.recorder.ondevice.stt.SenseVoiceFileSpeechEngine
import com.thinktank.recorder.ondevice.stt.SenseVoiceFileSttAvailability
import com.thinktank.recorder.ondevice.stt.SpeechRecognitionException
import com.thinktank.recorder.ondevice.summary.ExtractiveSummaryEngine
import com.thinktank.recorder.ondevice.summary.LocalSummaryCompactor
import com.thinktank.recorder.ondevice.summary.QwenSummaryEngine
import java.util.UUID
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

enum class ModelUiStatus {
    NOT_INSTALLED,
    WAITING_FOR_WIFI,
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    READY,
    PAUSED,
    FAILED,
}

private fun SummaryEngineType.usesQwen(): Boolean =
    this == SummaryEngineType.QWEN_LOCAL || this == SummaryEngineType.QWEN_LOCAL_GROUNDED

data class ModelUiState(
    val descriptor: ModelDescriptor,
    val status: ModelUiStatus,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = descriptor.approximateDownloadBytes,
    val installedBytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val etaSeconds: Long = -1,
    val error: String? = null,
) {
    val progress: Float
        get() = if (totalBytes <= 0) 0f else {
            (downloadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        }
}

data class OnDeviceUiState(
    val sessions: List<OnDeviceSessionEntity> = emptyList(),
    val mainRecordingSources: List<MainRecordingSource> = emptyList(),
    val selectedMainRecordingId: String? = null,
    val fileSttAvailability: SenseVoiceFileSttAvailability =
        SenseVoiceFileSttAvailability.MODEL_NOT_INSTALLED,
    val selectedSttProfile: SttCaptureProfile = SttCaptureProfile.BALANCED,
    val selectedSummary: SummaryEngineType = SummaryEngineType.EXTRACTIVE_KOTLIN,
    val systemSttAvailable: Boolean = false,
    val nativeAiAvailable: Boolean = false,
    val nativeAiUnavailableReason: String? = null,
    val listening: Boolean = false,
    val fileTranscribing: Boolean = false,
    val processing: Boolean = false,
    val processingLabel: String? = null,
    val processingProgress: Float? = null,
    val liveTranscript: String = "",
    val partialTranscript: String = "",
    val activeSessionId: String? = null,
    val message: String? = null,
    val models: List<ModelUiState> = emptyList(),
) {
    val micBusy: Boolean
        get() = listening || fileTranscribing
}

@HiltViewModel
class OnDeviceViewModel @Inject constructor(
    application: Application,
    private val mainRecordingSources: MainRecordingSourceGateway,
) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES, 0)
    private val audioFiles = LocalAudioFileManager(application)
    private val repository = OnDeviceRepository(
        dao = OnDeviceDatabase.get(application).sessionDao(),
        audioFiles = audioFiles,
    )
    private val modelStore = ModelStore(application)
    private val speechEngine = AndroidOnDeviceSpeechEngine(application)
    private val fileSpeechEngine = SenseVoiceFileSpeechEngine(application, modelStore)
    private val pcmNormalizer = AndroidPcmNormalizer()
    private val extractiveSummary = ExtractiveSummaryEngine()
    private val summaryCompactor = LocalSummaryCompactor()
    private val qwenDelegate = lazy { QwenSummaryEngine(application, modelStore) }
    private val qwen by qwenDelegate
    private val modelManager = ModelDownloadManager(application)
    private val coordinator = OnDeviceOperationCoordinator()
    private val nativeCapability = NativeRuntimeCapabilities.current()

    private val selectedSummary = MutableStateFlow(
        preferences.getString(KEY_SUMMARY, null)
            ?.let { runCatching { SummaryEngineType.valueOf(it) }.getOrNull() }
            ?.let { if (it == SummaryEngineType.QWEN_LOCAL_GROUNDED) SummaryEngineType.QWEN_LOCAL else it }
            ?.takeUnless {
                it.usesQwen() && !nativeCapability.supported
            }
            ?: SummaryEngineType.EXTRACTIVE_KOTLIN,
    )
    private val selectedSttProfile = MutableStateFlow(
        preferences.getString(KEY_STT_PROFILE, null)
            ?.let { runCatching { SttCaptureProfile.valueOf(it) }.getOrNull() }
            ?: SttCaptureProfile.BALANCED,
    )
    private val selectedMainRecordingId = MutableStateFlow<String?>(null)
    private val listening = MutableStateFlow(false)
    private val fileTranscribing = MutableStateFlow(false)
    private val processing = MutableStateFlow(false)
    private val processingLabel = MutableStateFlow<String?>(null)
    private val processingProgress = MutableStateFlow<Float?>(null)
    private val liveTranscript = MutableStateFlow("")
    private val partialTranscript = MutableStateFlow("")
    private val stopRequested = MutableStateFlow(false)
    private val activeSessionId = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)
    private val refreshModels = MutableStateFlow(0)

    init {
        // Older builds may have persisted the retired system file-input policy.
        preferences.edit().remove(KEY_STT).remove(KEY_FILE_STT_VALIDATED).apply()
    }

    private val recoveryJob: Job = viewModelScope.launch(Dispatchers.IO) {
        repository.recoverInterrupted()
        modelManager.recoverInterruptedInstalls()
        // A build/app replacement can stop extraction after the archive is fully downloaded.
        // Resume the verified local install automatically; no Wi-Fi or re-download is involved.
        modelManager.resumeCompletedDownloadsLocally()
        refreshModels.value += 1
    }

    private val captureState = combine(
        listening,
        fileTranscribing,
        liveTranscript,
        partialTranscript,
        activeSessionId,
    ) { isListening, isFileTranscribing, completed, partial, sessionId ->
        CaptureState(isListening, isFileTranscribing, completed, partial, sessionId)
    }
    private val operationState = combine(
        message,
        processing,
        processingLabel,
        processingProgress,
    ) { text, isProcessing, label, progress ->
        OperationState(text, isProcessing, label, progress)
    }
    private val activeState = combine(captureState, operationState) { capture, operation ->
        ActiveState(capture, operation)
    }
    private val engineSelection = combine(selectedSttProfile, selectedSummary) { sttProfile, summary ->
        EngineSelection(sttProfile, summary)
    }
    private val sessionAndSources = combine(repository.sessions, mainRecordingSources.sources) {
            sessions,
            sources,
        ->
        SessionsAndSources(sessions, sources)
    }
    private val fileSelection = selectedMainRecordingId
        .let { selected ->
            combine(selected, refreshModels) { sourceId, _ ->
                FileSelection(sourceId, fileSpeechEngine.availability())
            }
        }
    private val localAiSelection = combine(engineSelection, fileSelection) { engine, file ->
        LocalAiSelection(engine, file)
    }

    val uiState: StateFlow<OnDeviceUiState> = combine(
        sessionAndSources,
        localAiSelection,
        activeState,
        modelManager.workInfos,
        refreshModels,
    ) { sessionSources, selection, active, workInfos, _ ->
        OnDeviceUiState(
            sessions = sessionSources.sessions,
            mainRecordingSources = sessionSources.sources,
            selectedMainRecordingId = selection.file.sourceId,
            fileSttAvailability = fileSpeechEngine.availability(),
            selectedSttProfile = selection.engine.sttProfile,
            selectedSummary = selection.engine.summary,
            systemSttAvailable = speechEngine.isAvailable(),
            nativeAiAvailable = nativeCapability.supported,
            nativeAiUnavailableReason = nativeCapability.reason,
            listening = active.capture.listening,
            fileTranscribing = active.capture.fileTranscribing,
            processing = active.operation.processing,
            processingLabel = active.operation.label,
            processingProgress = active.operation.progress,
            liveTranscript = active.capture.completed,
            partialTranscript = active.capture.partial,
            activeSessionId = active.capture.sessionId,
            message = active.operation.message,
            models = ModelCatalog.userManagedModels.map { descriptor ->
                descriptor.toUiState(workInfos)
            },
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        OnDeviceUiState(
            systemSttAvailable = speechEngine.isAvailable(),
            nativeAiAvailable = nativeCapability.supported,
            nativeAiUnavailableReason = nativeCapability.reason,
            models = ModelCatalog.userManagedModels.map { descriptor ->
                val installed = modelStore.snapshot(descriptor)
                ModelUiState(
                    descriptor = descriptor,
                    status = if (installed.ready) ModelUiStatus.READY else ModelUiStatus.NOT_INSTALLED,
                    installedBytes = installed.installedBytes,
                )
            },
        ),
    )

    fun selectSummary(engine: SummaryEngineType) {
        if (coordinator.active.value != null) return
        if (engine.usesQwen() && !requireNativeCapability()) return
        selectedSummary.value = engine
        preferences.edit().putString(KEY_SUMMARY, engine.name).apply()
    }

    fun selectSttProfile(profile: SttCaptureProfile) {
        if (coordinator.active.value != null) return
        selectedSttProfile.value = profile
        preferences.edit().putString(KEY_STT_PROFILE, profile.name).apply()
    }

    fun selectMainRecording(sourceId: String) {
        if (coordinator.active.value != null) return
        selectedMainRecordingId.value = sourceId
    }

    fun transcribeSelectedRecording() {
        if (coordinator.active.value != null) return
        when (fileSpeechEngine.availability()) {
            SenseVoiceFileSttAvailability.READY -> Unit
            SenseVoiceFileSttAvailability.NATIVE_UNSUPPORTED -> {
                message.value = nativeCapability.reason ?: "이 기기는 SenseVoice 파일 STT를 지원하지 않습니다."
                return
            }
            SenseVoiceFileSttAvailability.MODEL_NOT_INSTALLED -> {
                message.value = "SenseVoice 한국어 파일 STT 모델을 먼저 Wi-Fi에서 설치하세요."
                return
            }
        }
        val source = uiState.value.mainRecordingSources.firstOrNull {
            it.id == selectedMainRecordingId.value
        }
        if (source == null) {
            message.value = "1번 탭에서 분석할 완료 녹음을 먼저 선택하세요."
            return
        }
        startFileTranscription(source)
    }

    fun startListening() {
        if (coordinator.active.value != null) return
        startSystemRecognition()
    }

    fun stopListening() {
        if (coordinator.active.value?.kind == OnDeviceOperationKind.LIVE_STT) {
            stopRequested.value = true
            message.value = "현재 문장을 확정한 뒤 전사를 마칩니다."
            speechEngine.stop()
        }
    }

    fun cancelListening() {
        val active = coordinator.active.value ?: return
        when (active.kind) {
            OnDeviceOperationKind.LIVE_STT -> speechEngine.cancel()
            OnDeviceOperationKind.FILE_STT -> fileSpeechEngine.cancel()
            OnDeviceOperationKind.KOTLIN_SUMMARY,
            OnDeviceOperationKind.QWEN_SUMMARY,
            -> Unit
        }
        stopRequested.value = true
        coordinator.cancelActive()
        message.value = "작업을 안전하게 취소하고 있습니다."
    }

    fun onHostStopped() {
        cancelListening()
    }

    fun summarize(sessionId: String) {
        val session = uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
        if (session.failureStage == OnDeviceFailureStage.DELETE.name) return
        if (session.transcript.isBlank()) return
        startSummary(sessionId, session.transcript, selectedSummary.value)
    }

    fun deleteSession(sessionId: String) {
        if (coordinator.isActive(sessionId)) {
            message.value = "실행 중인 작업을 먼저 취소한 뒤 삭제하세요."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { repository.delete(sessionId) }
                .getOrElse { DeleteSessionResult.FileDeleteFailed(it.message ?: "삭제 실패") }
            withContext(Dispatchers.Main) {
                message.value = when (result) {
                    DeleteSessionResult.Deleted -> "로컬 기록을 삭제했습니다."
                    DeleteSessionResult.ActiveOrMissing ->
                        "실행 중이거나 이미 삭제된 기록은 삭제할 수 없습니다."
                    is DeleteSessionResult.FileDeleteFailed -> result.message
                }
            }
        }
    }

    fun downloadModel(id: ModelId) {
        if (!requireNativeCapability()) return
        message.value = "Wi-Fi에서 모델 파일만 내려받습니다. 음성·전사·요약은 전송하지 않습니다."
        runCatching { modelManager.download(id) }
            .onFailure { message.value = it.message }
    }

    fun importModel(id: ModelId, uri: Uri) {
        if (!requireNativeCapability()) return
        message.value = "선택한 로컬 모델 파일을 검증합니다."
        runCatching { modelManager.import(id, uri) }
            .onFailure { message.value = it.message }
    }

    fun pauseModel(id: ModelId) {
        viewModelScope.launch {
            modelManager.pause(id)
            refreshModels.value += 1
            message.value = "모델 다운로드를 일시정지했습니다. 다시 누르면 이어받습니다."
        }
    }

    fun deleteModel(id: ModelId) {
        message.value = if (ResourceArbiter.activeWorkload() == null) {
            "모델을 삭제하고 있습니다."
        } else {
            "실행 중인 로컬 AI 작업이 끝난 뒤 모델을 삭제합니다."
        }
        viewModelScope.launch(Dispatchers.IO) {
            ResourceArbiter.withLease(NativeWorkload.MODEL_MAINTENANCE) {
                modelManager.delete(id)
            }
            refreshModels.value += 1
            message.value = "모델을 이 기기에서 삭제했습니다."
        }
    }

    fun clearMessage() {
        message.value = null
    }

    override fun onCleared() {
        coordinator.cancelActive()
        speechEngine.release()
        fileSpeechEngine.release()
        MicrophoneArbiter.release(MicrophoneOwner.LOCAL_AI)
        super.onCleared()
    }

    private fun startSystemRecognition() {
        if (!speechEngine.isAvailable()) {
            message.value = "이 기기에서 시스템 온디바이스 한국어 STT를 사용할 수 없습니다."
            return
        }
        val operation = reserve(UUID.randomUUID().toString(), OnDeviceOperationKind.LIVE_STT)
            ?: return
        if (!MicrophoneArbiter.tryAcquire(MicrophoneOwner.LOCAL_AI)) {
            coordinator.finish(operation.token)
            activeSessionId.value = null
            message.value = "기본 녹음이 마이크를 사용 중입니다. 녹음을 마친 뒤 다시 시도하세요."
            return
        }
        val summaryEngine = selectedSummary.value
        val captureProfile = selectedSttProfile.value
        listening.value = true
        liveTranscript.value = ""
        partialTranscript.value = ""
        stopRequested.value = false
        message.value = "연속 온디바이스 음성 인식을 준비하고 있습니다."
        launchOperation(operation) {
            var transcriptForSummary: String? = null
            try {
                recoveryJob.join()
                withContext(Dispatchers.IO) {
                    repository.begin(
                        operation.sessionId,
                        SttEngineType.ANDROID_ON_DEVICE,
                        summaryEngine,
                        OnDeviceSessionState.STARTING,
                        operation.token,
                    )
                    check(
                        repository.advanceOperation(
                            operation.sessionId,
                            operation.token,
                            setOf(OnDeviceSessionState.STARTING),
                            OnDeviceSessionState.LISTENING,
                        ),
                    )
                }
                var unexpectedErrorCount = 0
                while (!stopRequested.value) {
                    try {
                        val result = speechEngine.recognize(captureProfile) { event ->
                            when (event) {
                                SpeechEvent.Ready ->
                                    message.value = "다음 문장을 준비했습니다. 말씀하세요."
                                SpeechEvent.Listening ->
                                    message.value = "듣는 중 · 멈추면 자동으로 다음 문장을 준비합니다."
                                is SpeechEvent.Partial -> partialTranscript.value = event.text
                            }
                        }
                        appendLiveTranscript(result.text)
                        partialTranscript.value = ""
                        unexpectedErrorCount = 0
                    } catch (error: SpeechRecognitionException) {
                        partialTranscript.value = ""
                        if (stopRequested.value) break
                        if (error.isExpectedSilence()) {
                            message.value = "음성을 기다리는 중 · 자동으로 다시 듣습니다."
                        } else {
                            unexpectedErrorCount += 1
                            check(unexpectedErrorCount < MAX_AUTOMATIC_RESTART_ERRORS) {
                                "음성 인식기를 반복해서 시작하지 못했습니다. 잠시 후 다시 시도하세요."
                            }
                            message.value = "${error.message} · 자동으로 다시 시도합니다."
                        }
                    }
                    if (!stopRequested.value) {
                        message.value = "다음 문장을 준비합니다."
                        delay(captureProfile.restartDelayMillis)
                    }
                }
                val completedTranscript = liveTranscript.value.trim()
                if (completedTranscript.isBlank()) {
                    withContext(Dispatchers.IO) {
                        repository.finishOperation(
                            operation.sessionId,
                            operation.token,
                            OnDeviceSessionState.CANCELLED,
                        )
                    }
                    message.value = "저장할 인식 결과가 없어 기록을 마쳤습니다."
                } else {
                    val saved = withContext(Dispatchers.IO) {
                        repository.saveTranscript(
                            operation.sessionId,
                            operation.token,
                            completedTranscript,
                        )
                    }
                    check(saved) { "만료된 음성 인식 결과는 저장하지 않았습니다." }
                    coroutineContext.ensureActive()
                    transcriptForSummary = completedTranscript
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    repository.advanceOperation(
                        operation.sessionId,
                        operation.token,
                        setOf(
                            OnDeviceSessionState.STARTING,
                            OnDeviceSessionState.LISTENING,
                        ),
                        OnDeviceSessionState.CANCELLING,
                    )
                    repository.finishOperation(
                        operation.sessionId,
                        operation.token,
                        OnDeviceSessionState.CANCELLED,
                    )
                }
                throw cancelled
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    repository.finishOperation(
                        operation.sessionId,
                        operation.token,
                        OnDeviceSessionState.FAILED_RECOVERABLE,
                        OnDeviceFailureStage.CAPTURE,
                        error.message ?: "온디바이스 음성 인식에 실패했습니다.",
                    )
                }
                message.value = error.message ?: "온디바이스 음성 인식에 실패했습니다."
            } finally {
                MicrophoneArbiter.release(MicrophoneOwner.LOCAL_AI)
                finishUiOperation(operation)
                transcriptForSummary?.let { transcript ->
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        continueAfterTranscript(operation.sessionId, transcript, summaryEngine)
                    }
                }
            }
        }
    }

    private fun startFileTranscription(source: MainRecordingSource) {
        val operation = reserve(UUID.randomUUID().toString(), OnDeviceOperationKind.FILE_STT) ?: return
        val summaryEngine = selectedSummary.value
        val snapshot = audioFiles.temporaryFile("${operation.sessionId}.source")
        val pcm = audioFiles.temporaryFile("${operation.sessionId}.pcm")
        fileTranscribing.value = true
        processing.value = true
        processingLabel.value = "원본 녹음 확인 중"
        processingProgress.value = 0f
        liveTranscript.value = ""
        partialTranscript.value = ""
        message.value = "1번 탭 원본을 안전하게 확인하고 있습니다."
        launchOperation(operation) {
            var transcriptForSummary: String? = null
            var sessionStarted = false
            var failureStage = OnDeviceFailureStage.NORMALIZE
            try {
                recoveryJob.join()
                val prepared = withContext(Dispatchers.IO) {
                    mainRecordingSources.prepareSnapshot(source.id, snapshot)
                }
                withContext(Dispatchers.IO) {
                    repository.beginFromMainRecording(
                        id = operation.sessionId,
                        source = prepared.source,
                        sttEngine = SttEngineType.SENSEVOICE_LOCAL_FILE,
                        summaryEngine = summaryEngine,
                        operationToken = operation.token,
                    )
                    sessionStarted = true
                    check(
                        repository.advanceOperation(
                            operation.sessionId,
                            operation.token,
                            setOf(OnDeviceSessionState.STARTING),
                            OnDeviceSessionState.TRANSCRIBING,
                        ),
                    )
                }
                processingLabel.value = "PCM 변환 중"
                message.value = "원본은 유지하고 분석용 PCM만 만들고 있습니다."
                pcmNormalizer.normalize(prepared.snapshotFile, pcm) { progress ->
                    processingProgress.value = progress.coerceIn(0f, 1f)
                }
                coroutineContext.ensureActive()
                failureStage = OnDeviceFailureStage.TRANSCRIBE
                processingLabel.value = "SenseVoice 로컬 STT 전사 중"
                processingProgress.value = null
                message.value = "SenseVoice가 녹음 파일을 이 기기에서 텍스트로 변환하고 있습니다."
                val result = fileSpeechEngine.transcribe(pcm) { event ->
                    when (event) {
                        SpeechEvent.Ready -> message.value = "PCM 파일 전사를 준비했습니다."
                        SpeechEvent.Listening -> message.value = "녹음 파일을 분석하고 있습니다."
                        is SpeechEvent.Partial -> partialTranscript.value = event.text
                    }
                }
                liveTranscript.value = result.text
                partialTranscript.value = ""
                val saved = withContext(Dispatchers.IO) {
                    repository.saveTranscript(operation.sessionId, operation.token, result.text)
                }
                check(saved) { "만료된 파일 전사 결과는 저장하지 않았습니다." }
                transcriptForSummary = result.text
            } catch (cancelled: CancellationException) {
                if (sessionStarted) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        repository.advanceOperation(
                            operation.sessionId,
                            operation.token,
                            setOf(OnDeviceSessionState.STARTING, OnDeviceSessionState.TRANSCRIBING),
                            OnDeviceSessionState.CANCELLING,
                        )
                        repository.finishOperation(
                            operation.sessionId,
                            operation.token,
                            OnDeviceSessionState.CANCELLED,
                        )
                    }
                }
                throw cancelled
            } catch (error: Throwable) {
                if (sessionStarted) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        repository.finishOperation(
                            operation.sessionId,
                            operation.token,
                            OnDeviceSessionState.FAILED_RECOVERABLE,
                            failureStage,
                            error.message ?: "녹음 파일 전사에 실패했습니다.",
                        )
                    }
                }
                message.value = error.message ?: "녹음 파일 전사에 실패했습니다."
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { audioFiles.deleteTemporary(snapshot) }
                    runCatching { audioFiles.deleteTemporary(pcm) }
                }
                finishUiOperation(operation)
                transcriptForSummary?.let { transcript ->
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        continueAfterTranscript(operation.sessionId, transcript, summaryEngine)
                    }
                }
            }
        }
    }

    private fun startSummary(
        sessionId: String,
        transcript: String,
        engine: SummaryEngineType,
    ) {
        if (engine == SummaryEngineType.NONE) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.completeWithoutSummary(sessionId)
            }
            return
        }
        if (
            engine.usesQwen() &&
            !requireNativeCapability()
        ) {
            return
        }
        if (
            engine.usesQwen() &&
            !modelStore.snapshot(ModelCatalog.get(ModelId.QWEN_SUMMARY_KO)).ready
        ) {
            message.value = "전사는 저장했습니다. Qwen 로컬 AI 요약 모델을 먼저 설치하세요."
            return
        }
        val kind = when (engine) {
            SummaryEngineType.EXTRACTIVE_KOTLIN -> OnDeviceOperationKind.KOTLIN_SUMMARY
            SummaryEngineType.QWEN_LOCAL,
            SummaryEngineType.QWEN_LOCAL_GROUNDED,
            -> OnDeviceOperationKind.QWEN_SUMMARY
            SummaryEngineType.NONE -> return
        }
        val operation = reserve(sessionId, kind) ?: return
        processing.value = true
        processingLabel.value = if (engine.usesQwen()) {
            "Qwen 로컬 요약 중"
        } else {
            "빠른 요약 중"
        }
        processingProgress.value = null
        launchOperation(operation) {
            try {
                recoveryJob.join()
                val started = withContext(Dispatchers.IO) {
                    repository.startOperation(
                        sessionId,
                        setOf(
                            OnDeviceSessionState.TRANSCRIPT_READY,
                            OnDeviceSessionState.FAILED_RECOVERABLE,
                        ),
                        OnDeviceSessionState.SUMMARIZING,
                        operation.token,
                    )
                }
                check(started) { "현재 상태에서는 요약을 시작할 수 없습니다." }
                val result = if (engine.usesQwen()) {
                    try {
                        qwen.summarize(transcript)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        withContext(Dispatchers.Default) {
                            summaryCompactor.compact(
                                summary = extractiveSummary.summarize(transcript),
                                transcript = transcript,
                            )
                        }.also {
                            message.value = "Qwen 요약에 실패해 원문 기반 빠른 요약을 보존했습니다."
                        }
                    }
                } else {
                    withContext(Dispatchers.Default) {
                        summaryCompactor.compact(
                            summary = extractiveSummary.summarize(transcript),
                            transcript = transcript,
                        )
                    }
                }
                val saved = withContext(Dispatchers.IO) {
                    repository.saveSummary(sessionId, operation.token, result)
                }
                check(saved) { "만료된 요약 결과는 저장하지 않았습니다." }
                if (message.value?.startsWith("Qwen 요약에 실패") != true) {
                    message.value = if (engine.usesQwen()) {
                        "Qwen 로컬 AI 요약을 완료했습니다."
                    } else {
                        "빠른 요약을 완료했습니다."
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    repository.advanceOperation(
                        sessionId,
                        operation.token,
                        setOf(OnDeviceSessionState.SUMMARIZING),
                        OnDeviceSessionState.CANCELLING,
                    )
                    repository.finishOperation(
                        sessionId,
                        operation.token,
                        OnDeviceSessionState.TRANSCRIPT_READY,
                    )
                }
                throw cancelled
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    repository.finishOperation(
                        sessionId,
                        operation.token,
                        OnDeviceSessionState.FAILED_RECOVERABLE,
                        OnDeviceFailureStage.SUMMARIZE,
                        error.message ?: "로컬 요약에 실패했습니다.",
                    )
                }
                message.value = error.message ?: "로컬 요약에 실패했습니다."
            } finally {
                finishUiOperation(operation)
            }
        }
    }

    private fun continueAfterTranscript(
        sessionId: String,
        transcript: String,
        engine: SummaryEngineType,
    ) {
        if (engine == SummaryEngineType.NONE) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.completeWithoutSummary(sessionId)
            }
            message.value = "온디바이스 전사를 완료했습니다."
        } else {
            startSummary(sessionId, transcript, engine)
        }
    }

    private fun reserve(sessionId: String, kind: OnDeviceOperationKind): ActiveOperation? {
        val operation = coordinator.reserve(
            token = UUID.randomUUID().toString(),
            sessionId = sessionId,
            kind = kind,
        )
        if (operation == null) {
            message.value = "다른 로컬 작업이 끝난 뒤 다시 시도하세요."
            return null
        }
        activeSessionId.value = sessionId
        return operation
    }

    private fun launchOperation(
        operation: ActiveOperation,
        block: suspend () -> Unit,
    ) {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) { block() }
        if (coordinator.attach(operation.token, job)) {
            job.start()
        }
    }

    private fun finishUiOperation(operation: ActiveOperation) {
        if (!coordinator.finish(operation.token)) return
        listening.value = false
        fileTranscribing.value = false
        processing.value = false
        processingLabel.value = null
        processingProgress.value = null
        partialTranscript.value = ""
        stopRequested.value = false
        if (activeSessionId.value == operation.sessionId) activeSessionId.value = null
    }

    private fun appendLiveTranscript(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        liveTranscript.value = listOf(liveTranscript.value.trim(), normalized)
            .filter(String::isNotBlank)
            .joinToString(separator = "\n")
    }

    private fun SpeechRecognitionException.isExpectedSilence(): Boolean =
        recognitionCode == SpeechRecognizer.ERROR_NO_MATCH ||
            recognitionCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

    private fun parseSummaryEngine(value: String): SummaryEngineType =
        runCatching { SummaryEngineType.valueOf(value) }
            .getOrDefault(SummaryEngineType.EXTRACTIVE_KOTLIN)
            .let { if (it == SummaryEngineType.QWEN_LOCAL_GROUNDED) SummaryEngineType.QWEN_LOCAL else it }

    private fun requireNativeCapability(): Boolean {
        if (nativeCapability.supported) return true
        message.value = nativeCapability.reason ?: "이 기기에서는 native AI를 사용할 수 없습니다."
        return false
    }

    private fun ModelDescriptor.toUiState(workInfos: List<WorkInfo>): ModelUiState {
        val installed = modelStore.snapshot(this)
        if (installed.ready) {
            return ModelUiState(
                descriptor = this,
                status = ModelUiStatus.READY,
                downloadedBytes = approximateDownloadBytes,
                totalBytes = approximateDownloadBytes,
                installedBytes = installed.installedBytes,
            )
        }
        val work = workInfos
            .filter { ModelDownloadWorker.tag(id) in it.tags }
            .firstOrNull { !it.state.isFinished }
            ?: workInfos.lastOrNull { ModelDownloadWorker.tag(id) in it.tags }
        val progress = work?.progress
        val downloaded = progress?.getLong(
            ModelDownloadWorker.KEY_DOWNLOADED_BYTES,
            modelStore.partialBytes(id),
        ) ?: modelStore.partialBytes(id)
        val total = progress?.getLong(
            ModelDownloadWorker.KEY_TOTAL_BYTES,
            approximateDownloadBytes,
        ) ?: approximateDownloadBytes
        val workerStatus = progress?.getString(ModelDownloadWorker.KEY_STATUS)
        val bytesPerSecond = progress?.getLong(ModelDownloadWorker.KEY_BYTES_PER_SECOND, 0) ?: 0
        val etaSeconds = progress?.getLong(ModelDownloadWorker.KEY_ETA_SECONDS, -1) ?: -1
        val status = when {
            work == null && downloaded > 0 -> ModelUiStatus.PAUSED
            work == null -> ModelUiStatus.NOT_INSTALLED
            work.state == WorkInfo.State.ENQUEUED -> ModelUiStatus.WAITING_FOR_WIFI
            work.state == WorkInfo.State.RUNNING &&
                workerStatus == ModelDownloadWorker.STATUS_VERIFYING -> ModelUiStatus.VERIFYING
            work.state == WorkInfo.State.RUNNING &&
                workerStatus == ModelDownloadWorker.STATUS_INSTALLING -> ModelUiStatus.INSTALLING
            work.state == WorkInfo.State.RUNNING -> ModelUiStatus.DOWNLOADING
            work.state == WorkInfo.State.FAILED -> ModelUiStatus.FAILED
            work.state == WorkInfo.State.CANCELLED && downloaded > 0 -> ModelUiStatus.PAUSED
            else -> ModelUiStatus.NOT_INSTALLED
        }
        return ModelUiState(
            descriptor = this,
            status = status,
            downloadedBytes = downloaded,
            totalBytes = total,
            installedBytes = installed.installedBytes,
            bytesPerSecond = bytesPerSecond,
            etaSeconds = etaSeconds,
            error = work?.outputData?.getString(ModelDownloadWorker.KEY_ERROR),
        )
    }

    private data class CaptureState(
        val listening: Boolean,
        val fileTranscribing: Boolean,
        val completed: String,
        val partial: String,
        val sessionId: String?,
    )

    private data class OperationState(
        val message: String?,
        val processing: Boolean,
        val label: String?,
        val progress: Float?,
    )

    private data class ActiveState(
        val capture: CaptureState,
        val operation: OperationState,
    )

    private data class EngineSelection(
        val sttProfile: SttCaptureProfile,
        val summary: SummaryEngineType,
    )

    private data class FileSelection(
        val sourceId: String?,
        val availability: SenseVoiceFileSttAvailability,
    )

    private data class LocalAiSelection(
        val engine: EngineSelection,
        val file: FileSelection,
    )

    private data class SessionsAndSources(
        val sessions: List<OnDeviceSessionEntity>,
        val sources: List<MainRecordingSource>,
    )

    private companion object {
        const val PREFERENCES = "ondevice-settings"
        const val KEY_STT = "stt-engine"
        const val KEY_STT_PROFILE = "stt-capture-profile"
        const val KEY_SUMMARY = "summary-engine"
        const val KEY_FILE_STT_VALIDATED = "system-file-stt-validated"
        const val MAX_AUTOMATIC_RESTART_ERRORS = 3
    }
}
