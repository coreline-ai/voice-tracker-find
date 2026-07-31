package com.thinktank.recorder.ondevice.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaSummaryInputTest {
    @Test
    fun longTranscriptUsesAtMostTwoBoundedEvidenceSegments() {
        val transcript = List(20) { index ->
            if (index == 11) {
                "핵심 결론으로 인공지능 에이전트는 개인 데이터를 활용할 필요가 있습니다."
            } else {
                "${index + 1}번째 일반 설명을 이어가며 관련 배경을 길게 이야기합니다."
            }
        }.joinToString(" ")

        val result = GemmaSummaryInputBuilder.build(transcript)

        assertEquals(
            GemmaSummaryInputBuilder.normalize(transcript),
            result.source,
        )
        assertTrue(result.selectedEvidence.size <= 2)
        assertTrue(result.selectedEvidence.sumOf(String::length) <= 700)
        assertTrue(result.promptSource.contains("인공지능 에이전트"))
    }
}
