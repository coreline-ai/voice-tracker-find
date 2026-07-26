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
                    val segments = KoreanTranscriptSegmenter().segment(transcript)
                    check(segments.isNotEmpty()) { "요약할 원문 구간이 없습니다" }
                    val first = runCatching {
                        generateAndParse(
                            engine = engine,
                            prompt = buildUserPrompt(transcript, segments),
                            transcript = transcript,
                            segments = segments,
                        )
                    }
                    val parsed = first.getOrElse { firstError ->
                        if (firstError !is QwenOutputRejectedException) throw firstError
                        runCatching {
                            generateAndParse(
                                engine = engine,
                                prompt = buildCorrectionPrompt(firstError.reason),
                                transcript = transcript,
                                segments = segments,
                            )
                        }.getOrElse { secondError ->
                            if (secondError is QwenOutputRejectedException) {
                                throw QwenFinalOutputRejectedException(secondError.reason)
                            }
                            throw secondError
                        }
                    }
                    val result = parsed.summary.copy(
                        modelVersion = descriptor.version,
                        policyVersion = SummaryPolicy.VERSION,
                        promptVersion = SummaryPolicy.PROMPT_VERSION,
                        validationStatus = SUMMARY_VALIDATION_PASSED,
                    )
                    callback.safeSuccess(requestId, QwenSummaryCodec.encode(result))
                } catch (cancelled: CancellationException) {
                    callback.safeError(requestId, "Qwen 로컬 요약이 취소되었습니다")
                } catch (rejected: QwenFinalOutputRejectedException) {
                    callback.safeError(
                        requestId,
                        "$QWEN_QUALITY_REJECTED:${rejected.reason}",
                    )
                } catch (error: Throwable) {
                    callback.safeError(requestId, QWEN_RUNTIME_FAILED)
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

    private suspend fun generateAndParse(
        engine: InferenceEngine,
        prompt: String,
        transcript: String,
        segments: List<SourceSegment>,
    ): ParsedQwenSummary {
        val raw = engine.sendUserPrompt(prompt, PREDICT_TOKENS).toList().joinToString("")
        return try {
            QwenOutputParser.parse(raw, transcript, segments)
        } catch (error: Throwable) {
            throw QwenOutputRejectedException(rejectionReason(error), error)
        }
    }

    private fun buildUserPrompt(
        source: String,
        segments: List<SourceSegment>,
    ): String {
        val totalBudget = SummaryPolicy.totalBudget(source)
        val facts = segments.joinToString("\n") { segment ->
            """<segment id="${segment.id}">${escapeXml(segment.text)}</segment>"""
        }
        return """
            아래 한국어 전사 구간만 근거로 핵심을 정리하세요. /no_think
            기본은 summary 1개입니다. 서로 독립된 핵심이 명확할 때만 2개를 쓰세요.
            summary 전체는 ${totalBudget}자 이내, 각 text는 ${SummaryPolicy.MAX_BULLET_CHARS}자 이내의
            완결된 한국어 문장으로 작성하세요. 문장을 자르거나 말줄임표를 쓰지 마세요.
            제목을 반복하거나 "성공 사례 분석", "운영 전략", "계획 수립" 같은 일반 문장만 쓰지 마세요.
            원문에 없는 이름, 날짜, 수치, 비교 결과, 성과, 담당자, 기한, 할 일을 만들지 마세요.
            각 summary에는 직접 근거가 된 segment 번호를 evidenceIds에 넣으세요.
            할 일이 명시되지 않았으면 actionItems는 빈 배열로 반환하세요.
            마크다운 없이 아래 구조의 JSON 객체 하나만 반환하세요.
            {"title":"28자 이내 제목","summary":[{"text":"완결된 핵심 문장","evidenceIds":[1]}],"actionItems":[]}

            <transcript>
            $facts
            </transcript>
        """.trimIndent()
    }

    private fun buildCorrectionPrompt(reason: String): String {
        return """
            이전 결과는 앱 품질 검사에서 거절되었습니다: $reason
            이전 설명을 반복하지 말고 JSON 객체만 다시 작성하세요.
            summary는 기본 1개, 최대 2개이며 완결된 문장이어야 합니다.
            말줄임표를 쓰지 말고, 전체 ${SummaryPolicy.MAX_TOTAL_CHARS}자와
            항목당 ${SummaryPolicy.MAX_BULLET_CHARS}자를 넘지 마세요.
            각 항목의 evidenceIds는 실제 원문 segment 번호만 사용하세요.
            {"title":"짧은 제목","summary":[{"text":"짧고 완결된 핵심 문장","evidenceIds":[1]}],"actionItems":[]}
        """.trimIndent()
    }

    private fun rejectionReason(error: Throwable): String =
        (error as? SummaryQualityException)
            ?.validation
            ?.correctionHint()
            ?: "INVALID_JSON_OR_SCHEMA"

    private fun escapeXml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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

    private class QwenOutputRejectedException(
        val reason: String,
        cause: Throwable,
    ) : IllegalArgumentException(reason, cause)

    private class QwenFinalOutputRejectedException(
        val reason: String,
    ) : IllegalArgumentException(reason)

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
