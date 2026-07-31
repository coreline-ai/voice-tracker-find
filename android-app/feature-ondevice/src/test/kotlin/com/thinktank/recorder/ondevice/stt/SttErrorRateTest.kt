package com.thinktank.recorder.ondevice.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class SttErrorRateTest {
    @Test
    fun cerCountsInsertionDeletionAndSubstitution() {
        assertEquals(1, SttErrorRates.cer("가나다", "가나").edits)
        assertEquals(1, SttErrorRates.cer("가나", "가나다").edits)
        assertEquals(1, SttErrorRates.cer("가나다", "가라다").edits)
        assertEquals(0f, SttErrorRates.cer("가 나 다", "가나다").rate)
    }

    @Test
    fun werNormalizesPunctuationAndCountsWordEdits() {
        val result = SttErrorRates.wer(
            reference = "회의 일정은 금요일입니다.",
            hypothesis = "회의 예산은 금요일입니다",
        )

        assertEquals(1, result.edits)
        assertEquals(3, result.referenceUnits)
        assertEquals(1f / 3f, result.rate)
    }

    @Test
    fun emptyReferenceHasBoundedRate() {
        assertEquals(0f, SttErrorRates.wer("", "").rate)
        assertEquals(1f, SttErrorRates.wer("", "추가 단어").rate)
    }
}
