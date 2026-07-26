package com.thinktank.recorder.ondevice.summary

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractiveSummaryEngineTest {
    private val engine = ExtractiveSummaryEngine()

    @Test
    fun emptyTranscriptReturnsNoInventedContent() = runBlocking {
        val result = engine.summarize(" \n ")

        assertEquals("내용 없는 기록", result.title)
        assertTrue(result.bullets.isEmpty())
        assertTrue(result.actionItems.isEmpty())
    }

    @Test
    fun bulletsAndActionsAreAlwaysSourceSentences() = runBlocking {
        val source = """
            오늘 회의에서는 오프라인 음성 인식을 검토했습니다.
            한국어 모델은 기기 안에서 실행합니다.
            민수는 금요일까지 실기기 성능을 확인해야 합니다.
            모델 다운로드 외에는 네트워크를 사용하지 않습니다.
        """.trimIndent()

        val result = engine.summarize(source)
        val sourceSentences = engine.splitSentences(source).toSet()

        assertTrue(result.bullets.isNotEmpty())
        assertTrue(result.bullets.all(sourceSentences::contains))
        assertEquals(
            listOf("민수는 금요일까지 실기기 성능을 확인해야 합니다."),
            result.actionItems,
        )
    }

    @Test
    fun sameInputProducesSameSummary() = runBlocking {
        val source = "첫 번째 문장입니다. 두 번째 핵심 문장입니다. 세 번째 확인이 필요합니다."

        assertEquals(engine.summarize(source), engine.summarize(source))
    }

    @Test
    fun punctuationLightSttUsesSpokenKoreanEndingsWithoutEllipsis() = runBlocking {
        val source = """
            쇼핑쇼츠 강의에서 수강생 판매 경험을 설명합니다
            쿠팡 경쟁 대응법은 후속 영상에서 소개합니다
            여성 의류 판매자는 상품 영상을 확인해야 합니다
        """.trimIndent()

        val result = engine.summarize(source)

        assertTrue(result.bullets.isNotEmpty())
        assertTrue(result.bullets.all { it in engine.splitSentences(source) })
        assertTrue(result.bullets.none { it.contains('…') || it.contains("...") })
        assertTrue(result.bullets.joinToString("\n").length < source.length)
    }
}
