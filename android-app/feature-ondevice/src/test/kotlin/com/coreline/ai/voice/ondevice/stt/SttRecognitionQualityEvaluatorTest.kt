package com.coreline.ai.voice.ondevice.stt

import com.coreline.ai.voice.ondevice.api.SttCoverageStatus
import com.coreline.ai.voice.ondevice.api.SttDiagnostics
import com.coreline.ai.voice.ondevice.api.SttQualityStatus
import com.coreline.ai.voice.ondevice.api.SttRecognitionQualityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SttRecognitionQualityEvaluatorTest {
    private val evaluator = SttRecognitionQualityEvaluator()

    @Test
    fun fullCoverageCanStillBeNoisy() {
        val result = evaluator.evaluate(
            transcript = "일정 확인 반복 ".repeat(40),
            diagnostics = diagnostics(processedThroughMs = 300_000L),
        )

        assertEquals(SttCoverageStatus.COMPLETE, result.coverage)
        assertEquals(SttRecognitionQualityStatus.NOISY, result.recognitionQuality)
        assertFalse(result.encodedDiagnostics.contains("일정"))
    }

    @Test
    fun missingTailIsIncompleteRegardlessOfReadableText() {
        val result = evaluator.evaluate(
            transcript = "회의 일정은 금요일까지 확정합니다.",
            diagnostics = diagnostics(processedThroughMs = 250_000L),
        )

        assertEquals(SttCoverageStatus.INCOMPLETE, result.coverage)
        assertEquals(SttRecognitionQualityStatus.ADEQUATE, result.recognitionQuality)
    }

    @Test
    fun punctuationOnlyTranscriptIsInsufficient() {
        val result = evaluator.evaluate(
            transcript = "...",
            diagnostics = diagnostics(
                processedThroughMs = 300_000L,
                meaningfulChars = 0,
                recognizedSegments = 1,
            ),
        )

        assertEquals(SttCoverageStatus.INCOMPLETE, result.coverage)
        assertEquals(SttRecognitionQualityStatus.INSUFFICIENT, result.recognitionQuality)
    }

    private fun diagnostics(
        processedThroughMs: Long,
        meaningfulChars: Int = 24,
        recognizedSegments: Int = 10,
    ) = SttDiagnostics(
        inputDurationMs = 300_000L,
        processedThroughMs = processedThroughMs,
        segmentCount = 10,
        recognizedSegmentCount = recognizedSegments,
        retryCount = 0,
        meaningfulChars = meaningfulChars,
        charsPerSecond = 1f,
        qualityStatus = SttQualityStatus.COMPLETE,
    )
}
