package com.thinktank.recorder.ondevice.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.thinktank.recorder.ondevice.api.OnDeviceFailureStage
import com.thinktank.recorder.ondevice.api.OnDeviceOperationKind
import com.thinktank.recorder.ondevice.api.OnDeviceSessionState
import com.thinktank.recorder.ondevice.api.SpeechEvent
import com.thinktank.recorder.ondevice.api.SttEngineType
import com.thinktank.recorder.ondevice.api.SummaryEngineType
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
import com.thinktank.recorder.ondevice.recording.LocalAudioRecorder
import com.thinktank.recorder.ondevice.runtime.ActiveOperation
import com.thinktank.recorder.ondevice.runtime.MicrophoneArbiter
import com.thinktank.recorder.ondevice.runtime.MicrophoneOwner
import com.thinktank.recorder.ondevice.runtime.NativeWorkload
import com.thinktank.recorder.ondevice.runtime.NativeRuntimeCapabilities
import com.thinktank.recorder.ondevice.runtime.OnDeviceOperationCoordinator
import com.thinktank.recorder.ondevice.runtime.ResourceArbiter
import com.thinktank.recorder.ondevice.stt.AndroidOnDeviceSpeechEngine
import com.thinktank.recorder.ondevice.stt.MoonshineSpeechEngine
import com.thinktank.recorder.ondevice.summary.ExtractiveSummaryEngine
import com.thinktank.recorder.ondevice.summary.QwenSummaryEngine
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
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
    val selectedStt: SttEngineType = SttEngineType.ANDROID_ON_DEVICE,
    val selectedSummary: SummaryEngineType = SummaryEngineType.EXTRACTIVE_KOTLIN,
    val systemSttAvailable: Boolean = false,
    val nativeAiAvailable: Boolean = false,
    val nativeAiUnavailableReason: String? = null,
    val listening: Boolean = false,
    val processing: Boolean = false,
    val processingLabel: String? = null,
    val processingProgress: Float? = null,
    val partialTranscript: String = "",
    val activeSessionId: String? = null,
    val message: String? = null,
    val models: List<ModelUiState> = emptyList(),
) {
    val micBusy: Boolean
        get() = listening
}

class OnDeviceViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES, 0)
    private val audioFiles = LocalAudioFileManager(application)
    private val repository = OnDeviceRepository(
        dao = OnDeviceDatabase.get(application).sessionDao(),
        audioFiles = audioFiles,
    )
    private val speechEngine = AndroidOnDeviceSpeechEngine(application)
    private val localRecorder = LocalAudioRecorder()
    private val extractiveSummary = ExtractiveSummaryEngine()
    private val modelStore = ModelStore(application)
    private val moonshineDelegate = lazy { MoonshineSpeechEngine(modelStore) }
    private val moonshine by moonshineDelegate
    private val qwenDelegate = lazy { QwenSummaryEngine(application, modelStore) }
    private val qwen by qwenDelegate
    private val modelManager = ModelDownloadManager(application)
    private val coordinator = OnDeviceOperationCoordinator()
    private val nativeCapability = NativeRuntimeCapabilities.current()

    private val selectedStt = MutableStateFlow(
        preferences.getString(KEY_STT, null)
            ?.let { runCatching { SttEngineType.valueOf(it) }.getOrNull() }
            ?.takeUnless {
                it == SttEngineType.MOONSHINE_LOCAL && !nativeCapability.supported
            }
            ?: SttEngineType.ANDROID_ON_DEVICE,
    )
    private val selectedSummary = MutableStateFlow(
        preferences.getString(KEY_SUMMARY, null)
            ?.let { runCatching { SummaryEngineType.valueOf(it) }.getOrNull() }
            ?.takeUnless {
                it == SummaryEngineType.QWEN_LOCAL && !nativeCapability.supported
            }
            ?: SummaryEngineType.EXTRACTIVE_KOTLIN,
    )
    private val listening = MutableStateFlow(false)
    private val processing = MutableStateFlow(false)
    private val processingLabel = MutableStateFlow<String?>(null)
    private val processingProgress = MutableStateFlow<Float?>(null)
    private val partialTranscript = MutableStateFlow("")
    private val activeSessionId = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)
    private val refreshModels = MutableStateFlow(0)
    private var captureStop: CaptureStop? = null

    private val recoveryJob: Job = viewModelScope.launch(Dispatchers.IO) {
        repository.recoverInterrupted()
    }

    private val selectionState = combine(selectedStt, selectedSummary) { stt, summary ->
        stt to summary
    }
    private val captureState = combine(
        listening,
        partialTranscript,
        activeSessionId,
    ) { isListening, partial, sessionId ->
        CaptureState(isListening, partial, sessionId)
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

    val uiState: StateFlow<OnDeviceUiState> = combine(
        repository.sessions,
        selectionState,
        activeState,
        modelManager.workInfos,
        refreshModels,
    ) { sessions, selection, active, workInfos, _ ->
        OnDeviceUiState(
            sessions = sessions,
            selectedStt = selection.first,
            selectedSummary = selection.second,
            systemSttAvailable = speechEngine.isAvailable(),
            nativeAiAvailable = nativeCapability.supported,
            nativeAiUnavailableReason = nativeCapability.reason,
            listening = active.capture.listening,
            processing = active.operation.processing,
            processingLabel = active.operation.label,
            processingProgress = active.operation.progress,
            partialTranscript = active.capture.partial,
            activeSessionId = active.capture.sessionId,
            message = active.operation.message,
            models = ModelCatalog.models.map { descriptor ->
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
            models = ModelCatalog.models.map { descriptor ->
                val installed = modelStore.snapshot(descriptor)
                ModelUiState(
                    descriptor = descriptor,
                    status = if (installed.ready) ModelUiStatus.READY else ModelUiStatus.NOT_INSTALLED,
                    installedBytes = installed.installedBytes,
                )
            },
        ),
    )

    fun selectStt(engine: SttEngineType) {
        if (coordinator.active.value != null) return
        if (engine == SttEngineType.MOONSHINE_LOCAL && !requireNativeCapability()) return
        selectedStt.value = engine
        preferences.edit().putString(KEY_STT, engine.name).apply()
    }

    fun selectSummary(engine: SummaryEngineType) {
        if (coordinator.active.value != null) return
        if (engine == SummaryEngineType.QWEN_LOCAL && !requireNativeCapability()) return
        selectedSummary.value = engine
        preferences.edit().putString(KEY_SUMMARY, engine.name).apply()
    }

    fun startListening() {
        if (coordinator.active.value != null) return
        when (selectedStt.value) {
            SttEngineType.ANDROID_ON_DEVICE -> startSystemRecognition()
            SttEngineType.MOONSHINE_LOCAL -> startMoonshineCapture()
        }
    }

    fun stopListening() {
        when (coordinator.active.value?.kind) {
            OnDeviceOperationKind.LOCAL_CAPTURE -> {
                captureStop?.request?.complete(Unit)
            }
            OnDeviceOperationKind.LIVE_STT -> speechEngine.stop()
            else -> Unit
        }
    }

    fun cancelListening() {
        val active = coordinator.active.value ?: return
        when (active.kind) {
            OnDeviceOperationKind.LIVE_STT -> speechEngine.cancel()
            OnDeviceOperationKind.LOCAL_CAPTURE -> Unit
            OnDeviceOperationKind.MOONSHINE_TRANSCRIBE ->
                if (moonshineDelegate.isInitialized()) moonshine.cancel()
            OnDeviceOperationKind.KOTLIN_SUMMARY,
            OnDeviceOperationKind.QWEN_SUMMARY,
            -> Unit
        }
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

    fun retryTranscription(sessionId: String) {
        if (!requireNativeCapability()) return
        val session = uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
        val audioPath = session.audioPath
        if (
            session.sttEngine != SttEngineType.MOONSHINE_LOCAL.name ||
            session.failureStage == OnDeviceFailureStage.DELETE.name ||
            audioPath.isNullOrBlank() ||
            !File(audioPath).isFile
        ) {
            message.value = "다시 전사할 로컬 음성 파일이 없습니다."
            return
        }
        val operation = reserve(sessionId, OnDeviceOperationKind.MOONSHINE_TRANSCRIBE) ?: return
        listening.value = false
        processing.value = true
        processingLabel.value = "Moonshine 한국어 전사 중"
        processingProgress.value = 0f
        partialTranscript.value = ""
        launchOperation(operation) {
            var transcriptForSummary: String? = null
            try {
                recoveryJob.join()
                val started = withContext(Dispatchers.IO) {
                    repository.startOperation(
                        sessionId,
                        setOf(
                            OnDeviceSessionState.AUDIO_READY,
                            OnDeviceSessionState.FAILED_RECOVERABLE,
                        ),
                        OnDeviceSessionState.TRANSCRIBING,
                        operation.token,
                    )
                }
                check(started) { "현재 상태에서는 전사를 다시 시작할 수 없습니다." }
                val result = moonshine.transcribe(File(audioPath)) { progress ->
                    processingProgress.value = progress
                }
                partialTranscript.value = result.text
                val saved = withContext(Dispatchers.IO) {
                    repository.saveTranscript(sessionId, operation.token, result.text)
                }
                check(saved) { "만료된 전사 결과는 저장하지 않았습니다." }
                coroutineContext.ensureActive()
                transcriptForSummary = result.text
                message.value = "Moonshine 전사를 완료했습니다."
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    repository.advanceOperation(
                        sessionId,
                        operation.token,
                        setOf(OnDeviceSessionState.TRANSCRIBING),
                        OnDeviceSessionState.CANCELLING,
                    )
                    repository.finishOperation(
                        sessionId,
                        operation.token,
                        OnDeviceSessionState.AUDIO_READY,
                    )
                }
                throw cancelled
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    repository.finishOperation(
                        sessionId,
                        operation.token,
                        OnDeviceSessionState.FAILED_RECOVERABLE,
                        OnDeviceFailureStage.TRANSCRIBE,
                        error.message ?: "Moonshine 로컬 전사에 실패했습니다.",
                    )
                }
                message.value = error.message ?: "Moonshine 로컬 전사에 실패했습니다."
            } finally {
                finishUiOperation(operation)
                transcriptForSummary?.let { transcript ->
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        continueAfterTranscript(
                            sessionId,
                            transcript,
                            parseSummaryEngine(session.summaryEngine),
                        )
                    }
                }
            }
        }
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

    fun downloadModel(id: ModelId, wifiOnly: Boolean = true) {
        if (!requireNativeCapability()) return
        message.value = "모델 파일만 내려받습니다. 음성·전사·요약은 전송하지 않습니다."
        runCatching { modelManager.download(id, wifiOnly) }
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
        val workload = when (id) {
            ModelId.MOONSHINE_KO -> NativeWorkload.MOONSHINE_STT
            ModelId.QWEN_SUMMARY_KO -> NativeWorkload.QWEN_SUMMARY
        }
        message.value = if (ResourceArbiter.activeWorkload() == null) {
            "모델을 삭제하고 있습니다."
        } else {
            "실행 중인 로컬 AI 작업이 끝난 뒤 모델을 삭제합니다."
        }
        viewModelScope.launch(Dispatchers.IO) {
            ResourceArbiter.withLease(workload) {
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
        if (moonshineDelegate.isInitialized()) moonshine.release()
        localRecorder.release()
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
        listening.value = true
        partialTranscript.value = ""
        message.value = "온디바이스 음성 인식을 준비하고 있습니다."
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
                val result = speechEngine.recognize { event ->
                    when (event) {
                        SpeechEvent.Ready ->
                            message.value = "말씀하세요. 음성은 이 기기에서만 처리됩니다."
                        SpeechEvent.Listening -> message.value = "듣고 있습니다."
                        is SpeechEvent.Partial -> partialTranscript.value = event.text
                    }
                }
                partialTranscript.value = result.text
                val saved = withContext(Dispatchers.IO) {
                    repository.saveTranscript(operation.sessionId, operation.token, result.text)
                }
                check(saved) { "만료된 음성 인식 결과는 저장하지 않았습니다." }
                coroutineContext.ensureActive()
                transcriptForSummary = result.text
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

    private fun startMoonshineCapture() {
        if (!requireNativeCapability()) return
        if (!modelStore.snapshot(ModelCatalog.get(ModelId.MOONSHINE_KO)).ready) {
            message.value = "Moonshine 한국어 STT 모델을 먼저 설치하세요."
            return
        }
        val operation = reserve(UUID.randomUUID().toString(), OnDeviceOperationKind.LOCAL_CAPTURE)
            ?: return
        if (!MicrophoneArbiter.tryAcquire(MicrophoneOwner.LOCAL_AI)) {
            coordinator.finish(operation.token)
            activeSessionId.value = null
            message.value = "기본 녹음이 마이크를 사용 중입니다. 녹음을 마친 뒤 다시 시도하세요."
            return
        }
        val summaryEngine = selectedSummary.value
        val stopRequest = CompletableDeferred<Unit>()
        captureStop = CaptureStop(operation.token, stopRequest)
        listening.value = true
        partialTranscript.value = ""
        message.value = "Moonshine용 로컬 녹음을 준비하고 있습니다."
        launchOperation(operation) {
            var shouldTranscribe = false
            val audioFile = audioFiles.recordingFile(operation.sessionId)
            try {
                recoveryJob.join()
                withContext(Dispatchers.IO) {
                    repository.begin(
                        operation.sessionId,
                        SttEngineType.MOONSHINE_LOCAL,
                        summaryEngine,
                        OnDeviceSessionState.STARTING,
                        operation.token,
                    )
                }
                localRecorder.start(audioFile) { error ->
                    stopRequest.completeExceptionally(error)
                }
                withContext(Dispatchers.IO) {
                    check(repository.attachAudio(operation.sessionId, operation.token, audioFile.absolutePath))
                    check(
                        repository.advanceOperation(
                            operation.sessionId,
                            operation.token,
                            setOf(OnDeviceSessionState.STARTING),
                            OnDeviceSessionState.LISTENING,
                        ),
                    )
                }
                message.value = "Moonshine용 음성을 녹음합니다. 완료를 누르면 기기에서 전사합니다."
                stopRequest.await()
                processing.value = true
                processingLabel.value = "녹음 마무리 중"
                processingProgress.value = 0f
                val finalized = localRecorder.stop()
                MicrophoneArbiter.release(MicrophoneOwner.LOCAL_AI)
                val saved = withContext(Dispatchers.IO) {
                    repository.finishOperation(
                        operation.sessionId,
                        operation.token,
                        OnDeviceSessionState.AUDIO_READY,
                    )
                }
                check(saved && finalized.isFile) { "마감된 녹음 상태를 저장하지 못했습니다." }
                coroutineContext.ensureActive()
                shouldTranscribe = true
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    runCatching { localRecorder.cancelAndDelete() }
                    withContext(Dispatchers.IO) {
                        repository.advanceOperation(
                            operation.sessionId,
                            operation.token,
                            setOf(
                                OnDeviceSessionState.STARTING,
                                OnDeviceSessionState.LISTENING,
                            ),
                            OnDeviceSessionState.CANCELLING,
                        )
                        repository.finishCaptureWithoutAudio(
                            operation.sessionId,
                            operation.token,
                            OnDeviceSessionState.CANCELLED,
                        )
                    }
                }
                throw cancelled
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    runCatching { localRecorder.cancelAndDelete() }
                    withContext(Dispatchers.IO) {
                        repository.finishCaptureWithoutAudio(
                            operation.sessionId,
                            operation.token,
                            OnDeviceSessionState.FAILED_RECOVERABLE,
                            OnDeviceFailureStage.CAPTURE,
                            error.message ?: "로컬 녹음을 시작하지 못했습니다.",
                        )
                    }
                }
                message.value = error.message ?: "로컬 녹음을 시작하지 못했습니다."
            } finally {
                MicrophoneArbiter.release(MicrophoneOwner.LOCAL_AI)
                if (captureStop?.token == operation.token) captureStop = null
                finishUiOperation(operation)
                if (shouldTranscribe) {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        retryTranscription(operation.sessionId)
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
            engine == SummaryEngineType.QWEN_LOCAL &&
            !requireNativeCapability()
        ) {
            return
        }
        if (
            engine == SummaryEngineType.QWEN_LOCAL &&
            !modelStore.snapshot(ModelCatalog.get(ModelId.QWEN_SUMMARY_KO)).ready
        ) {
            message.value = "전사는 저장했습니다. Qwen 로컬 AI 요약 모델을 먼저 설치하세요."
            return
        }
        val kind = when (engine) {
            SummaryEngineType.EXTRACTIVE_KOTLIN -> OnDeviceOperationKind.KOTLIN_SUMMARY
            SummaryEngineType.QWEN_LOCAL -> OnDeviceOperationKind.QWEN_SUMMARY
            SummaryEngineType.NONE -> return
        }
        val operation = reserve(sessionId, kind) ?: return
        processing.value = true
        processingLabel.value = if (engine == SummaryEngineType.QWEN_LOCAL) {
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
                val result = if (engine == SummaryEngineType.QWEN_LOCAL) {
                    try {
                        qwen.summarize(transcript)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        withContext(Dispatchers.Default) {
                            extractiveSummary.summarize(transcript)
                        }.also {
                            message.value = "Qwen 요약에 실패해 원문 기반 빠른 요약을 보존했습니다."
                        }
                    }
                } else {
                    withContext(Dispatchers.Default) {
                        extractiveSummary.summarize(transcript)
                    }
                }
                val saved = withContext(Dispatchers.IO) {
                    repository.saveSummary(sessionId, operation.token, result)
                }
                check(saved) { "만료된 요약 결과는 저장하지 않았습니다." }
                if (message.value?.startsWith("Qwen 요약에 실패") != true) {
                    message.value = if (engine == SummaryEngineType.QWEN_LOCAL) {
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
        processing.value = false
        processingLabel.value = null
        processingProgress.value = null
        if (activeSessionId.value == operation.sessionId) activeSessionId.value = null
    }

    private fun parseSummaryEngine(value: String): SummaryEngineType =
        runCatching { SummaryEngineType.valueOf(value) }
            .getOrDefault(SummaryEngineType.EXTRACTIVE_KOTLIN)

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

    private data class CaptureStop(
        val token: String,
        val request: CompletableDeferred<Unit>,
    )

    private data class CaptureState(
        val listening: Boolean,
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

    private companion object {
        const val PREFERENCES = "ondevice-settings"
        const val KEY_STT = "stt-engine"
        const val KEY_SUMMARY = "summary-engine"
    }
}
