package com.thinktank.recorder.ondevice.summary

/** Maximum Binder-safe Qwen prompt input after host-side compaction. */
internal const val QWEN_MAX_INPUT_CHARS = 6_000

/**
 * Keeps the Binder payload bounded before crossing into the isolated Qwen process.
 * The original transcript remains the source of record; this text only feeds generation.
 */
internal class QwenInputReducer(
    private val reducer: ExtractiveSummaryEngine = ExtractiveSummaryEngine(maxBullets = 12),
) {
    suspend fun reduce(transcript: String): String {
        val compact = transcript.replace(Regex("\\s+"), " ").trim()
        if (compact.length <= QWEN_MAX_INPUT_CHARS) return compact
        val extracted = reducer.summarize(compact)
        return buildString {
            appendLine("[원문 앞부분]")
            appendLine(compact.take(1_500))
            appendLine("[중요 문장]")
            extracted.bullets.forEach { appendLine("- $it") }
            if (extracted.actionItems.isNotEmpty()) {
                appendLine("[명시된 할 일 후보]")
                extracted.actionItems.forEach { appendLine("- $it") }
            }
            appendLine("[원문 뒷부분]")
            append(compact.takeLast(800))
        }.take(QWEN_MAX_INPUT_CHARS)
    }
}
