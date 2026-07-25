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
}
