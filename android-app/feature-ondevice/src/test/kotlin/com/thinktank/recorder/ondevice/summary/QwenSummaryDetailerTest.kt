package com.thinktank.recorder.ondevice.summary

import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSummaryCompactorTest {
    @Test
    fun keepsOnlyCompactRowsAndNeverExpandsTheSource() {
        val transcript = """
            쇼핑쇼츠 강의에서 여성 의류 판매 전환 사례를 설명한다. 쿠팡과 경쟁하는 영상 구도를
            비교하고, 수강생이 짧은 영상으로 고객을 유입하는 과정을 소개한다. 마지막에는 상품
            노출 순서와 수익 확인 방법을 정리한다. 이 내용은 실제 교육 영상의 핵심 흐름이다.
        """.trimIndent()
        val generated = LocalSummary(
            title = "쇼핑쇼츠 운영 전략",
            bullets = listOf(
                "쇼핑쇼츠 강의에서 여성 의류 판매 전환 사례와 고객 유입 과정을 상세히 설명한다.",
                "쿠팡과 경쟁하는 영상 구도를 비교하고 차별화된 상품 노출 순서를 제안한다.",
                "수강생이 짧은 영상으로 고객을 유입한 뒤 수익을 확인하는 방법을 정리한다.",
                "이 항목은 세 개를 넘으므로 저장된 요약에 포함되면 안 된다.",
            ),
            actionItems = emptyList(),
            engine = SummaryEngineType.QWEN_LOCAL,
        )

        val result = LocalSummaryCompactor().compact(generated, transcript)
        val persisted = result.bullets.joinToString("\n")
        val normalizedSource = transcript.replace(Regex("\\s+"), " ").trim()

        assertEquals(SummaryEngineType.QWEN_LOCAL, result.engine)
        assertTrue(result.bullets.size <= 2)
        assertTrue(result.bullets.all { it.length <= 30 })
        assertTrue(persisted.length <= 80)
        assertTrue(persisted.length <= (normalizedSource.length * 0.15).toInt())
        assertTrue(persisted.length < normalizedSource.length)
    }

    @Test
    fun doesNotForceAPlaceholderForTooShortSource() {
        val generated = LocalSummary(
            title = "짧은 기록",
            bullets = listOf("매우 긴 설명 문장입니다."),
            actionItems = emptyList(),
        )

        val result = LocalSummaryCompactor().compact(generated, "짧음")

        assertTrue(result.bullets.isEmpty())
    }

    @Test
    fun removesGenericBulletLeadBeforeApplyingTheCompactLimit() {
        val transcript = List(4) {
            "쇼핑쇼츠는 경쟁 서비스와 다른 판매 영상 구성으로 수강생 유입을 늘렸다."
        }.joinToString(" ")
        val result = LocalSummaryCompactor().compact(
            LocalSummary(
                title = "쇼핑쇼츠 성과",
                bullets = listOf("비교 결과: 쇼핑쇼츠는 수강생 유입을 늘렸다."),
                actionItems = emptyList(),
            ),
            transcript,
        )

        assertEquals(listOf("쇼핑쇼츠는 수강생 유입을 늘렸다."), result.bullets)
    }

    @Test
    fun compactsKotlinFallbackWithTheSamePersistedBudget() = runBlocking {
        val transcript = """
            회의에서 다음 분기 출시 준비를 위한 모바일 녹음 화면 개선 요구사항을 검토했다.
            담당자는 사용자 녹음을 서버로 보내지 않고 기기 안에서 텍스트 변환과 요약을 끝내기로 했다.
            이후 테스트 기기에서 긴 녹음의 원문 표시와 짧은 핵심 요약이 모두 정상인지 확인하기로 했다.
        """.trimIndent()

        val fallback = ExtractiveSummaryEngine().summarize(transcript)
        val result = LocalSummaryCompactor().compact(fallback, transcript)
        val persisted = result.bullets.joinToString("\n")
        val normalizedSource = transcript.replace(Regex("\\s+"), " ").trim()

        assertEquals(SummaryEngineType.EXTRACTIVE_KOTLIN, result.engine)
        assertTrue(result.bullets.size <= 2)
        assertTrue(result.bullets.all { it.length <= 30 })
        assertTrue(persisted.length <= 80)
        assertTrue(persisted.length <= (normalizedSource.length * 0.15).toInt())
        assertTrue(persisted.length < normalizedSource.length)
    }
}
