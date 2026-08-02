package com.thinktank.recorder.ondevice.summary

/**
 * Builds the compact user-facing projection for a hierarchical summary tree.
 *
 * The root remains the first, overall summary. A bounded number of SECTION summaries are sampled
 * across the recording so a long recording does not collapse into a single sentence or expand
 * into an unbounded card.
 */
object HierarchicalSummaryProjection {
    data class Result(
        val bullets: List<String>,
        val sectionHighlightCount: Int,
    )

    fun build(
        rootSummary: String,
        sectionSummaries: List<String>,
        maxSectionHighlights: Int = MAX_SECTION_HIGHLIGHTS,
    ): Result {
        require(maxSectionHighlights >= 0)
        val root = normalize(rootSummary)
        require(root.isNotBlank()) { "최종 계층형 요약이 비어 있습니다." }

        val candidates = sectionSummaries
            .map(::normalize)
            .filter(String::isNotBlank)
            .filterNot { equivalent(it, root) }
            .fold(mutableListOf<String>()) { accepted, candidate ->
                if (accepted.none { equivalent(it, candidate) }) accepted += candidate
                accepted
            }
        val selected = evenlySample(candidates, maxSectionHighlights)
        return Result(
            bullets = listOf(root) + selected,
            sectionHighlightCount = selected.size,
        )
    }

    private fun evenlySample(values: List<String>, limit: Int): List<String> {
        if (limit == 0 || values.isEmpty()) return emptyList()
        if (values.size <= limit) return values
        if (limit == 1) return listOf(values[values.lastIndex / 2])
        return (0 until limit)
            .map { index -> index * values.lastIndex / (limit - 1) }
            .distinct()
            .map(values::get)
    }

    private fun equivalent(left: String, right: String): Boolean {
        val leftCompact = compact(left)
        val rightCompact = compact(right)
        if (leftCompact == rightCompact) return true
        val shorter = minOf(leftCompact.length, rightCompact.length)
        if (shorter < MIN_CONTAINMENT_CHARS) return false
        return leftCompact.contains(rightCompact) || rightCompact.contains(leftCompact)
    }

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private fun compact(value: String): String =
        value.lowercase().replace(Regex("[^가-힣a-z0-9]"), "")

    private const val MAX_SECTION_HIGHLIGHTS = 4
    private const val MIN_CONTAINMENT_CHARS = 12
}

data class SummaryEvidenceCandidate(
    val id: String,
    val text: String,
)

data class HierarchicalGroundingResult(
    val passed: Boolean,
    val evidenceIds: List<String>,
    val violationCodes: List<String>,
)

/** Grounding gate used after Gemma parsing and before a tree node is marked PASSED. */
object HierarchicalSummaryGrounding {
    fun evaluate(
        summary: String,
        candidates: List<SummaryEvidenceCandidate>,
    ): HierarchicalGroundingResult {
        val source = candidates.joinToString(" ", transform = SummaryEvidenceCandidate::text)
        val sourceCompact = compact(source)
        val violations = mutableListOf<String>()
        if (summary.isBlank() || candidates.isEmpty()) violations += "GROUNDING_SOURCE_EMPTY"
        if (NUMBER.findAll(summary).map { it.value }.any { it !in source }) {
            violations += "UNSUPPORTED_NUMBER"
        }
        val sourceLower = source.lowercase()
        if (LATIN.findAll(summary).map { it.value.lowercase() }.any { it !in sourceLower }) {
            violations += "UNSUPPORTED_LATIN"
        }

        val summaryTokens = contentTokens(summary)
        val matchingTokens = summaryTokens.filter { sourceCompact.contains(compact(it)) }
        val unsupportedKoreanTokens = summaryTokens.filter { token ->
            KOREAN.matches(token) && token !in matchingTokens
        }
        val tokenRatio = if (summaryTokens.isEmpty()) {
            0.0
        } else {
            matchingTokens.size.toDouble() / summaryTokens.size.toDouble()
        }
        if (matchingTokens.size < MIN_MATCHING_TOKENS || tokenRatio < MIN_TOKEN_RATIO) {
            violations += "WEAK_CHILD_EVIDENCE"
        }
        if (unsupportedKoreanTokens.size > MAX_UNSUPPORTED_KOREAN_TOKENS) {
            violations += "UNSUPPORTED_KOREAN_TERMS"
        }

        val evidenceIds = candidates.mapNotNull { candidate ->
            val candidateCompact = compact(candidate.text)
            val directMatches = matchingTokens.count { candidateCompact.contains(compact(it)) }
            candidate.id.takeIf { directMatches >= MIN_CHILD_MATCHING_TOKENS }
        }.distinct()
        if (evidenceIds.isEmpty()) violations += "EVIDENCE_CHILD_NOT_FOUND"

        return HierarchicalGroundingResult(
            passed = violations.isEmpty(),
            evidenceIds = evidenceIds,
            violationCodes = violations.distinct(),
        )
    }

    private fun contentTokens(value: String): List<String> =
        TOKEN.findAll(value.lowercase())
            .map { normalizeToken(it.value) }
            .filter { it.length >= 2 }
            .filterNot(STOP_WORDS::contains)
            .distinct()
            .toList()

    private fun normalizeToken(value: String): String {
        var token = value
        PARTICLE_SUFFIXES.firstOrNull { suffix ->
            token.length - suffix.length >= 2 && token.endsWith(suffix)
        }?.let { token = token.dropLast(it.length) }
        return token
    }

    private fun compact(value: String): String =
        value.lowercase().replace(Regex("[^가-힣a-z0-9]"), "")

    private const val MIN_MATCHING_TOKENS = 2
    private const val MIN_CHILD_MATCHING_TOKENS = 1
    private const val MIN_TOKEN_RATIO = 0.50
    private const val MAX_UNSUPPORTED_KOREAN_TOKENS = 1
    private val TOKEN = Regex("[가-힣A-Za-z0-9]{2,}")
    private val KOREAN = Regex("[가-힣]{2,}")
    private val NUMBER = Regex("""\d+(?:[.,]\d+)?""")
    private val LATIN = Regex("[A-Za-z][A-Za-z0-9_-]+")
    private val STOP_WORDS = setOf(
        "그리고", "그러나", "그래서", "하지만", "대한", "위한", "있는", "없는",
        "합니다", "입니다", "했다", "한다", "하는", "것은", "것을", "또한", "현재",
        "내용", "결과", "방법", "계획", "관련", "핵심", "요약", "팀", "회의", "녹음",
    )
    private val PARTICLE_SUFFIXES = listOf(
        "으로부터", "에게서", "으로", "에서", "에게", "보다", "까지", "부터", "하고",
        "이며", "에는", "은", "는", "이", "가", "을", "를", "와", "과", "도", "만",
        "의", "에", "로",
    )
}
