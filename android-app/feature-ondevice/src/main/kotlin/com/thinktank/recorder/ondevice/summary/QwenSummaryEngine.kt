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
import kotlinx.coroutines.withTimeout

class QwenSummaryEngine(
    context: Context,
    private val modelStore: ModelStore,
) : SummaryEngine {
    private val applicationContext = context.applicationContext
    private val resourceGuard = DeviceResourceGuard(applicationContext)
    private val integrityVerifier = ModelIntegrityVerifier(modelStore)
    private val client = QwenInferenceClient(applicationContext)

    override suspend fun summarize(transcript: String): LocalSummary {
        require(transcript.isNotBlank()) { "요약할 전사문이 없습니다" }
        return ResourceArbiter.withLease(NativeWorkload.QWEN_SUMMARY) {
            withTimeout(MAX_TOTAL_TIME_MS) {
                val descriptor = ModelCatalog.get(ModelId.QWEN_SUMMARY_KO)
                check(modelStore.snapshot(descriptor).ready) {
                    "Qwen 로컬 요약 모델이 설치되지 않았습니다"
                }
                integrityVerifier.requireValid(descriptor)
                resourceGuard.requireQwenCapacity()
                val modelFile = File(modelStore.installDir(descriptor.id), "model.gguf")
                client.summarize(modelFile.absolutePath, transcript)
            }
        }
    }

    private companion object {
        const val MAX_TOTAL_TIME_MS = 120_000L
    }
}
