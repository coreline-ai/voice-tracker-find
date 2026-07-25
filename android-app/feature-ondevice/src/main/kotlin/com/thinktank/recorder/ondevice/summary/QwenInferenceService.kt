package com.thinktank.recorder.ondevice.summary

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.modelpack.ModelIntegrityVerifier
import com.thinktank.recorder.ondevice.modelpack.ModelStore
import com.thinktank.recorder.ondevice.runtime.DeviceResourceGuard
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

class QwenInferenceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeRequest = AtomicReference<RunningRequest?>(null)
    private val store by lazy { ModelStore(this) }
    private val reducer = ExtractiveSummaryEngine(maxBullets = 12)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val binder = object : IQwenInferenceService.Stub() {
        override fun summarize(
            requestId: String,
            modelPath: String,
            transcript: String,
            callback: IQwenInferenceCallback,
        ) {
            if (activeRequest.get() != null) {
                callback.safeError(requestId, "다른 Qwen 요청이 실행 중입니다")
                return
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                var engine: InferenceEngine? = null
                try {
                    require(transcript.isNotBlank()) { "요약할 전사문이 없습니다" }
                    val descriptor = ModelCatalog.get(ModelId.QWEN_SUMMARY_KO)
                    val canonicalModel = File(store.installDir(descriptor.id), "model.gguf").canonicalFile
                    check(File(modelPath).canonicalFile == canonicalModel) { "허용되지 않은 Qwen 모델 경로입니다" }
                    ModelIntegrityVerifier(store).requireValid(descriptor)
                    DeviceResourceGuard(applicationContext).requireQwenCapacity()
                    engine = AiChat.getInferenceEngine(applicationContext)
                    prepareEngine(engine)
                    engine.loadModel(canonicalModel.absolutePath)
                    engine.setSystemPrompt(SYSTEM_PROMPT)
                    val prompt = buildUserPrompt(reduceInput(transcript))
                    val raw = engine.sendUserPrompt(prompt, PREDICT_TOKENS).toList().joinToString("")
                    val result = QwenOutputParser.parse(raw, transcript)
                    callback.safeSuccess(requestId, QwenSummaryCodec.encode(result))
                } catch (cancelled: CancellationException) {
                    callback.safeError(requestId, "Qwen 로컬 요약이 취소되었습니다")
                } catch (error: Throwable) {
                    callback.safeError(requestId, error.message ?: "Qwen 로컬 요약에 실패했습니다")
                } finally {
                    engine?.let(::destroyEngine)
                    activeRequest.set(null)
                    terminateProcessSoon()
                }
            }
            val request = RunningRequest(requestId, job)
            if (!activeRequest.compareAndSet(null, request)) {
                job.cancel()
                callback.safeError(requestId, "다른 Qwen 요청이 실행 중입니다")
            } else {
                job.start()
            }
        }

        override fun cancel(requestId: String) {
            val current = activeRequest.get()
            if (current?.id != requestId) return
            current.job.cancel()
            terminateProcessSoon(delayMs = 20)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
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
                error("Qwen 런타임 초기화 상태가 올바르지 않습니다")
            }
            is InferenceEngine.State.Initialized -> Unit
            else -> error("Qwen 런타임이 사용 중입니다: ${state.javaClass.simpleName}")
        }
    }

    private fun destroyEngine(engine: InferenceEngine) {
        runCatching {
            when (engine.state.value) {
                is InferenceEngine.State.ModelReady -> engine.cleanUp()
                else -> Unit
            }
        }
        runCatching { engine.destroy() }
    }

    private suspend fun reduceInput(transcript: String): String {
        val compact = transcript.replace(Regex("\\s+"), " ").trim()
        if (compact.length <= MAX_INPUT_CHARS) return compact
        val extracted = reducer.summarize(compact)
        return buildString {
            appendLine("[원문 앞부분]")
            appendLine(compact.take(1_500))
            appendLine("[중요 문장]")
            extracted.bullets.forEach { appendLine("- $it") }
            if (extracted.actionItems.isNotEmpty()) {
                appendLine("[명시된 할 일 후보]")
                extracted.actionItems.forEach { appendLine("- $it") }
            }
            appendLine("[원문 뒷부분]")
            append(compact.takeLast(800))
        }.take(MAX_INPUT_CHARS)
    }

    private fun buildUserPrompt(source: String): String = """
        아래 한국어 전사문만 근거로 정리하세요. /no_think
        원문에 없는 이름, 날짜, 수치, 담당자, 기한, 할 일을 만들지 마세요.
        할 일이 명시되지 않았으면 actionItems는 빈 배열로 반환하세요.
        마크다운 설명 없이 아래 JSON 객체 하나만 반환하세요.
        {"title":"짧은 제목","bullets":["핵심 1","핵심 2","핵심 3"],"actionItems":[]}

        <transcript>
        $source
        </transcript>
    """.trimIndent()

    private fun IQwenInferenceCallback.safeSuccess(requestId: String, json: String) {
        runCatching { onSuccess(requestId, json, Process.myPid()) }
    }

    private fun IQwenInferenceCallback.safeError(requestId: String, message: String) {
        runCatching { onError(requestId, message, Process.myPid()) }
    }

    private fun terminateProcessSoon(delayMs: Long = 150) {
        stopSelf()
        mainHandler.postDelayed(
            {
                Process.killProcess(Process.myPid())
            },
            delayMs,
        )
    }

    private data class RunningRequest(
        val id: String,
        val job: Job,
    )

    private companion object {
        const val MAX_INPUT_CHARS = 6_000
        const val PREDICT_TOKENS = 384
        val SYSTEM_PROMPT = """
            당신은 네트워크를 사용하지 않는 한국어 회의 기록 정리기입니다.
            제공된 전사문 밖의 사실을 추측하거나 보충하지 마세요.
            항상 유효한 UTF-8 JSON 객체 하나만 출력하세요.
        """.trimIndent()
    }
}
