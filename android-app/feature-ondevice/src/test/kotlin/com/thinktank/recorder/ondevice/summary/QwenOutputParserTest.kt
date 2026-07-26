package com.thinktank.recorder.ondevice.summary

import com.thinktank.recorder.ondevice.api.SummaryEngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenOutputParserTest {
    @Test
    fun parsesJsonWrappedByReasoningAndCodeFence() {
        val source = """
            민수는 금요일까지 신규 제품 견적서를 확인해야 합니다.
            일정은 다음 주 회의에서 다시 논의합니다.
            출시 전에 회귀 테스트 범위도 함께 확인합니다.
        """.trimIndent()
        val raw = """
            <think>internal reasoning</think>
            ```json
            {
              "title": "견적서 확인",
              "summary": [
                {"text":"민수는 신규 제품 견적서를 확인한다.","evidenceIds":[1]}
              ],
              "actionItems": ["민수는 금요일까지 견적서를 확인한다.", "영희가 예산을 승인한다."]
            }
            ```
        """.trimIndent()

        val result = QwenOutputParser.parse(raw, source)

        assertEquals(SummaryEngineType.QWEN_LOCAL, result.summary.engine)
        assertEquals("견적서 확인", result.summary.title)
        assertEquals(listOf("민수는 신규 제품 견적서를 확인한다."), result.summary.bullets)
        assertEquals(listOf("민수는 금요일까지 견적서를 확인한다."), result.summary.actionItems)
        assertEquals(listOf(setOf(1)), result.evidenceIds)
        assertEquals(64, result.summary.sourceHash.length)
    }

    @Test
    fun rejectsIncompleteObject() {
        val failure = runCatching {
            QwenOutputParser.extractJsonObject("""{"title":"broken"""")
        }
        assertTrue(failure.isFailure)
    }

    @Test
    fun rejectsMoreThanTwoSummaryRowsInsteadOfSilentlySlicingThem() {
        val source = """
            쇼핑쇼츠 강의에서 고객 전환 사례를 설명합니다.
            쿠팡 경쟁 구도는 후속 영상에서 소개합니다.
            여성 의류 판매 경험도 별도 자료로 공유합니다.
            강의와 영상 콘텐츠의 관계를 구체적으로 설명합니다.
        """.trimIndent()
        val raw = """
            {
              "title": "쇼핑쇼츠 강의",
              "summary": [
                {"text":"쇼핑쇼츠 강의의 고객 전환 사례를 설명한다.","evidenceIds":[1]},
                {"text":"쿠팡 경쟁 구도는 후속 영상에서 소개한다.","evidenceIds":[2]},
                {"text":"여성 의류 판매 경험은 별도 자료로 공유한다.","evidenceIds":[3]}
              ],
              "actionItems": []
            }
        """.trimIndent()

        val failure = runCatching { QwenOutputParser.parse(raw, source) }

        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("TOO_MANY_BULLETS"))
    }
}
