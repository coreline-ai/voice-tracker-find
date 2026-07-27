package com.thinktank.recorder.ondevice.summary

import com.arm.aichat.GenerationConfig
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.modelpack.ModelRuntimeType

internal data class LocalLlmProfile(
    val modelId: ModelId,
    val engineType: SummaryEngineType,
    val runtimeType: ModelRuntimeType,
    val systemPrompt: String,
    val generationProfile: String,
    val selectionTokens: Int,
    val summaryTokens: Int,
    val generationConfig: GenerationConfig,
)

internal object LocalLlmProfiles {
    fun get(modelId: ModelId): LocalLlmProfile {
        val descriptor = ModelCatalog.get(modelId)
        return when (modelId) {
            ModelId.QWEN_SUMMARY_KO -> LocalLlmProfile(
                modelId = modelId,
                engineType = SummaryEngineType.QWEN_LOCAL,
                runtimeType = descriptor.runtimeType,
                systemPrompt = """
                    당신은 기기 안에서만 실행되는 한국어 기록 정리기입니다.
                    제공된 원문 구간 밖의 사실을 추측하거나 보충하지 마세요.
                    요청한 JSON 객체 하나만 출력하세요.
                """.trimIndent(),
                generationProfile = "qwen-greedy-json-v1",
                selectionTokens = 48,
                summaryTokens = 160,
                generationConfig = GenerationConfig(
                    temperature = 0.0f,
                    topK = 1,
                    topP = 1.0f,
                    minP = 0.0f,
                    repeatPenalty = 1.05f,
                    presencePenalty = 0.0f,
                    seed = 1L,
                    stopAtJsonObjectEnd = true,
                ),
            )

            ModelId.EXAONE_SUMMARY_KO -> LocalLlmProfile(
                modelId = modelId,
                engineType = SummaryEngineType.EXAONE_LOCAL,
                runtimeType = descriptor.runtimeType,
                systemPrompt = """
                    당신은 한국어 전사 기록을 사실에 근거해 짧게 정리하는 도우미입니다.
                    원문에 없는 사실을 추가하지 말고 요청한 JSON 객체 하나만 출력하세요.
                """.trimIndent(),
                generationProfile = "exaone-greedy-json-v1",
                selectionTokens = 48,
                summaryTokens = 128,
                generationConfig = GenerationConfig(
                    temperature = 0.0f,
                    topK = 1,
                    topP = 1.0f,
                    minP = 0.0f,
                    repeatPenalty = 1.05f,
                    presencePenalty = 0.0f,
                    seed = 1L,
                    stopAtJsonObjectEnd = true,
                ),
            )

            ModelId.GEMMA_SUMMARY_KO -> LocalLlmProfile(
                modelId = modelId,
                engineType = SummaryEngineType.GEMMA_LOCAL,
                runtimeType = descriptor.runtimeType,
                systemPrompt = """
                    You summarize Korean transcripts locally. Use only the supplied source.
                    Return exactly one JSON object and never add unsupported facts.
                """.trimIndent(),
                generationProfile = "gemma-litert-cpu-greedy-v1",
                selectionTokens = 48,
                summaryTokens = 160,
                generationConfig = GenerationConfig(
                    temperature = 0.0f,
                    topK = 1,
                    topP = 1.0f,
                    minP = 0.0f,
                    seed = 1L,
                    stopAtJsonObjectEnd = true,
                ),
            )

            ModelId.SENSEVOICE_STT_KO -> error("STT 모델은 요약에 사용할 수 없습니다")
        }
    }
}
