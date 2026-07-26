package com.thinktank.recorder.ondevice.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KoreanTranscriptSegmenterTest {
    private val segmenter = KoreanTranscriptSegmenter()

    @Test
    fun splitsPunctuationLightKoreanSttAtSpokenEndings() {
        val source =
            "여수라고 하고요 지금 쇼핑쇼츠 사업을 하고 있습니다 제가 전날 받기로는 쇼츠 강사라고 하거든요 오늘 핵심을 알려 드릴 거예요"

        assertEquals(
            listOf(
                "여수라고 하고요",
                "지금 쇼핑쇼츠 사업을 하고 있습니다",
                "제가 전날 받기로는 쇼츠 강사라고 하거든요",
                "오늘 핵심을 알려 드릴 거예요",
            ),
            segmenter.segment(source).map(SourceSegment::text),
        )
    }

    @Test
    fun keepsLongBoundarylessUtteranceWholeInsteadOfCuttingCharacters() {
        val source = "문장부호나종결어미없이인식된아주긴한국어음성전사문"

        val result = segmenter.segment(source)

        assertEquals(listOf(source), result.map(SourceSegment::text))
        assertFalse(result.single().text.endsWith("…"))
    }

    @Test
    fun splitsFiveHundredCharacterPunctuationLightSttIntoSpokenUnits() {
        val source = buildString {
            repeat(18) { index ->
                append("발화 $index 에서 쇼핑쇼츠 판매 경험을 설명하고요 ")
                append("다음 영상에서는 쿠팡 경쟁 대응법을 소개합니다 ")
            }
        }
        val result = segmenter.segment(source)

        assertEquals(true, source.length >= 500)
        assertEquals(36, result.size)
        assertEquals(
            source.replace(Regex("\\s+"), ""),
            result.joinToString("") { it.text }.replace(" ", ""),
        )
    }
}
