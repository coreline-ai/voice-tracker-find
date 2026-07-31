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
        summarizeBatch(listOf(transcript)).single()

    suspend fun summarizeBatch(
        transcripts: List<String>,
        onSummary: suspend (index: Int, summary: LocalSummary) -> Unit = { _, _ -> },
    ): List<LocalSummary> =
        ResourceArbiter.withLease(NativeWorkload.GEMMA_SUMMARY) {
            withTimeout(MAX_SETUP_TIME_MS + MAX_NODE_TIME_MS * transcripts.size.coerceAtLeast(1)) {
                val descriptor = ModelCatalog.get(ModelId.GEMMA_SUMMARY_KO)
                check(modelStore.snapshot(descriptor).ready) {
                    "Gemma 3 1B 모델이 설치되지 않았습니다."
                }
                verifier.requireValid(descriptor)
                val modelFile = File(
                    modelStore.installDir(descriptor.id),
                    descriptor.requiredFiles.single(),
                )
                client.summarizeBatch(modelFile.absolutePath, transcripts, onSummary)
            }
        }

    suspend fun <T> withBatch(
        expectedNodeCount: Int,
        block: suspend (summarize: suspend (String) -> LocalSummary) -> T,
    ): T = ResourceArbiter.withLease(NativeWorkload.GEMMA_SUMMARY) {
        withTimeout(MAX_SETUP_TIME_MS + MAX_NODE_TIME_MS * expectedNodeCount.coerceAtLeast(1)) {
            val descriptor = ModelCatalog.get(ModelId.GEMMA_SUMMARY_KO)
            check(modelStore.snapshot(descriptor).ready) {
                "Gemma 3 1B 모델이 설치되지 않았습니다."
            }
            verifier.requireValid(descriptor)
            val modelFile = File(
                modelStore.installDir(descriptor.id),
                descriptor.requiredFiles.single(),
            )
            client.withBatch(modelFile.absolutePath, block)
        }
    }

    private companion object {
        const val MAX_SETUP_TIME_MS = 60_000L
        const val MAX_NODE_TIME_MS = 240_000L
    }
}
