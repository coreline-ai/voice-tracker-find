package com.thinktank.recorder.ondevice.summary

import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.api.SummaryEngine
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import kotlin.math.sqrt

/**
 * Deterministic Korean-friendly extractive summary.
 *
 * It only returns source sentences. It never invents a name, date, action, or conclusion.
 */
class ExtractiveSummaryEngine(
    private val maxBullets: Int = 3,
) : SummaryEngine {
    override suspend fun summarize(transcript: String): LocalSummary {
        val sentences = splitSentences(transcript)
        if (sentences.isEmpty()) {
            return LocalSummary(
                title = "내용 없는 기록",
                bullets = emptyList(),
                actionItems = emptyList(),
                engine = SummaryEngineType.EXTRACTIVE_KOTLIN,
                sourceHash = sourceHash(transcript),
            )
        }

        val tokenized = sentences.map(::tokens)
        val frequency = tokenized
            .flatten()
            .filterNot(STOP_WORDS::contains)
            .groupingBy { it }
            .eachCount()

        val ranked = sentences.indices
            .map { index ->
                val meaningful = tokenized[index].filterNot(STOP_WORDS::contains)
                val lexical = meaningful.sumOf { frequency[it] ?: 0 }.toDouble()
                val normalized = lexical / sqrt(meaningful.size.coerceAtLeast(1).toDouble())
                val positionBoost = when (index) {
                    0 -> 1.25
                    1 -> 1.12
                    else -> 1.0
                }
                RankedSentence(index, normalized * positionBoost)
            }
            .sortedWith(compareByDescending<RankedSentence> { it.score }.thenBy { it.index })
            .take(maxBullets.coerceAtLeast(1))
            .sortedBy { it.index }
            .map { sentences[it.index] }

        val actions = sentences
            .filter { sentence -> ACTION_MARKERS.any(sentence::contains) }
            .distinct()
            .take(5)

        return LocalSummary(
            title = titleOf(ranked.firstOrNull() ?: sentences.first()),
            bullets = ranked,
            actionItems = actions,
            engine = SummaryEngineType.EXTRACTIVE_KOTLIN,
            sourceHash = sourceHash(transcript),
        )
    }

    internal fun splitSentences(text: String): List<String> =
        text
            .replace("\r\n", "\n")
            .split(SENTENCE_BOUNDARY)
            .asSequence()
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    private fun tokens(sentence: String): List<String> =
        TOKEN.findAll(sentence.lowercase()).map { it.value }.toList()

    private fun titleOf(sentence: String): String {
        val compact = sentence.replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= 34) compact else compact.take(33).trimEnd() + "…"
    }

    private data class RankedSentence(val index: Int, val score: Double)

    private companion object {
        val SENTENCE_BOUNDARY = Regex("""(?<=[.!?。！？])\s+|\n+""")
        val TOKEN = Regex("""[가-힣A-Za-z0-9]{2,}""")
        val STOP_WORDS = setOf(
            "그리고", "그러나", "그래서", "하지만", "대한", "위한", "있는", "없는",
            "합니다", "입니다", "했다", "한다", "하는", "것은", "것을", "수가", "으로",
            "에서", "에게", "까지", "부터", "또한", "이번", "현재", "정도",
        )
        val ACTION_MARKERS = listOf(
            "해야", "하기로", "하자", "필요", "확인", "요청", "예정", "담당", "까지",
        )
    }
}
