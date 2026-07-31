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
import com.thinktank.recorder.ondevice.modelpack.ModelDeleteScope
import com.thinktank.recorder.ondevice.modelpack.ModelDescriptor
import com.thinktank.recorder.ondevice.modelpack.ModelDownloadManager
import com.thinktank.recorder.ondevice.modelpack.ModelDownloadWorker
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.modelpack.ModelStore
import com.thinktank.recorder.ondevice.modelpack.ModelVaultState
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
import com.thinktank.recorder.ondevice.summary.GemmaSummaryEngine
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
    RETAINED,
    PAUSED,
    FAILED,
}

data class ModelUiState(
    val descriptor: ModelDescriptor,
    val status: ModelUiStatus,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = descriptor.approximateDownloadBytes,
    val installedBytes: Long = 0,
    val retainedArtifactBytes: Long = 0,
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
    val modelVault: ModelVaultState = ModelVaultState(),
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
    private val database = OnDeviceDatabase.get(application)
    private val repository = OnDeviceRepository(
        dao = database.sessionDao(),
        audioFiles = audioFiles,
    )
    private val modelStore = ModelStore(application)
    private val speechEngine = AndroidOnDeviceSpeechEngine(application)
    private val fileSpeechEngine = SenseVoiceFileSpeechEngine(application, modelStore)
    private val gemmaSummaryEngine = GemmaSummaryEngine(application, modelStore)
    private val pcmNormalizer = AndroidPcmNormalizer()
    private val modelManager = ModelDownloadManager(application)
    private val coordinator = OnDeviceOperationCoordinator()
    private val nativeCapability = NativeRuntimeCapabilities.current()
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
    private val modelVaultState = MutableStateFlow(modelManager.vaultState())

    init {
        // Gemma 3 1B is the single fixed local-summary model.
        preferences.edit()
            .remove(KEY_STT)
            .remove(KEY_FILE_STT_VALIDATED)
            .putString(KEY_SUMMARY, SummaryEngineType.GEMMA_LOCAL.name)
            .apply()
    }

    private val recoveryJob: Job = viewModelScope.launch(Dispatchers.IO) {
        repository.recoverInterrupted()
        modelManager.recoverInterruptedInstalls()
        // A build/app replacement can stop extraction after the archive is fully downloaded.
        // Resume the verified local install automatically; no Wi-Fi or re-download is involved.
        modelManager.resumeCompletedDownloadsLocally()
        modelVaultState.value = modelManager.vaultState()
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
    private val sessionAndSources = combine(
        repository.sessions,
        mainRecordingSources.sources,
    ) { sessions, sources ->
        SessionsAndSources(sessions, sources)
    }
    private val fileSelection = selectedMainRecordingId
        .let { selected ->
            combine(selected, refreshModels) { sourceId, _ ->
                FileSelection(sourceId, fileSpeechEngine.availability())
            }
        }
    private val localAiSelection = combine(selectedSttProfile, fileSelection) { sttProfile, file ->
        LocalAiSelection(sttProfile, file)
    }
    private val modelRefreshState = combine(refreshModels, modelVaultState) { _, vault ->
        vault
    }

    val uiState: StateFlow<OnDeviceUiState> = combine(
        sessionAndSources,
        localAiSelection,
        activeState,
        modelManager.workInfos,
        modelRefreshState,
    ) { sessionSources, selection, active, workInfos, vault ->
        OnDeviceUiState(
            sessions = sessionSources.sessions,
            mainRecordingSources = sessionSources.sources,
            selectedMainRecordingId = selection.file.sourceId,
            fileSttAvailability = fileSpeechEngine.availability(),
            selectedSttProfile = selection.sttProfile,
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
            modelVault = vault,
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
                val retainedArtifactBytes = modelStore.artifactBytes(descriptor)
                ModelUiState(
                    descriptor = descriptor,
                    status = when {
                        installed.ready -> ModelUiStatus.READY
                        retainedArtifactBytes > 0 -> ModelUiStatus.RETAINED
                        else -> ModelUiStatus.NOT_INSTALLED
                    },
                    installedBytes = installed.installedBytes,
                    retainedArtifactBytes = retainedArtifactBytes,
                )
            },
            modelVault = modelVaultState.value,
        ),
    )

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
            OnDeviceOperationKind.GEMMA_SUMMARY -> Unit
        }
        stopRequested.value = true
        coordinator.cancelActive()
        message.value = "작업을 안전하게 취소하고 있습니다."
    }

    fun onHostStopped() {
        cancelListening()
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
        message.value = "Wi-Fi에서 STT 모델 파일만 내려받습니다. 음성과 전사 원문은 전송하지 않습니다."
        runCatching { modelManager.download(id) }
            .onFailure { message.value = it.message }
    }

    fun importModel(id: ModelId, uri: Uri) {
        if (!requireNativeCapability()) return
        message.value = "선택한 로컬 모델 파일을 검증합니다."
        runCatching { modelManager.import(id, uri) }
            .onFailure { message.value = it.message }
    }

    fun connectModelVault(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val state = modelManager.connectVault(uri)
            modelVaultState.value = state
            if (state.connected) {
                modelManager.resumeCompletedDownloadsLocally()
                refreshModels.value += 1
                message.value = "모델 보관함을 연결했습니다. 보관된 원본을 확인합니다."
            } else {
                message.value = state.error ?: "모델 보관함을 연결하지 못했습니다."
            }
        }
    }

    fun disconnectModelVault() {
        modelManager.disconnectVault()
        modelVaultState.value = modelManager.vaultState()
        message.value = "모델 보관함 연결을 해제했습니다. 보관된 파일은 삭제하지 않았습니다."
    }

    fun pauseModel(id: ModelId) {
        viewModelScope.launch {
            modelManager.pause(id)
            refreshModels.value += 1
            message.value = "모델 다운로드를 일시정지했습니다. 다시 누르면 이어받습니다."
        }
    }

    fun restoreModel(id: ModelId) {
        if (!requireNativeCapability()) return
        message.value = "보관된 원본을 검증해 모델 적용본을 복구합니다."
        runCatching { modelManager.restore(id) }
            .onFailure { message.value = it.message }
    }

    fun deleteModel(id: ModelId, scope: ModelDeleteScope) {
        message.value = if (ResourceArbiter.activeWorkload() == null) {
            "모델을 삭제하고 있습니다."
        } else {
            "실행 중인 로컬 AI 작업이 끝난 뒤 모델을 삭제합니다."
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                ResourceArbiter.withLease(NativeWorkload.MODEL_MAINTENANCE) {
                    modelManager.delete(id, scope)
                }
            }
            refreshModels.value += 1
            modelVaultState.value = modelManager.vaultState()
            message.value = result.fold(
                onSuccess = {
                    if (scope == ModelDeleteScope.INSTALLED_ONLY) {
                        "모델 적용본만 삭제했습니다. 보관 원본은 유지됩니다."
                    } else {
                        "모델 적용본과 보관 원본을 완전히 삭제했습니다."
                    }
                },
                onFailure = { it.message ?: "모델을 삭제하지 못했습니다." },
            )
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
        val captureProfile = selectedSttProfile.value
        listening.value = true
        liveTranscript.value = ""
        partialTranscript.value = ""
        stopRequested.value = false
        message.value = "연속 온디바이스 음성 인식을 준비하고 있습니다."
        launchOperation(operation) {
            var completedTranscript: String? = null
            try {
                recoveryJob.join()
                withContext(Dispatchers.IO) {
                    repository.begin(
                        operation.sessionId,
                        SttEngineType.ANDROID_ON_DEVICE,
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
                                SpeechEvent.Retrying -> Unit
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
                val recognizedText = liveTranscript.value.trim()
                if (recognizedText.isBlank()) {
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
                            recognizedText,
                        )
                    }
                    check(saved) { "만료된 음성 인식 결과는 저장하지 않았습니다." }
                    coroutineContext.ensureActive()
                    completedTranscript = recognizedText
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
                completedTranscript?.let {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        continueAfterTranscript(operation.sessionId, it)
                    }
                }
            }
        }
    }

    private fun startFileTranscription(source: MainRecordingSource) {
        val operation = reserve(UUID.randomUUID().toString(), OnDeviceOperationKind.FILE_STT) ?: return
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
            var completedTranscript: String? = null
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
                        SpeechEvent.Retrying -> {
                            processingLabel.value = "전체 범위 안전 재처리 중"
                            message.value = "누락 가능 구간을 전체 녹음 범위에서 한 번 더 확인하고 있습니다."
                        }
                        is SpeechEvent.Partial -> partialTranscript.value = event.text
                    }
                }
                partialTranscript.value = ""
                val diagnostics = requireNotNull(result.diagnostics) {
                    "파일 전사 처리 범위를 확인할 수 없습니다."
                }
                if (!diagnostics.passed) {
                    liveTranscript.value = ""
                    val failed = withContext(Dispatchers.IO) {
                        repository.finishTranscriptQualityFailure(
                            id = operation.sessionId,
                            token = operation.token,
                            diagnostics = diagnostics,
                            error = "녹음 끝까지 신뢰할 수 있게 전사하지 못했습니다. 원본은 유지되며 다시 시도할 수 있습니다.",
                            transcript = result.text,
                        )
                    }
                    check(failed) { "만료된 파일 전사 품질 결과는 저장하지 않았습니다." }
                    message.value =
                        "전사 누락 가능성이 있어 결과를 완료하지 않았습니다. 원본 녹음은 그대로 유지됩니다."
                } else {
                    liveTranscript.value = result.text
                    val saved = withContext(Dispatchers.IO) {
                        repository.saveTranscript(operation.sessionId, operation.token, result)
                    }
                    check(saved) { "만료된 파일 전사 결과는 저장하지 않았습니다." }
                    completedTranscript = result.text
                }
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
                completedTranscript?.let {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        continueAfterTranscript(operation.sessionId, it)
                    }
                }
            }
        }
    }

    fun summarize(sessionId: String) {
        if (coordinator.active.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val session = repository.get(sessionId)
            val transcript = session?.transcript.orEmpty()
            withContext(Dispatchers.Main.immediate) {
                if (transcript.isBlank()) {
                    message.value = "Gemma로 요약할 전사 원문이 없습니다."
                } else {
                    startGemmaSummary(sessionId, transcript)
                }
            }
        }
    }

    private fun continueAfterTranscript(sessionId: String, transcript: String) {
        val descriptor = ModelCatalog.get(ModelId.GEMMA_SUMMARY_KO)
        if (!modelStore.snapshot(descriptor).ready) {
            message.value =
                "전사를 저장했습니다. Gemma 3 1B 모델을 적용하면 기본 요약을 실행할 수 있습니다."
            return
        }
        startGemmaSummary(sessionId, transcript)
    }

    private fun startGemmaSummary(sessionId: String, transcript: String) {
        val operation = reserve(sessionId, OnDeviceOperationKind.GEMMA_SUMMARY) ?: return
        processing.value = true
        processingLabel.value = "Gemma 3 1B 기본 요약 중"
        processingProgress.value = null
        message.value = "Gemma 3 1B가 전사 원문을 이 기기에서 요약하고 있습니다."
        launchOperation(operation) {
            try {
                recoveryJob.join()
                val started = withContext(Dispatchers.IO) {
                    repository.startOperation(
                        id = sessionId,
                        allowedStates = setOf(
                            OnDeviceSessionState.TRANSCRIPT_READY,
                            OnDeviceSessionState.FAILED_RECOVERABLE,
                            OnDeviceSessionState.COMPLETE,
                        ),
                        targetState = OnDeviceSessionState.SUMMARIZING,
                        token = operation.token,
                    )
                }
                check(started) { "현재 기록은 Gemma 요약을 시작할 수 없는 상태입니다." }
                val summary = gemmaSummaryEngine.summarize(transcript)
                val saved = withContext(Dispatchers.IO) {
                    repository.saveGemmaSummary(sessionId, operation.token, summary)
                }
                check(saved) { "만료된 Gemma 요약 결과는 저장하지 않았습니다." }
                message.value = "Gemma 3 1B 기본 요약을 완료했습니다."
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    repository.finishOperation(
                        sessionId,
                        operation.token,
                        OnDeviceSessionState.TRANSCRIPT_READY,
                        OnDeviceFailureStage.SUMMARIZE,
                        "Gemma 요약이 취소되었습니다.",
                    )
                }
                throw cancelled
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    repository.finishOperation(
                        sessionId,
                        operation.token,
                        OnDeviceSessionState.TRANSCRIPT_READY,
                        OnDeviceFailureStage.SUMMARIZE,
                        error.message ?: "Gemma 3 1B 요약에 실패했습니다.",
                    )
                }
                message.value = error.message ?: "Gemma 3 1B 요약에 실패했습니다."
            } finally {
                finishUiOperation(operation)
            }
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

    private fun requireNativeCapability(): Boolean {
        if (nativeCapability.supported) return true
        message.value = nativeCapability.reason ?: "이 기기에서는 native AI를 사용할 수 없습니다."
        return false
    }

    private fun ModelDescriptor.toUiState(workInfos: List<WorkInfo>): ModelUiState {
        val installed = modelStore.snapshot(this)
        val retainedArtifactBytes = modelStore.artifactBytes(this)
        if (installed.ready) {
            return ModelUiState(
                descriptor = this,
                status = ModelUiStatus.READY,
                downloadedBytes = approximateDownloadBytes,
                totalBytes = approximateDownloadBytes,
                installedBytes = installed.installedBytes,
                retainedArtifactBytes = retainedArtifactBytes,
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
            work == null && retainedArtifactBytes > 0 -> ModelUiStatus.RETAINED
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
            retainedArtifactBytes > 0 -> ModelUiStatus.RETAINED
            else -> ModelUiStatus.NOT_INSTALLED
        }
        return ModelUiState(
            descriptor = this,
            status = status,
            downloadedBytes = downloaded,
            totalBytes = total,
            installedBytes = installed.installedBytes,
            retainedArtifactBytes = retainedArtifactBytes,
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

    private data class FileSelection(
        val sourceId: String?,
        val availability: SenseVoiceFileSttAvailability,
    )

    private data class LocalAiSelection(
        val sttProfile: SttCaptureProfile,
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
