package com.coreline.ai.voice.ondevice.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HierarchicalSummaryProjectionTest {
    @Test
    fun projectionKeepsRootAndBoundedDistinctSectionHighlights() {
        val result = HierarchicalSummaryProjection.build(
            rootSummary = "회의에서는 출시 일정과 품질 검증 기준을 확정했습니다.",
            sectionSummaries = listOf(
                "회의에서는 출시 일정과 품질 검증 기준을 확정했습니다.",
                "첫 구간에서는 사용자 요구사항과 범위를 정리했습니다.",
                "두 번째 구간에서는 데이터 이전 방법을 검토했습니다.",
                "세 번째 구간에서는 실기기 테스트 순서를 결정했습니다.",
                "네 번째 구간에서는 오류 복구 정책을 확인했습니다.",
                "마지막 구간에서는 배포 전 확인 항목을 합의했습니다.",
            ),
            maxSectionHighlights = 3,
        )

        assertEquals(4, result.bullets.size)
        assertEquals(3, result.sectionHighlightCount)
        assertEquals("회의에서는 출시 일정과 품질 검증 기준을 확정했습니다.", result.bullets.first())
        assertEquals(result.bullets.size, result.bullets.distinct().size)
    }

    @Test
    fun shortProjectionDoesNotDuplicateTheOnlySection() {
        val summary = "녹음에서 모델 설치 절차를 설명했습니다."
        val result = HierarchicalSummaryProjection.build(summary, listOf(summary))

        assertEquals(listOf(summary), result.bullets)
        assertEquals(0, result.sectionHighlightCount)
    }

    @Test
    fun groundingReturnsOnlyChildrenThatSupportTheSummary() {
        val result = HierarchicalSummaryGrounding.evaluate(
            summary = "팀은 출시 일정과 실기기 검증 계획을 확정했습니다.",
            candidates = listOf(
                SummaryEvidenceCandidate(
                    id = "schedule",
                    text = "팀은 출시 일정을 정하고 실기기 검증 계획을 확정했습니다.",
                ),
                SummaryEvidenceCandidate(
                    id = "storage",
                    text = "저장공간 정리와 모델 파일 관리 방법을 검토했습니다.",
                ),
            ),
        )

        assertTrue(result.passed)
        assertEquals(listOf("schedule"), result.evidenceIds)
    }

    @Test
    fun groundingRejectsUnsupportedFacts() {
        val result = HierarchicalSummaryGrounding.evaluate(
            summary = "팀은 2027년에 CloudX 서비스를 출시합니다.",
            candidates = listOf(
                SummaryEvidenceCandidate(
                    id = "source",
                    text = "팀은 모바일 앱의 로컬 테스트 계획을 논의했습니다.",
                ),
            ),
        )

        assertFalse(result.passed)
        assertTrue("UNSUPPORTED_NUMBER" in result.violationCodes)
        assertTrue("UNSUPPORTED_LATIN" in result.violationCodes)
    }

    @Test
    fun groundingRejectsUnsupportedKoreanTopicsEvenWhenGenericTermsMatch() {
        val result = HierarchicalSummaryGrounding.evaluate(
            summary = "출시 일정과 대통령 탄핵 결정을 확정했습니다.",
            candidates = listOf(
                SummaryEvidenceCandidate(
                    id = "source",
                    text = "제품 출시 일정과 실기기 검증 결정을 확정했습니다.",
                ),
            ),
        )

        assertFalse(result.passed)
        assertTrue("UNSUPPORTED_KOREAN_TERMS" in result.violationCodes)
    }
}
