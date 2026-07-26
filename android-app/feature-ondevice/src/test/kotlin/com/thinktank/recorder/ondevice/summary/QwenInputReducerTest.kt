package com.thinktank.recorder.ondevice.summary

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenInputReducerTest {
    @Test
    fun largeTranscriptIsCompactedBeforeBinderBoundary() = runBlocking {
        val transcript = buildString {
            repeat(2_000) { index ->
                append("회의 $index 에서 일정과 담당자를 확인해야 합니다. ")
            }
        }

        val reduced = QwenInputReducer().reduce(transcript)

        assertTrue(reduced.length <= QWEN_MAX_INPUT_CHARS)
        assertTrue(reduced.contains("[원문 앞부분]"))
        assertTrue(reduced.contains("[원문 뒷부분]"))
    }

    @Test
    fun smallTranscriptStaysCompactWithoutChangingWords() = runBlocking {
        val transcript = "  다음 주 일정 확인  "

        assertEquals("다음 주 일정 확인", QwenInputReducer().reduce(transcript))
    }
}
