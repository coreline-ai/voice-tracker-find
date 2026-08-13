package com.coreline.ai.voice.ondevice.stt

import com.coreline.ai.voice.ondevice.api.SttCoverageStatus
import com.coreline.ai.voice.ondevice.api.SttDiagnostics
import com.coreline.ai.voice.ondevice.api.SttRecognitionQualityStatus
import java.util.Locale

internal data class SttRecognitionAssessment(
    val coverage: SttCoverageStatus,
    val recognitionQuality: SttRecognitionQualityStatus,
    /** Aggregate metrics only. The transcript itself is never duplicated into this field. */
    val encodedDiagnostics: String,
)

internal class SttRecognitionQualityEvaluator {
    fun evaluate(transcript: String, diagnostics: SttDiagnostics?): SttRecognitionAssessment {
        if (diagnostics == null) {
            return SttRecognitionAssessment(
                coverage = SttCoverageStatus.UNKNOWN,
                recognitionQuality = if (transcript.count(Char::isLetterOrDigit) < 2) {
                    SttRecognitionQualityStatus.INSUFFICIENT
                } else {
                    SttRecognitionQualityStatus.UNMEASURED
                },
                encodedDiagnostics = "v=1;coverage=unknown",
            )
        }
        val coverage = when {
            diagnostics.inputDurationMs <= 0L -> SttCoverageStatus.UNKNOWN
            diagnostics.processedThroughMs + COVERAGE_TOLERANCE_MS <
                diagnostics.inputDurationMs -> SttCoverageStatus.INCOMPLETE
            diagnostics.segmentCount > 0 &&
                diagnostics.recognizedSegmentCount < diagnostics.segmentCount ->
                SttCoverageStatus.INCOMPLETE
            else -> SttCoverageStatus.COMPLETE
        }
        val normalized = transcript.replace(Regex("\\s+"), " ").trim()
        val tokens = TOKEN.findAll(normalized.lowercase()).map { it.value }.toList()
        val repeatedTokenRatio = repeatedTokenRatio(tokens)
        val maxUnpunctuatedChars = normalized
            .split(PUNCTUATION)
            .maxOfOrNull(String::length)
            ?: 0
        val fragmentRatio = if (tokens.isEmpty()) {
            1f
        } else {
            tokens.count { token -> token.length <= 1 }.toFloat() / tokens.size.toFloat()
        }
        val emptySegmentRatio = if (diagnostics.segmentCount <= 0) {
            0f
        } else {
            (diagnostics.segmentCount - diagnostics.recognizedSegmentCount)
                .coerceAtLeast(0)
                .toFloat() / diagnostics.segmentCount.toFloat()
        }
        val quality = when {
            diagnostics.meaningfulChars < MIN_MEANINGFUL_CHARS ||
                diagnostics.recognizedSegmentCount <= 0 ->
                SttRecognitionQualityStatus.INSUFFICIENT
            repeatedTokenRatio >= REPEATED_TOKEN_RATIO_NOISY ||
                maxUnpunctuatedChars >= MAX_UNPUNCTUATED_CHARS ||
                fragmentRatio >= FRAGMENT_RATIO_NOISY ||
                emptySegmentRatio >= EMPTY_SEGMENT_RATIO_NOISY ->
                SttRecognitionQualityStatus.NOISY
            else -> SttRecognitionQualityStatus.ADEQUATE
        }
        return SttRecognitionAssessment(
            coverage = coverage,
            recognitionQuality = quality,
            encodedDiagnostics = listOf(
                "v=1",
                "repeat=${repeatedTokenRatio.metric()}",
                "unpunctuated=$maxUnpunctuatedChars",
                "fragment=${fragmentRatio.metric()}",
                "emptySegment=${emptySegmentRatio.metric()}",
            ).joinToString(";"),
        )
    }

    private fun repeatedTokenRatio(tokens: List<String>): Float {
        if (tokens.size < MIN_REPEAT_WINDOW * 2) return 0f
        val windows = tokens.windowed(MIN_REPEAT_WINDOW)
        if (windows.isEmpty()) return 0f
        val repeated = windows.size - windows.distinct().size
        return repeated.toFloat() / windows.size.toFloat()
    }

    private fun Float.metric(): String = String.format(Locale.US, "%.3f", this)

    private companion object {
        const val COVERAGE_TOLERANCE_MS = 40L
        const val MIN_MEANINGFUL_CHARS = 2
        const val MIN_REPEAT_WINDOW = 3
        const val REPEATED_TOKEN_RATIO_NOISY = 0.35f
        const val MAX_UNPUNCTUATED_CHARS = 240
        const val FRAGMENT_RATIO_NOISY = 0.35f
        const val EMPTY_SEGMENT_RATIO_NOISY = 0.20f
        val TOKEN = Regex("""[가-힣A-Za-z0-9]+""")
        val PUNCTUATION = Regex("""[.!?。！？\n]+""")
    }
}
