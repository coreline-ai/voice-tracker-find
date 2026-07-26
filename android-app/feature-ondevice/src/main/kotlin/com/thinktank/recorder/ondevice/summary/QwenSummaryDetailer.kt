package com.thinktank.recorder.ondevice.summary

import com.thinktank.recorder.ondevice.api.LocalSummary
/**
 * Final output guard shared by Qwen and Kotlin fallback summaries.
 * A summary may contain fewer than two bullets; it must never become a longer restatement of
 * the source merely to satisfy an item-count target. The persisted summary includes line
 * separators, so the budget is applied to the exact text that is displayed and stored.
 */
internal class LocalSummaryCompactor {
    fun compact(summary: LocalSummary, transcript: String): LocalSummary {
        val sourceChars = transcript.replace(Regex("\\s+"), " ").trim().length
        if (sourceChars == 0) return summary
        val totalBudget = (sourceChars * MAX_SOURCE_RATIO).toInt()
            .coerceAtMost(MAX_SUMMARY_CHARS)
        if (totalBudget < MIN_BULLET_CHARS) return summary.copy(bullets = emptyList())
        val selected = mutableListOf<String>()
        var remaining = totalBudget
        summary.bullets
            .asSequence()
            .map(::normalize)
            .map(::dropGenericLeadLabel)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .take(MAX_BULLETS)
            .forEach { bullet ->
                if (remaining <= 0) return@forEach
                // A newline is persisted between every pair of summary rows.
                val separatorCost = if (selected.isEmpty()) 0 else 1
                val allowed = minOf(MAX_BULLET_CHARS, remaining - separatorCost)
                if (allowed < MIN_BULLET_CHARS) return@forEach
                val compact = shorten(bullet, allowed)
                if (compact.isNotBlank()) {
                    selected += compact
                    remaining -= separatorCost + compact.length
                }
            }
        return summary.copy(bullets = selected)
    }

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    /**
     * Qwen sometimes emits a second heading such as "비교 결과: …" as a bullet. The card already
     * has a title and a "핵심 요약" heading, so retain only the factual clause when that lead is a
     * generic label. Specific facts before a colon are intentionally left intact.
     */
    private fun dropGenericLeadLabel(value: String): String {
        val separator = value.indexOfFirst { it == ':' || it == '：' }
        if (separator !in 1..18) return value
        val lead = value.take(separator).trim()
        return if (GENERIC_LEAD_WORDS.any(lead::contains)) {
            value.drop(separator + 1).trim()
        } else {
            value
        }
    }

    private fun shorten(value: String, limit: Int): String = when {
        limit <= 0 -> ""
        value.length <= limit -> value
        limit == 1 -> "…"
        else -> value.take(limit - 1).trimEnd() + "…"
    }

    private companion object {
        /** Summary body is no more than 15% of the transcript, and never more than 80 chars. */
        const val MAX_SOURCE_RATIO = 0.15
        const val MAX_SUMMARY_CHARS = 80
        const val MAX_BULLETS = 2
        const val MAX_BULLET_CHARS = 30
        const val MIN_BULLET_CHARS = 8
        val GENERIC_LEAD_WORDS = setOf(
            "분석", "결과", "비교", "핵심", "요약", "사례", "정리", "포인트",
        )
    }
}
