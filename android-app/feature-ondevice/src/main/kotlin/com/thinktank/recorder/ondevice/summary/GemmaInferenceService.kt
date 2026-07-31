package com.thinktank.recorder.ondevice.summary

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.modelpack.ModelIntegrityVerifier
import com.thinktank.recorder.ondevice.modelpack.ModelStore
import com.thinktank.recorder.ondevice.runtime.DeviceResourceGuard
import com.thinktank.recorder.ondevice.summary.litert.GemmaLiteRtBridge
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GemmaInferenceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeRequest = AtomicReference<RunningRequest?>(null)
    private val activeBridge = AtomicReference<GemmaLiteRtBridge?>(null)
    private val store by lazy { ModelStore(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val binder = object : IGemmaInferenceService.Stub() {
        override fun summarize(
            requestId: String,
            modelPath: String,
            transcript: String,
            callback: IGemmaInferenceCallback,
        ) {
            if (activeRequest.get() != null) {
                callback.safeError(requestId, "다른 Gemma 요약이 실행 중입니다.")
                return
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                var bridge: GemmaLiteRtBridge? = null
                try {
                    require(transcript.isNotBlank()) { "요약할 전사 원문이 없습니다." }
                    require(transcript.length <= MAX_INPUT_CHARS) {
                        "Gemma 입력 한도를 넘었습니다. 현재 전사 ${transcript.length}자"
                    }
                    val descriptor = ModelCatalog.get(ModelId.GEMMA_SUMMARY_KO)
                    val canonicalModel =
                        File(store.installDir(descriptor.id), "model.litertlm").canonicalFile
                    check(File(modelPath).canonicalFile == canonicalModel) {
                        "허용되지 않은 Gemma 모델 경로입니다."
                    }
                    ModelIntegrityVerifier(store).requireValid(descriptor)
                    DeviceResourceGuard(applicationContext).requireGemmaCapacity()

                    val startedAt = SystemClock.elapsedRealtime()
                    val input = GemmaSummaryInputBuilder.build(transcript)
                    val inferenceBridge = GemmaLiteRtBridge(
                        canonicalModel.absolutePath,
                        File(cacheDir, "litertlm").absolutePath,
                        SYSTEM_PROMPT,
                    ).also {
                        bridge = it
                        activeBridge.set(it)
                    }
                    var rawOutput = inferenceBridge.generate(buildPrompt(input))
                    val first = runCatching {
                        GemmaSummaryParser.parse(
                            rawOutput = rawOutput,
                            sourceHash = sourceHash(input.source),
                            source = input.source,
                            selectedEvidence = input.selectedEvidence,
                        )
                    }
                    val parsed = first.getOrElse {
                        rawOutput = inferenceBridge.generate(buildCorrectionPrompt(input))
                        GemmaSummaryParser.parse(
                            rawOutput = rawOutput,
                            sourceHash = sourceHash(input.source),
                            source = input.source,
                            selectedEvidence = input.selectedEvidence,
                        )
                    }
                    val summary = parsed.copy(
                        modelVersion = descriptor.version,
                        requestedModelId = descriptor.id.name,
                        actualModelId = descriptor.id.name,
                        runtimeType = descriptor.runtimeType.name,
                        generationProfile = GENERATION_PROFILE,
                        durationMs = SystemClock.elapsedRealtime() - startedAt,
                        inputChars = input.promptSource.length,
                        outputChars = rawOutput.length,
                    )
                    activeBridge.compareAndSet(inferenceBridge, null)
                    inferenceBridge.close()
                    bridge = null
                    callback.safeSuccess(requestId, GemmaSummaryCodec.encode(summary))
                } catch (cancelled: CancellationException) {
                    callback.safeError(requestId, "Gemma 요약이 취소되었습니다.")
                } catch (error: Throwable) {
                    callback.safeError(
                        requestId,
                        error.message?.takeIf(String::isNotBlank)
                            ?: "Gemma 3 1B 요약에 실패했습니다.",
                    )
                } finally {
                    activeBridge.compareAndSet(bridge, null)
                    runCatching { bridge?.close() }
                    activeRequest.set(null)
                    stopSelf()
                    mainHandler.post { Process.killProcess(Process.myPid()) }
                }
            }
            val request = RunningRequest(requestId, job)
            if (!activeRequest.compareAndSet(null, request)) {
                job.cancel()
                callback.safeError(requestId, "다른 Gemma 요약이 실행 중입니다.")
            } else {
                job.start()
            }
        }

        override fun cancel(requestId: String) {
            val request = activeRequest.get()
            if (request?.id != requestId) return
            runCatching { activeBridge.get()?.cancel() }
            request.job.cancel()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { activeBridge.getAndSet(null)?.cancel() }
        activeRequest.getAndSet(null)?.job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildPrompt(input: GemmaSummaryInput): String = """
        아래 원문의 중심 내용을 10~60자의 짧고 완결된 한국어 문장 하나로 압축하세요.
        원문의 단어와 사실만 사용하고, 원문에 없는 이름·숫자·영문 용어를 만들지 마세요.
        문장은 '다' 또는 '합니다'로 끝내세요.
        제목, 항목 번호, 설명을 쓰지 마세요.
        출력은 JSON 객체 하나이며 키 이름은 summary, 값은 실제 요약 문장입니다.
        지시문에 나온 표현을 답으로 복사하지 마세요.

        [원문]
        ${input.promptSource}
    """.trimIndent()

    private fun buildCorrectionPrompt(input: GemmaSummaryInput): String = """
        아래 원문에서 실제 핵심 사실만 다시 한 문장으로 작성하세요.
        10~60자의 한국어 완결문이며 '다' 또는 '합니다'로 끝내세요.
        원문에 없는 사실을 추가하지 마세요.
        JSON 객체 하나만 출력하세요. 키는 summary 하나만 사용하세요.

        [원문]
        ${input.promptSource}
    """.trimIndent()

    private fun sourceHash(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun IGemmaInferenceCallback.safeSuccess(requestId: String, json: String) {
        runCatching { onSuccess(requestId, json, Process.myPid()) }
    }

    private fun IGemmaInferenceCallback.safeError(requestId: String, message: String) {
        runCatching { onError(requestId, message, Process.myPid()) }
    }

    private data class RunningRequest(
        val id: String,
        val job: Job,
    )

    private companion object {
        const val MAX_INPUT_CHARS = 10_000
        const val GENERATION_PROFILE = "gemma3-1b-litert-cpu8-grounded-v4"
        const val SYSTEM_PROMPT =
            "한국어 전사 원문에 근거한 짧은 요약만 생성하고 JSON 객체 하나만 출력하세요."
    }
}
