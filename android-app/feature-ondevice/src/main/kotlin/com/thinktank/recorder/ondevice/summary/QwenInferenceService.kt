package com.thinktank.recorder.ondevice.summary

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.modelpack.ModelIntegrityVerifier
import com.thinktank.recorder.ondevice.modelpack.ModelRuntimeType
import com.thinktank.recorder.ondevice.modelpack.ModelStore
import com.thinktank.recorder.ondevice.runtime.DeviceResourceGuard
import com.thinktank.recorder.ondevice.summary.litert.GemmaLiteRtBridge
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

/**
 * Disposable local-LLM worker. The historical class/AIDL names are kept to preserve manifest and
 * instrumentation compatibility, but requests are explicitly model-scoped and support llama.cpp
 * (Qwen/EXAONE) and LiteRT-LM (Gemma).
 */
class QwenInferenceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeRequest = AtomicReference<RunningRequest?>(null)
    private val activeGemma = AtomicReference<GemmaLiteRtBridge?>(null)
    private val store by lazy { ModelStore(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val binder = object : IQwenInferenceService.Stub() {
        override fun summarize(
            requestId: String,
            modelId: String,
            modelPath: String,
            transcript: String,
            callback: IQwenInferenceCallback,
        ) {
            if (activeRequest.get() != null) {
                callback.safeError(requestId, "다른 로컬 AI 요청이 실행 중입니다")
                return
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                var llamaEngine: InferenceEngine? = null
                var gemmaBridge: GemmaLiteRtBridge? = null
                try {
                    require(transcript.isNotBlank()) { "요약할 전사문이 없습니다" }
                    check(transcript.length <= QWEN_MAX_INPUT_CHARS) {
                        "로컬 AI 입력이 허용 크기를 초과했습니다"
                    }
                    val id = runCatching { ModelId.valueOf(modelId) }
                        .getOrElse { error("지원하지 않는 로컬 AI 모델입니다") }
                    val profile = LocalLlmProfiles.get(id)
                    val descriptor = ModelCatalog.get(id)
                    val installedName = descriptor.requiredFiles.single()
                    val canonicalModel =
                        File(store.installDir(descriptor.id), installedName).canonicalFile
                    check(File(modelPath).canonicalFile == canonicalModel) {
                        "허용되지 않은 로컬 AI 모델 경로입니다"
                    }
                    ModelIntegrityVerifier(store).requireValid(descriptor)
                    DeviceResourceGuard(applicationContext).requireLocalLlmCapacity()

                    val startedAt = SystemClock.elapsedRealtime()
                    val summary = when (profile.runtimeType) {
                        ModelRuntimeType.LLAMA_CPP -> {
                            llamaEngine = AiChat.getInferenceEngine(applicationContext)
                            prepareEngine(requireNotNull(llamaEngine))
                            requireNotNull(llamaEngine).loadModel(canonicalModel.absolutePath)
                            requireNotNull(llamaEngine).setSystemPrompt(profile.systemPrompt)
                            TwoStageSummaryPipeline().summarize(
                                transcript = transcript,
                                profile = profile,
                                generator = LocalTextGenerator { request ->
                                    requireNotNull(llamaEngine)
                                        .sendUserPrompt(
                                            message = request.prompt,
                                            predictLength = request.maxTokens,
                                            generationConfig = profile.generationConfig.copy(
                                                grammar = request.grammar,
                                            ),
                                        )
                                        .toList()
                                        .joinToString("")
                                },
                            )
                        }

                        ModelRuntimeType.LITERT_LM -> {
                            gemmaBridge = GemmaLiteRtBridge(
                                canonicalModel.absolutePath,
                                File(cacheDir, "litertlm").absolutePath,
                                profile.systemPrompt,
                            ).also(activeGemma::set)
                            TwoStageSummaryPipeline().summarize(
                                transcript = transcript,
                                profile = profile,
                                generator = LocalTextGenerator { request ->
                                    // LiteRT-LM 0.14 has sampler control but no public GBNF API.
                                    // The same strict parser and provenance gate remain authoritative.
                                    requireNotNull(gemmaBridge).generate(request.prompt)
                                },
                            )
                        }

                        ModelRuntimeType.SHERPA_ONNX ->
                            error("STT 모델은 요약에 사용할 수 없습니다")
                    }.copy(
                        durationMs = SystemClock.elapsedRealtime() - startedAt,
                        inputChars = transcript.length,
                    )
                    callback.safeSuccess(requestId, QwenSummaryCodec.encode(summary))
                } catch (cancelled: CancellationException) {
                    callback.safeError(requestId, "로컬 AI 요약이 취소되었습니다")
                } catch (rejected: FinalLocalLlmOutputRejectedException) {
                    callback.safeError(
                        requestId,
                        "$QWEN_QUALITY_REJECTED:${rejected.reason}",
                    )
                } catch (error: Throwable) {
                    callback.safeError(requestId, LOCAL_LLM_RUNTIME_FAILED)
                } finally {
                    activeGemma.compareAndSet(gemmaBridge, null)
                    runCatching { gemmaBridge?.close() }
                    llamaEngine?.let(::destroyEngine)
                    terminateProcess()
                }
            }
            val request = RunningRequest(requestId, job)
            if (!activeRequest.compareAndSet(null, request)) {
                job.cancel()
                callback.safeError(requestId, "다른 로컬 AI 요청이 실행 중입니다")
            } else {
                job.start()
            }
        }

        override fun cancel(requestId: String) {
            val current = activeRequest.get()
            if (current?.id != requestId) return
            runCatching { activeGemma.get()?.cancel() }
            current.job.cancel()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { activeGemma.getAndSet(null)?.cancel() }
        activeRequest.getAndSet(null)?.job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun prepareEngine(engine: InferenceEngine) {
        when (engine.state.value) {
            InferenceEngine.State.Uninitialized,
            InferenceEngine.State.Initializing,
            -> engine.state.first {
                it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
            }

            else -> Unit
        }
        when (val state = engine.state.value) {
            is InferenceEngine.State.Error,
            is InferenceEngine.State.ModelReady,
            -> {
                runCatching { engine.destroy() }
                error("로컬 AI 런타임 초기화 상태가 올바르지 않습니다")
            }

            is InferenceEngine.State.Initialized -> Unit
            else -> error("로컬 AI 런타임이 사용 중입니다: ${state.javaClass.simpleName}")
        }
    }

    private fun destroyEngine(engine: InferenceEngine) {
        runCatching {
            if (engine.state.value is InferenceEngine.State.ModelReady) engine.cleanUp()
        }
        runCatching { engine.destroy() }
    }

    private fun IQwenInferenceCallback.safeSuccess(requestId: String, json: String) {
        runCatching { onSuccess(requestId, json, Process.myPid()) }
    }

    private fun IQwenInferenceCallback.safeError(requestId: String, message: String) {
        runCatching { onError(requestId, message, Process.myPid()) }
    }

    private fun terminateProcess() {
        stopSelf()
        mainHandler.post { Process.killProcess(Process.myPid()) }
    }

    private data class RunningRequest(
        val id: String,
        val job: Job,
    )
}
