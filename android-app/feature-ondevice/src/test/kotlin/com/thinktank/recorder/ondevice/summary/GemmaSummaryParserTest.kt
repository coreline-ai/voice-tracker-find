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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDetachedSingleCharacterSentenceEnding() {
        GemmaSummaryParser.parse(
            rawOutput =
                """{"summary":"문제가 발견되면 기록을 통해 원인을 파악하여 수정한 후 다"}""",
            sourceHash = "source",
            source =
                "출시 후보는 문제가 없을 때 다음 단계로 진행하고 " +
                    "문제가 발견되면 원인을 기록해 수정한다.",
            selectedEvidence = listOf(
                "출시 후보는 문제가 없을 때 다음 단계로 진행하고 " +
                    "문제가 발견되면 원인을 기록해 수정한다.",
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGenericSentenceThatDropsRepeatedSourceTopics() {
        val source =
            "데이터 보호는 원본 보존과 안전한 백업 및 복구 절차로 구성된다. " +
                "데이터 백업은 파일 해시를 기록하고 복구 테스트를 반복한다. " +
                "운영 담당자는 백업 생성 시간과 복구 성공 여부를 보고한다."

        GemmaSummaryParser.parse(
            rawOutput = """{"summary":"담당자는 이와 관련하여 여부를 보고한다."}""",
            sourceHash = "source",
            source = source,
            selectedEvidence = listOf(source),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRecognizedNoiseThatCopiesMinorSourceWordsWithoutMainTopics() {
        val source =
            "온디바이스 음성 모델은 기기 안에서 음성 파일을 처리한다. " +
                "음성 인식 모델은 기기에서 전사 원문을 만든다. " +
                "요모은에 있는 근거를 사용해 제목과 핵심을 생성해야 하고 " +
                "입력에 없는 숫자나 고용사가 나오면 결과를 저장하지 않고 다시 확인합니다."
        val selectedNoiseEvidence =
            "요모은에 있는 근거를 사용해 제목과 핵심을 생성해야 하고 " +
                "입력에 없는 숫자나 고용사가 나오면 결과를 저장하지 않고 다시 확인합니다."

        GemmaSummaryParser.parse(
            rawOutput =
                """{"summary":"요모은에 있는 근거를 사용해 제목과 핵심을 생성해야 하고, 입력에 없는 숫자나 고용사가 나오면 결과를 저장하지 않고 다시 확인합니다."}""",
            sourceHash = "source",
            source = source,
            selectedEvidence = listOf(selectedNoiseEvidence),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNoiseThatMatchesOnlyOneOfSeveralSalientTopics() {
        val source =
            "음성 모델은 기기 안에서 처리하고 음성 모델은 기기에서 전사한다. " +
                "요모은 근거 제목 핵심 입력 숫자 결과 저장 확인을 반복한다. " +
                "음성 모델과 기기 처리를 점검하고 요모은 근거 제목 핵심 입력 숫자 결과 저장 확인을 반복한다."
        val selectedNoiseEvidence =
            "요모은 근거 제목 핵심 입력 숫자 결과 저장 확인을 반복한다."

        GemmaSummaryParser.parse(
            rawOutput =
                """{"summary":"요모은 근거와 제목 핵심 입력 숫자 결과 저장을 다시 확인한다."}""",
            sourceHash = "source",
            source = source,
            selectedEvidence = listOf(selectedNoiseEvidence),
        )
    }

    @Test
    fun acceptsSummaryThatPreservesRepeatedSourceTopics() {
        val source =
            "고객 상담에서는 배송 지연 안내와 환불 절차를 설명한다. " +
                "고객 문의를 확인하고 배송 상태와 환불 처리 시간을 비교한다."
        val result = GemmaSummaryParser.parse(
            rawOutput =
                """{"summary":"고객 상담은 배송 안내와 환불 절차를 정확하게 설명한다."}""",
            sourceHash = "source",
            source = source,
            selectedEvidence = listOf(source),
        )

        assertEquals(
            listOf("고객 상담은 배송 안내와 환불 절차를 정확하게 설명한다."),
            result.bullets,
        )
    }
}
