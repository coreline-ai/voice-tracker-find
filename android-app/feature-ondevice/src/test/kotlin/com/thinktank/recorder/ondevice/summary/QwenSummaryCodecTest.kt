package com.thinktank.recorder.ondevice.summary

import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import org.junit.Assert.assertEquals
import org.junit.Test

class QwenSummaryCodecTest {
    @Test
    fun binderPayloadRoundTripsWithoutChangingSummary() {
        val summary = LocalSummary(
            title = "주간 회의",
            bullets = listOf("출시 일정을 확인했다", "테스트 범위를 합의했다"),
            actionItems = listOf("금요일까지 회귀 테스트"),
            engine = SummaryEngineType.QWEN_LOCAL,
            sourceHash = "abc123",
            policyVersion = 2,
            promptVersion = 2,
            modelVersion = "qwen-test",
            validationStatus = "PASSED",
            requestedModelId = "QWEN_SUMMARY_KO",
            actualModelId = "QWEN_SUMMARY_KO",
            runtimeType = "LLAMA_CPP",
            generationProfile = "qwen-greedy-json-v1",
            violationCodes = null,
            durationMs = 1_234,
            inputChars = 556,
            outputChars = 42,
        )

        assertEquals(summary, QwenSummaryCodec.decode(QwenSummaryCodec.encode(summary)))
    }
}
