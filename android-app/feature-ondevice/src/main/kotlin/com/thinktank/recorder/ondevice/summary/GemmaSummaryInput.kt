package com.thinktank.recorder.ondevice.summary

internal data class GemmaSummaryInput(
    val source: String,
    val selectedEvidence: List<String>,
) {
    val promptSource: String
        get() = selectedEvidence.joinToString("\n") { "- $it" }
}

/**
 * Reduces punctuation-light STT text before it reaches Gemma 3 1B.
 *
 * A 1B model is much more reliable when Kotlin selects a small, deterministic evidence set and
 * the model only performs the final compression. The complete transcript remains the source of
 * record and is still used by the output quality gate.
 */
internal object GemmaSummaryInputBuilder {
    fun build(transcript: String): GemmaSummaryInput {
        val source = normalize(transcript)
        require(source.isNotBlank()) { "요약할 전사 원문이 없습니다." }
        val segments = segment(source)
        require(segments.isNotEmpty()) { "요약할 원문 구간이 없습니다." }
        if (source.length <= MAX_PROMPT_SOURCE_CHARS) {
            return GemmaSummaryInput(
                source = source,
                selectedEvidence = listOf(source),
            )
        }
        val tokenFrequency = segments
            .flatMap(::meaningfulTokens)
            .groupingBy(String::lowercase)
            .eachCount()
        val leadTopics = meaningfulTokens(segments.first()).toSet()

        val rankedIndexes = segments.indices
            .sortedWith(
                compareByDescending<Int> { index ->
                    relevanceScore(segments[index], tokenFrequency) +
                        positionBonus(index, segments.lastIndex) +
                        leadTopicOverlapScore(segments[index], leadTopics)
                }.thenBy { it },
            )
        val selected = buildList {
            // Keep the lead segment as the topic anchor. A small model otherwise tends to prefer
            // repeated meta-instructions containing words such as "핵심" or "해야".
            add(segments.first())
            rankedIndexes.asSequence()
                .filter { it != 0 }
                .map(segments::get)
                .forEach { candidate ->
                    if (
                        size < MAX_EVIDENCE_SEGMENTS &&
                        sumOf(String::length) + candidate.length <= MAX_PROMPT_SOURCE_CHARS
                    ) {
                        add(candidate)
                    }
                }
        }

        return GemmaSummaryInput(
            source = source,
            selectedEvidence = selected,
        )
    }

    private fun segment(source: String): List<String> {
        val rough = mutableListOf<String>()
        var start = 0
        BOUNDARY.findAll(source).forEach { match ->
            val end = match.range.last + 1
            source.substring(start, end).trim().takeIf(String::isNotBlank)?.let(rough::add)
            start = end
        }
        if (start < source.length) {
            source.substring(start).trim().takeIf(String::isNotBlank)?.let(rough::add)
        }
        if (rough.isEmpty()) rough += source
        return rough.flatMap(::splitLongSegment).filter(String::isNotBlank)
    }

    private fun splitLongSegment(value: String): List<String> {
        if (value.length <= MAX_SEGMENT_CHARS) return listOf(value)
        val words = value.split(' ').filter(String::isNotBlank)
        if (words.isEmpty()) return listOf(value.take(MAX_SEGMENT_CHARS))
        return buildList {
            val current = StringBuilder()
            words.forEach { word ->
                val separator = if (current.isEmpty()) 0 else 1
                if (
                    current.isNotEmpty() &&
                    current.length + separator + word.length > MAX_SEGMENT_CHARS
                ) {
                    add(current.toString())
                    current.clear()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
            }
            if (current.isNotEmpty()) add(current.toString())
        }
    }

    private fun relevanceScore(text: String, tokenFrequency: Map<String, Int>): Int {
        val signals = IMPORTANT_SIGNAL.findAll(text).count() * 120
        val numericSignal = NUMBER.find(text)?.let { 8 } ?: 0
        val tokens = meaningfulTokens(text)
        val centrality = tokens.distinct().sumOf { token ->
            ((tokenFrequency[token.lowercase()] ?: 1) - 1).coerceIn(0, 4) * 8
        }
        return signals + numericSignal + centrality + tokens.distinct().size * 3 +
            text.length.coerceAtMost(MAX_SEGMENT_CHARS)
    }

    private fun meaningfulTokens(text: String): List<String> =
        TOKEN.findAll(text.lowercase())
            .map { normalizeToken(it.value) }
            .filter { it.length >= 2 }
            .filterNot(GENERIC_TOKENS::contains)
            .toList()

    private fun normalizeToken(value: String): String {
        var token = value
        PARTICLE_SUFFIXES.firstOrNull { suffix ->
            token.length - suffix.length >= 2 && token.endsWith(suffix)
        }?.let { token = token.dropLast(it.length) }
        return token
    }

    private fun positionBonus(index: Int, lastIndex: Int): Int =
        if (index == 0 || index == lastIndex) POSITION_BONUS else 0

    private fun leadTopicOverlapScore(text: String, leadTopics: Set<String>): Int =
        meaningfulTokens(text).distinct().count(leadTopics::contains) * LEAD_TOPIC_TOKEN_BONUS

    internal fun normalize(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    private const val MAX_SEGMENT_CHARS = 300
    private const val MAX_EVIDENCE_SEGMENTS = 2
    private const val MAX_PROMPT_SOURCE_CHARS = 700
    private const val POSITION_BONUS = 20
    private const val LEAD_TOPIC_TOKEN_BONUS = 100
    private val TOKEN = Regex("[가-힣A-Za-z0-9]{2,}")
    private val NUMBER = Regex("\\d+")
    private val IMPORTANT_SIGNAL =
        Regex("핵심|결론|문제|목표|필요|결정|이유|방법|해야|예정|구글|인공지능|에이전트")
    private val GENERIC_TOKENS = setOf(
        "그리고",
        "그런데",
        "하지만",
        "그래서",
        "이제",
        "저희",
        "우리",
        "오늘",
        "같아요",
        "있습니다",
        "합니다",
        "입니다",
        "것이다",
        "거예요",
        "생각",
    )
    private val PARTICLE_SUFFIXES = listOf(
        "으로부터",
        "에게서",
        "으로",
        "에서",
        "에게",
        "보다",
        "까지",
        "부터",
        "하고",
        "이며",
        "에는",
        "은",
        "는",
        "이",
        "가",
        "을",
        "를",
        "와",
        "과",
        "도",
        "만",
        "의",
        "에",
        "로",
    )

    private val BOUNDARY = Regex(
        """
        [.!?。！？]+
        |
        (?:
            했습니다|하였습니다|되었습니다|됐습니다|있습니다|없습니다|같습니다|
            합니다|됩니다|입니다|였습니다|것입니다|
            했어요|하였어요|됐어요|되었어요|있어요|없어요|같아요|
            해요|돼요|예요|이에요|거예요|거든요|는데요|네요|지요|죠
        )(?=\s+|$)
        """.trimIndent().replace(Regex("\\s+"), ""),
    )
}
