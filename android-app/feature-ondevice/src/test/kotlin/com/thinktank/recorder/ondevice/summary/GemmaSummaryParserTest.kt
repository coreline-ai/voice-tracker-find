package com.thinktank.recorder.ondevice.summary

import org.junit.Assert.assertEquals
import org.junit.Test

class GemmaSummaryParserTest {
    @Test
    fun parsesJsonWrappedInMarkdownFence() {
        val result = GemmaSummaryParser.parse(
            rawOutput = """
                ```json
                {
                  "summary": "인공지능 모델이 커지며 메모리 수요가 증가한다."
                }
                ```
            """.trimIndent(),
            sourceHash = "source",
            source = "인공지능 모델의 크기가 증가하면서 메모리 수요도 증가한다.",
            selectedEvidence =
                listOf("인공지능 모델의 크기가 증가하면서 메모리 수요도 증가한다."),
        )

        assertEquals(listOf("인공지능 모델이 커지며 메모리 수요가 증가한다."), result.bullets)
        assertEquals("인공지능 모델 메모리", result.title)
        assertEquals(GemmaSummaryParser.VALIDATION_STATUS, result.validationStatus)
        assertEquals("source", result.sourceHash)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsJsonWithoutSummary() {
        GemmaSummaryParser.parse(
            rawOutput = """{"title":"빈 결과","bullets":[]}""",
            sourceHash = "source",
            source = "인공지능 모델은 메모리 수요를 늘린다.",
            selectedEvidence = listOf("인공지능 모델은 메모리 수요를 늘린다."),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInstructionPlaceholderCopiedFromPrompt() {
        GemmaSummaryParser.parse(
            rawOutput = """{"summary":"완결된 핵심 문장입니다."}""",
            sourceHash = "source",
            source = "구글은 개인 데이터를 활용해 서비스를 제공한다.",
            selectedEvidence = listOf("구글은 개인 데이터를 활용해 서비스를 제공한다."),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedLatinTerm() {
        GemmaSummaryParser.parse(
            rawOutput = """{"summary":"Google은 개인 데이터를 활용해 서비스를 제공한다."}""",
            sourceHash = "source",
            source = "구글은 개인 데이터를 활용해 서비스를 제공한다.",
            selectedEvidence = listOf("구글은 개인 데이터를 활용해 서비스를 제공한다."),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHallucinatedSubjectEvenWhenOtherWordsOverlap() {
        GemmaSummaryParser.parse(
            rawOutput = """{"summary":"전자는 미래를 향한 투자를 통해 급속히 발전할 것이다."}""",
            sourceHash = "source",
            source = "미래 관련 투자는 많지 않지만 기술은 빠르게 발전하고 있습니다.",
            selectedEvidence =
                listOf("미래 관련 투자는 많지 않지만 기술은 빠르게 발전하고 있습니다."),
        )
    }

    @Test
    fun titleUsesOnlyStrongTopicTokensWhenTwoArePresent() {
        val result = GemmaSummaryParser.parse(
            rawOutput =
                """{"summary":"사진과 동영상을 처리하는 구글 생태계는 안정적인 운영을 추구한다."}""",
            sourceHash = "source",
            source = "사진과 동영상을 처리하는 구글 생태계는 안정적인 운영을 추구한다.",
            selectedEvidence =
                listOf("사진과 동영상을 처리하는 구글 생태계는 안정적인 운영을 추구한다."),
        )

        assertEquals("구글 생태계", result.title)
    }
}
