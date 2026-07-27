package com.thinktank.recorder.ondevice.summary

import com.thinktank.recorder.ondevice.api.SummaryEngineType
import com.thinktank.recorder.ondevice.modelpack.ModelId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoStageSummaryPipelineTest {
    private val source = """
        쇼핑쇼츠 강의에서 실제 수강생의 판매 경험을 설명합니다.
        쿠팡 경쟁 대응법은 다음 영상에서 별도로 소개합니다.
        여성 의류 판매자는 상품 영상을 끝까지 확인해 달라고 요청합니다.
    """.trimIndent()

    @Test
    fun separatesEvidenceSelectionFromCompression() = runBlocking {
        val responses = ArrayDeque(
            listOf(
                """{"selectedIds":[1]}""",
                """{"title":"쇼핑쇼츠 강의","summary":["수강생의 쇼핑쇼츠 판매 경험을 설명한다."],"actionItems":[]}""",
            ),
        )
        val requests = mutableListOf<GenerationRequest>()

        val result = TwoStageSummaryPipeline().summarize(
            transcript = source,
            profile = LocalLlmProfiles.get(ModelId.QWEN_SUMMARY_KO),
            generator = LocalTextGenerator { request ->
                requests += request
                responses.removeFirst()
            },
        )

        assertEquals(2, requests.size)
        assertTrue(requests[0].prompt.contains("selectedIds"))
        assertTrue(requests[1].prompt.contains("선택된 원문"))
        assertEquals(SummaryEngineType.QWEN_LOCAL, result.engine)
        assertEquals(ModelId.QWEN_SUMMARY_KO.name, result.actualModelId)
        assertTrue(result.bullets.sumOf(String::length) <= 100)
    }

    @Test
    fun rejectsUnknownEvidenceIdBeforeSummaryGeneration() = runBlocking {
        var calls = 0
        val failure = runCatching {
            TwoStageSummaryPipeline().summarize(
                transcript = source,
                profile = LocalLlmProfiles.get(ModelId.QWEN_SUMMARY_KO),
                generator = LocalTextGenerator {
                    calls += 1
                    """{"selectedIds":[99]}"""
                },
            )
        }.exceptionOrNull()

        assertEquals(2, calls)
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun modelPromptsDoNotContainUnsupportedThinkingSwitches() {
        listOf(ModelId.QWEN_SUMMARY_KO, ModelId.EXAONE_SUMMARY_KO).forEach { id ->
            val prompt = LocalLlmProfiles.get(id).systemPrompt
            assertFalse(prompt.contains("/no_think"))
            assertFalse(prompt.contains("/nothink"))
            assertFalse(prompt.contains("/think"))
        }
    }
}
