package com.thinktank.recorder.ondevice.summary

import com.thinktank.recorder.ondevice.api.SummaryEngineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenOutputParserTest {
    @Test
    fun parsesJsonWrappedByReasoningAndCodeFence() {
        val source = "민수는 금요일까지 견적서를 확인해야 합니다. 일정은 다음 주에 다시 논의합니다."
        val raw = """
            <think>internal reasoning</think>
            ```json
            {
              "title": "견적서 확인",
              "bullets": ["견적서를 확인한다.", "일정은 다음 주에 논의한다."],
              "actionItems": ["민수는 금요일까지 견적서를 확인한다.", "영희가 예산을 승인한다."]
            }
            ```
        """.trimIndent()

        val result = QwenOutputParser.parse(raw, source)

        assertEquals(SummaryEngineType.QWEN_LOCAL, result.engine)
        assertEquals("견적서 확인", result.title)
        assertEquals(2, result.bullets.size)
        assertEquals(listOf("민수는 금요일까지 견적서를 확인한다."), result.actionItems)
        assertEquals(64, result.sourceHash.length)
    }

    @Test
    fun rejectsIncompleteObject() {
        val failure = runCatching {
            QwenOutputParser.extractJsonObject("""{"title":"broken"""")
        }
        assertTrue(failure.isFailure)
    }

    @Test
    fun preservesCompactBulletsAndCapsTheThird() {
        val source = "쇼핑쇼츠 쿠팡 여성 의류 수익 경쟁 사례 영상 강의 판매 고객 전환 운영 전략"
        val raw = """
            {
              "title": "쇼핑쇼츠 운영 전략",
              "bullets": [
                "쇼핑쇼츠 영상으로 고객 전환을 설명한다.",
                "쿠팡과의 경쟁 구도를 사례로 든다.",
                "여성 의류 수익 사례를 중심으로 소개한다.",
                "강의 판매와 영상 콘텐츠를 연결한다.",
                "네 번째 항목은 저장하지 않는다."
              ],
              "actionItems": []
            }
        """.trimIndent()

        val result = QwenOutputParser.parse(raw, source)

        assertEquals(2, result.bullets.size)
        assertEquals("쇼핑쇼츠 영상으로 고객 전환을 설명한다.", result.bullets.first())
        assertEquals("쿠팡과의 경쟁 구도를 사례로 든다.", result.bullets.last())
    }
}
