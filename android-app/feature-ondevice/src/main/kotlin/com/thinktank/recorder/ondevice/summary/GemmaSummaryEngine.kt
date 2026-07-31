package com.thinktank.recorder.ondevice.summary

import android.content.Context
import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.api.SummaryEngine
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.modelpack.ModelIntegrityVerifier
import com.thinktank.recorder.ondevice.modelpack.ModelStore
import com.thinktank.recorder.ondevice.runtime.NativeWorkload
import com.thinktank.recorder.ondevice.runtime.ResourceArbiter
import java.io.File
import kotlinx.coroutines.withTimeout

class GemmaSummaryEngine(
    context: Context,
    private val modelStore: ModelStore,
) : SummaryEngine {
    private val client = GemmaInferenceClient(context.applicationContext)
    private val verifier = ModelIntegrityVerifier(modelStore)

    override suspend fun summarize(transcript: String): LocalSummary =
        ResourceArbiter.withLease(NativeWorkload.GEMMA_SUMMARY) {
            withTimeout(MAX_TOTAL_TIME_MS) {
                val descriptor = ModelCatalog.get(ModelId.GEMMA_SUMMARY_KO)
                check(modelStore.snapshot(descriptor).ready) {
                    "Gemma 3 1B 모델이 설치되지 않았습니다."
                }
                verifier.requireValid(descriptor)
                val modelFile = File(
                    modelStore.installDir(descriptor.id),
                    descriptor.requiredFiles.single(),
                )
                client.summarize(modelFile.absolutePath, transcript)
            }
        }

    private companion object {
        const val MAX_TOTAL_TIME_MS = 240_000L
    }
}
