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

    @Test
    fun longTranscriptKeepsLeadTopicAheadOfRepeatedMetaInstructions() {
        val leadTopic =
            "온디바이스 인공지능은 음성 파일과 전사 원문을 기기에서 처리하는 것을 목표로 합니다."
        val metaInstruction =
            "요약 모델은 근거만 사용해 제목과 핵심 문장을 생성해야 하고 입력 결과를 확인합니다."
        val transcript = buildString {
            append(leadTopic)
            repeat(12) {
                append(' ')
                append(metaInstruction)
                append(" 배터리와 메모리 사용량을 확인하고 처리 위치를 기록합니다.")
            }
            append(
                " 마지막으로 온디바이스 인공지능은 음성과 전사 원문을 기기에서 처리합니다.",
            )
        }

        val result = GemmaSummaryInputBuilder.build(transcript)

        assertTrue(result.source.length > 700)
        assertTrue(result.selectedEvidence.first().contains("온디바이스 인공지능"))
        assertTrue(result.selectedEvidence[1].contains("온디바이스 인공지능"))
    }
}
