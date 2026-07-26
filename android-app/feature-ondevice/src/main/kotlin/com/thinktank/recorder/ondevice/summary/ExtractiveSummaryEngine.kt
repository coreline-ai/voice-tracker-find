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
class ExtractiveSummaryEngine internal constructor(
    private val maxBullets: Int,
    private val segmenter: KoreanTranscriptSegmenter,
) : SummaryEngine {
    constructor() : this(
        maxBullets = SummaryPolicy.MAX_BULLETS,
        segmenter = KoreanTranscriptSegmenter(),
    )

    internal constructor(maxBullets: Int) : this(
        maxBullets = maxBullets,
        segmenter = KoreanTranscriptSegmenter(),
    )

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

        val rankedCandidates = sentences.indices
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
            .filter { sentences[it.index].length <= SummaryPolicy.MAX_BULLET_CHARS }

        val budget = SummaryPolicy.totalBudget(transcript)
        val selected = mutableListOf<RankedSentence>()
        var usedChars = 0
        rankedCandidates.forEach { candidate ->
            if (selected.size >= maxBullets.coerceAtLeast(1)) return@forEach
            val sentence = sentences[candidate.index]
            val separator = if (selected.isEmpty()) 0 else 1
            if (usedChars + separator + sentence.length > budget) return@forEach
            if (selected.any { tokenSimilarity(sentences[it.index], sentence) >= 0.60 }) {
                return@forEach
            }
            selected += candidate
            usedChars += separator + sentence.length
        }
        val ranked = selected.sortedBy { it.index }.map { sentences[it.index] }

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
            policyVersion = SummaryPolicy.VERSION,
            validationStatus = if (ranked.isEmpty()) null else SUMMARY_VALIDATION_PASSED,
        )
    }

    internal fun splitSentences(text: String): List<String> =
        segmenter.segment(text).map { it.text.trim('"') }

    private fun tokens(sentence: String): List<String> =
        TOKEN.findAll(sentence.lowercase()).map { it.value }.toList()

    private fun titleOf(sentence: String): String {
        val meaningful = tokens(sentence)
            .filterNot(STOP_WORDS::contains)
            .distinct()
            .take(3)
        return meaningful
            .fold("") { title, token ->
                val candidate = listOf(title, token).filter(String::isNotBlank).joinToString(" ")
                if (candidate.length <= SummaryPolicy.MAX_TITLE_CHARS) candidate else title
            }
            .ifBlank { "로컬 음성 기록" }
    }

    private fun tokenSimilarity(left: String, right: String): Double {
        val leftTokens = tokens(left).filterNot(STOP_WORDS::contains).toSet()
        val rightTokens = tokens(right).filterNot(STOP_WORDS::contains).toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
        return leftTokens.intersect(rightTokens).size.toDouble() /
            leftTokens.union(rightTokens).size.toDouble()
    }

    private data class RankedSentence(val index: Int, val score: Double)

    private companion object {
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
