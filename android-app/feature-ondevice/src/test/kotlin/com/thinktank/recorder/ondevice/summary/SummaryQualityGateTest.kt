package com.thinktank.recorder.ondevice.summary

import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryQualityGateTest {
    private val gate = SummaryQualityGate()
    private val source = """
        쇼핑쇼츠 강의에서 실제 수강생의 판매 경험을 설명합니다.
        쿠팡 경쟁 대응법은 다음 영상에서 별도로 소개합니다.
        여성 의류 판매자는 상품 영상을 끝까지 확인해 달라고 요청합니다.
    """.trimIndent()

    @Test
    fun acceptsShortCompleteSourceGroundedSummary() {
        val result = gate.validate(
            summary = qwenSummary(
                title = "쇼핑쇼츠 강의",
                bullets = listOf("수강생의 쇼핑쇼츠 판매 경험을 설명한다."),
            ),
            transcript = source,
            evidenceIds = listOf(setOf(1)),
        )

        assertTrue(result.violations.toString(), result.valid)
    }

    @Test
    fun acceptsSourceGroundedCompactDecisionHeadline() {
        val compactSource = "새 녹음 화면의 출시일은 8월 15일로 확정했다."
        val result = gate.validate(
            summary = qwenSummary(
                title = "8월 출시일",
                bullets = listOf("8월 15일 출시일 확정"),
            ),
            transcript = compactSource,
            evidenceIds = listOf(setOf(1)),
        )

        assertTrue(result.violations.toString(), result.valid)
    }

    @Test
    fun rejectsCharacterSlicedOrGenericSummary() {
        val result = gate.validate(
            summary = qwenSummary(
                title = "운영 전략",
                bullets = listOf("수정된 성공 사례 분석…"),
            ),
            transcript = source,
            evidenceIds = listOf(setOf(1)),
        )

        assertFalse(result.valid)
        assertTrue(SummaryViolationCode.INCOMPLETE_BULLET in result.violations)
        assertTrue(SummaryViolationCode.GENERIC_BULLET in result.violations)
    }

    @Test
    fun rejectsUnsupportedNumberAndEvidenceId() {
        val result = gate.validate(
            summary = qwenSummary(
                title = "판매 경험",
                bullets = listOf("수강생 매출이 300퍼센트 증가했다고 설명한다."),
            ),
            transcript = source,
            evidenceIds = listOf(setOf(99)),
        )

        assertFalse(result.valid)
        assertTrue(SummaryViolationCode.UNSUPPORTED_NUMBER in result.violations)
        assertTrue(SummaryViolationCode.INVALID_EVIDENCE_ID in result.violations)
    }

    @Test
    fun rejectsMostlyInventedKoreanContentEvenWithTwoSourceWords() {
        val result = gate.validate(
            summary = qwenSummary(
                title = "쇼핑쇼츠 강의",
                bullets = listOf("쇼핑쇼츠 강의가 전국 매출 순위를 제패했다고 발표한다."),
            ),
            transcript = source,
            evidenceIds = listOf(setOf(1)),
        )

        assertFalse(result.valid)
        assertTrue(SummaryViolationCode.WEAK_SOURCE_EVIDENCE in result.violations)
    }

    @Test
    fun rejectsSummaryLongerThanPolicyBudget() {
        val result = gate.validate(
            summary = qwenSummary(
                title = "쇼핑쇼츠 강의",
                bullets = listOf(
                    "쇼핑쇼츠 강의에서 수강생 판매 경험을 구체적으로 길게 설명한다.",
                    "쿠팡 경쟁 대응법을 다음 영상에서 별도로 자세하게 소개한다.",
                ),
            ),
            transcript = source,
            evidenceIds = listOf(setOf(1), setOf(2)),
        )

        assertFalse(result.valid)
        assertTrue(SummaryViolationCode.TOTAL_TOO_LONG in result.violations)
    }

    private fun qwenSummary(title: String, bullets: List<String>) = LocalSummary(
        title = title,
        bullets = bullets,
        actionItems = emptyList(),
        engine = SummaryEngineType.QWEN_LOCAL,
    )
}
