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
                    check(transcript.length <= QWEN_MAX_INPUT_CHARS) { "Qwen 입력이 허용 크기를 초과했습니다" }
                    val prompt = buildUserPrompt(transcript)
                    val raw = engine.sendUserPrompt(prompt, PREDICT_TOKENS).toList().joinToString("")
                    val result = QwenOutputParser.parse(raw, transcript)
                    callback.safeSuccess(requestId, QwenSummaryCodec.encode(result))
                } catch (cancelled: CancellationException) {
                    callback.safeError(requestId, "Qwen 로컬 요약이 취소되었습니다")
                } catch (error: Throwable) {
                    callback.safeError(requestId, error.message ?: "Qwen 로컬 요약에 실패했습니다")
                } finally {
                    engine?.let(::destroyEngine)
                    terminateProcess()
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

    private fun buildUserPrompt(source: String): String = """
        아래 한국어 전사문만 근거로 정리하세요. /no_think
        원문에 없는 이름, 날짜, 수치, 담당자, 기한, 할 일을 만들지 마세요.
        bullets는 핵심이 있는 만큼 1~2개만 작성하고 각 항목은 짧은 한 문장(30자 이내)으로 만드세요.
        각 항목에는 원문의 고유한 주제어·사례·수치·비교 대상 중 하나 이상을 포함하세요.
        "운영 가이드라인", "성공 사례 분석", "계획 수립"처럼 원문만으로 구분되지 않는
        일반 문장만 쓰지 마세요. 서로 다른 세부 내용을 짧고 구체적으로 정리하세요.
        bullet 앞에 "분석:", "결과:", "핵심:" 같은 소제목을 붙이지 말고 제목을 반복하지 마세요.
        원문 정보가 부족하면 억지로 항목을 만들지 말고, 확인 가능한 항목만 반환하세요.
        할 일이 명시되지 않았으면 actionItems는 빈 배열로 반환하세요.
        마크다운 설명 없이 아래 JSON 객체 하나만 반환하세요.
        {"title":"짧은 제목","bullets":["짧은 구체 핵심 1","짧은 구체 핵심 2"],"actionItems":[]}

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

    private fun terminateProcess() {
        stopSelf()
        // Callback delivery has returned before finally runs. Cleanup is complete here, so the
        // client may use Binder death as the single completion barrier for the host lease.
        mainHandler.post { Process.killProcess(Process.myPid()) }
    }

    private data class RunningRequest(
        val id: String,
        val job: Job,
    )

    private companion object {
        // A compact one-to-two-row JSON summary fits comfortably in this bound. A lower ceiling
        // prevents needless generation after the completed JSON object and reduces timeout risk.
        const val PREDICT_TOKENS = 384
        val SYSTEM_PROMPT = """
            /no_think
            당신은 네트워크를 사용하지 않는 한국어 회의 기록 정리기입니다.
            제공된 전사문 밖의 사실을 추측하거나 보충하지 마세요.
            항상 유효한 UTF-8 JSON 객체 하나만 출력하세요.
        """.trimIndent()
    }
}
