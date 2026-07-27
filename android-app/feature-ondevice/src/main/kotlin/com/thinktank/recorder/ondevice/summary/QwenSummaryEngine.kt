package com.thinktank.recorder.ondevice.summary

import android.content.Context
import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.api.SummaryEngine
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.modelpack.ModelIntegrityVerifier
import com.thinktank.recorder.ondevice.modelpack.ModelStore
import com.thinktank.recorder.ondevice.runtime.DeviceResourceGuard
import com.thinktank.recorder.ondevice.runtime.NativeWorkload
import com.thinktank.recorder.ondevice.runtime.ResourceArbiter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class LocalLlmSummaryEngine(
    context: Context,
    private val modelStore: ModelStore,
    private val modelId: ModelId,
) : SummaryEngine {
    private val applicationContext = context.applicationContext
    private val resourceGuard = DeviceResourceGuard(applicationContext)
    private val integrityVerifier = ModelIntegrityVerifier(modelStore)
    private val client = QwenInferenceClient(applicationContext)
    private val inputReducer = QwenInputReducer()
    private val qualityGate = SummaryQualityGate()

    override suspend fun summarize(transcript: String): LocalSummary {
        require(transcript.isNotBlank()) { "요약할 전사문이 없습니다" }
        return ResourceArbiter.withLease(NativeWorkload.QWEN_SUMMARY) {
            try {
                withTimeout(MAX_TOTAL_TIME_MS) {
                    val descriptor = ModelCatalog.get(modelId)
                    check(modelStore.snapshot(descriptor).ready) {
                        "${descriptor.displayName} 모델이 설치되지 않았습니다"
                    }
                    integrityVerifier.requireValid(descriptor)
                    resourceGuard.requireLocalLlmCapacity()
                    val modelFile = File(
                        modelStore.installDir(descriptor.id),
                        descriptor.requiredFiles.single(),
                    )
                    val promptInput = inputReducer.reduce(transcript)
                    qualityGate.requireValid(
                        summary = client.summarize(
                            modelId = modelId,
                            modelPath = modelFile.absolutePath,
                            transcript = promptInput,
                            originalSourceHash = sourceHash(transcript),
                        ),
                        transcript = transcript,
                    ).copy(
                        sourceHash = sourceHash(transcript),
                        modelVersion = descriptor.version,
                        policyVersion = SummaryPolicy.VERSION,
                        promptVersion = SummaryPolicy.PROMPT_VERSION,
                        validationStatus = SUMMARY_VALIDATION_PASSED,
                        requestedModelId = modelId.name,
                        actualModelId = modelId.name,
                        runtimeType = descriptor.runtimeType.name,
                        inputChars = transcript.length,
                    )
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    client.awaitActiveDrain(PROCESS_DRAIN_TIMEOUT_MS)
                }
                throw cancelled
            }
        }
    }

    private companion object {
        const val MAX_TOTAL_TIME_MS = 180_000L
        const val PROCESS_DRAIN_TIMEOUT_MS = 5_000L
    }
}

/** Source-compatible wrapper retained for existing tests and callers. */
class QwenSummaryEngine(
    context: Context,
    modelStore: ModelStore,
) : SummaryEngine by LocalLlmSummaryEngine(context, modelStore, ModelId.QWEN_SUMMARY_KO)
