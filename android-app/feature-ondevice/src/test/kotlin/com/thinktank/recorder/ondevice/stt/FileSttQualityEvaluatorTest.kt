package com.thinktank.recorder.ondevice.stt

import com.thinktank.recorder.ondevice.api.SttQualityStatus
import com.thinktank.recorder.ondevice.api.SttSegmentDiagnostic
import com.thinktank.recorder.ondevice.api.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSttQualityEvaluatorTest {
    private val evaluator = FileSttQualityEvaluator()

    @Test
    fun rejectsPunctuationOnlyResultEvenWhenAudioCoverageReachedTheEnd() {
        val diagnostics = evaluator.evaluate(
            pass = FileSttPass(
                text = ".",
                segments = listOf(TranscriptSegment(1_000L, 15_168L, ".")),
                inputDurationMs = 15_168L,
                processedThroughMs = 15_168L,
                sourceSegmentCount = 1,
                recognizedSegmentCount = 1,
                fixedChunkPass = false,
            ),
            retryCount = 1,
        )

        assertEquals(SttQualityStatus.INSUFFICIENT, diagnostics.qualityStatus)
        assertEquals(0, diagnostics.meaningfulChars)
    }

    @Test
    fun rejectsPassWhenOneSpeechSegmentProducedNoText() {
        val diagnostics = evaluator.evaluate(
            pass = FileSttPass(
                text = "전사된 앞부분입니다.",
                segments = listOf(TranscriptSegment(0L, 28_000L, "전사된 앞부분입니다.")),
                inputDurationMs = 45_184L,
                processedThroughMs = 45_184L,
                sourceSegmentCount = 2,
                recognizedSegmentCount = 1,
                fixedChunkPass = false,
            ),
            retryCount = 0,
        )

        assertEquals(SttQualityStatus.INSUFFICIENT, diagnostics.qualityStatus)
    }

    @Test
    fun acceptsDenseFullCoverageAndMarksSuccessfulRetry() {
        val diagnostics = evaluator.evaluate(
            pass = FileSttPass(
                text = "한국어 전사 결과입니다 ".repeat(24),
                segments = listOf(
                    TranscriptSegment(0L, 28_000L, "한국어 전사 결과입니다"),
                    TranscriptSegment(27_000L, 45_184L, "후반 전사 결과입니다"),
                ),
                inputDurationMs = 45_184L,
                processedThroughMs = 45_184L,
                sourceSegmentCount = 2,
                recognizedSegmentCount = 2,
                fixedChunkPass = true,
            ),
            retryCount = 1,
        )

        assertEquals(SttQualityStatus.RETRIED_COMPLETE, diagnostics.qualityStatus)
        assertTrue(diagnostics.charsPerSecond > 1f)
        assertTrue(diagnostics.passed)
    }

    @Test
    fun boundaryDeduplicationRemovesOnlyRepeatedContext() {
        val deduplicated = deduplicateTranscriptBoundary(
            previous = "오늘 회의에서는 일정과 예산을 확인했습니다",
            current = "예산을 확인했습니다 다음 단계는 고객 인터뷰입니다",
        )

        assertEquals("다음 단계는 고객 인터뷰입니다", deduplicated)
    }

    @Test
    fun targetedRetryPlannerSelectsOnlyEmptyRecognitionRanges() {
        val ranges = SttRetryPlanner.plan(
            diagnostics = listOf(
                SttSegmentDiagnostic(0L, 28_000L, 18),
                SttSegmentDiagnostic(27_000L, 55_000L, 0),
                SttSegmentDiagnostic(55_000L, 70_000L, 0),
                SttSegmentDiagnostic(70_000L, 90_000L, 12),
            ),
            inputDurationMs = 90_000L,
        )

        assertEquals(listOf(SttRetryRange(27_000L, 70_000L)), ranges)
    }

    @Test
    fun targetedRetryMergeKeepsPrimaryTextAndFillsFailedRange() {
        val primary = FileSttPass(
            text = "앞 구간입니다\n뒤 구간입니다",
            segments = listOf(
                TranscriptSegment(0L, 28_000L, "앞 구간입니다"),
                TranscriptSegment(55_000L, 80_000L, "뒤 구간입니다"),
            ),
            inputDurationMs = 80_000L,
            processedThroughMs = 80_000L,
            sourceSegmentCount = 3,
            recognizedSegmentCount = 2,
            fixedChunkPass = false,
            segmentDiagnostics = listOf(
                SttSegmentDiagnostic(0L, 28_000L, 6),
                SttSegmentDiagnostic(27_000L, 55_000L, 0),
                SttSegmentDiagnostic(55_000L, 80_000L, 6),
            ),
        )
        val retry = FileSttPass(
            text = "가운데 복구 구간입니다",
            segments = listOf(
                TranscriptSegment(27_000L, 55_000L, "가운데 복구 구간입니다"),
            ),
            inputDurationMs = 80_000L,
            processedThroughMs = 55_000L,
            sourceSegmentCount = 1,
            recognizedSegmentCount = 1,
            fixedChunkPass = true,
            segmentDiagnostics = listOf(SttSegmentDiagnostic(27_000L, 55_000L, 10)),
        )

        val merged = mergeTargetedRetry(primary, retry)

        assertEquals(3, merged.recognizedSegmentCount)
        assertEquals(80_000L, merged.processedThroughMs)
        assertEquals(
            "앞 구간입니다\n가운데 복구 구간입니다\n뒤 구간입니다",
            merged.text,
        )
    }
}
