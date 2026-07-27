package com.thinktank.recorder.ondevice.summary

import com.thinktank.recorder.ondevice.api.LocalSummary

internal object SummaryPolicy {
    const val VERSION = 3
    const val PROMPT_VERSION = 3
    const val MAX_BULLETS = 2
    const val MAX_BULLET_CHARS = 80
    const val MAX_TOTAL_CHARS = 100
    const val MAX_TITLE_CHARS = 28
    const val MIN_BULLET_CHARS = 10
    private const val MIN_TOTAL_BUDGET = 32
    private const val MAX_SOURCE_RATIO = 0.20

    fun totalBudget(transcript: String): Int {
        val sourceLength = normalizeText(transcript).length
        if (sourceLength <= 1) return 0
        return maxOf(
            MIN_TOTAL_BUDGET,
            (sourceLength * MAX_SOURCE_RATIO).toInt(),
        ).coerceAtMost(MAX_TOTAL_CHARS).coerceAtMost(sourceLength - 1)
    }
}

internal enum class SummaryViolationCode {
    EMPTY_TITLE,
    TITLE_TOO_LONG,
    EMPTY_SUMMARY,
    TOO_MANY_BULLETS,
    BULLET_TOO_SHORT,
    BULLET_TOO_LONG,
    TOTAL_TOO_LONG,
    NOT_SHORTER_THAN_SOURCE,
    INCOMPLETE_BULLET,
    GENERIC_BULLET,
    WEAK_SOURCE_EVIDENCE,
    INVALID_EVIDENCE_ID,
    UNSUPPORTED_NUMBER,
    UNSUPPORTED_LATIN_TERM,
    DUPLICATE_TITLE,
    DUPLICATE_BULLET,
}

internal data class SummaryValidationResult(
    val violations: Set<SummaryViolationCode>,
) {
    val valid: Boolean get() = violations.isEmpty()

    fun correctionHint(): String = violations
        .sortedBy(Enum<*>::name)
        .joinToString(",") { it.name }
}

internal class SummaryQualityException(
    val validation: SummaryValidationResult,
) : IllegalArgumentException(
    "요약 품질 검사 실패: ${validation.correctionHint()}",
)

/**
 * Validates semantic safety without changing generated text.
 *
 * A failed sentence is rejected and regenerated or replaced by a source-grounded fallback.
 * It is never sliced at an arbitrary character boundary.
 */
internal class SummaryQualityGate(
    private val segmenter: KoreanTranscriptSegmenter = KoreanTranscriptSegmenter(),
) {
    fun validate(
        summary: LocalSummary,
        transcript: String,
        evidenceIds: List<Set<Int>>? = null,
    ): SummaryValidationResult {
        val source = normalizeText(transcript)
        val bullets = summary.bullets.map(::normalizeText).filter(String::isNotBlank)
        val violations = linkedSetOf<SummaryViolationCode>()
        val sourceSegments = segmenter.segment(source).associateBy(SourceSegment::id)

        val title = normalizeText(summary.title)
        if (title.isBlank()) violations += SummaryViolationCode.EMPTY_TITLE
        if (title.length > SummaryPolicy.MAX_TITLE_CHARS || hasEllipsis(title)) {
            violations += SummaryViolationCode.TITLE_TOO_LONG
        }
        if (bullets.isEmpty()) violations += SummaryViolationCode.EMPTY_SUMMARY
        if (bullets.size > SummaryPolicy.MAX_BULLETS) {
            violations += SummaryViolationCode.TOO_MANY_BULLETS
        }

        val persisted = bullets.joinToString("\n")
        if (persisted.length > SummaryPolicy.totalBudget(source)) {
            violations += SummaryViolationCode.TOTAL_TOO_LONG
        }
        if (source.isNotEmpty() && persisted.length >= source.length) {
            violations += SummaryViolationCode.NOT_SHORTER_THAN_SOURCE
        }

        bullets.forEachIndexed { index, bullet ->
            if (bullet.length < SummaryPolicy.MIN_BULLET_CHARS) {
                violations += SummaryViolationCode.BULLET_TOO_SHORT
            }
            if (bullet.length > SummaryPolicy.MAX_BULLET_CHARS) {
                violations += SummaryViolationCode.BULLET_TOO_LONG
            }
            if (!isCompleteStatement(bullet) || hasEllipsis(bullet)) {
                violations += SummaryViolationCode.INCOMPLETE_BULLET
            }

            val contentTokens = contentTokens(bullet)
            if (contentTokens.size < 2) {
                violations += SummaryViolationCode.GENERIC_BULLET
            }

            val referenced = evidenceIds?.getOrNull(index)
            val evidenceText = if (referenced == null) {
                source
            } else {
                val invalidId = referenced.isEmpty() || referenced.any { it !in sourceSegments }
                if (invalidId) violations += SummaryViolationCode.INVALID_EVIDENCE_ID
                referenced.mapNotNull(sourceSegments::get).joinToString(" ") { it.text }
            }
            val evidenceMatches = contentTokens.count { tokenAppears(it, evidenceText) }
            val evidenceRatio = if (contentTokens.isEmpty()) {
                0.0
            } else {
                evidenceMatches.toDouble() / contentTokens.size.toDouble()
            }
            if (
                evidenceMatches < minOf(2, contentTokens.size) ||
                evidenceRatio < MIN_EVIDENCE_TOKEN_RATIO
            ) {
                violations += SummaryViolationCode.WEAK_SOURCE_EVIDENCE
            }

            if (NUMBER.findAll(bullet).map { it.value }.any { it !in source }) {
                violations += SummaryViolationCode.UNSUPPORTED_NUMBER
            }
            if (
                LATIN.findAll(bullet)
                    .map { it.value.lowercase() }
                    .any { it !in source.lowercase() }
            ) {
                violations += SummaryViolationCode.UNSUPPORTED_LATIN_TERM
            }
            if (tokenSimilarity(title, bullet) >= TITLE_DUPLICATE_THRESHOLD) {
                violations += SummaryViolationCode.DUPLICATE_TITLE
            }
        }

        for (left in bullets.indices) {
            for (right in left + 1 until bullets.size) {
                if (tokenSimilarity(bullets[left], bullets[right]) >= BULLET_DUPLICATE_THRESHOLD) {
                    violations += SummaryViolationCode.DUPLICATE_BULLET
                }
            }
        }
        return SummaryValidationResult(violations)
    }

    fun requireValid(
        summary: LocalSummary,
        transcript: String,
        evidenceIds: List<Set<Int>>? = null,
    ): LocalSummary {
        val validation = validate(summary, transcript, evidenceIds)
        if (!validation.valid) throw SummaryQualityException(validation)
        return summary.copy(
            title = normalizeText(summary.title),
            bullets = summary.bullets.map(::normalizeText).filter(String::isNotBlank),
            actionItems = summary.actionItems.map(::normalizeText).filter(String::isNotBlank),
            policyVersion = SummaryPolicy.VERSION,
            validationStatus = SUMMARY_VALIDATION_PASSED,
        )
    }

    private fun isCompleteStatement(value: String): Boolean {
        val compact = value.trim().trimEnd('.', '!', '?', '。', '！', '？')
        return COMPLETE_ENDING.containsMatchIn(compact)
    }

    private fun hasEllipsis(value: String): Boolean =
        value.contains('…') || value.contains("...")

    private fun tokenSimilarity(left: String, right: String): Double {
        val leftTokens = contentTokens(left).toSet()
        val rightTokens = contentTokens(right).toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
        return leftTokens.intersect(rightTokens).size.toDouble() /
            leftTokens.union(rightTokens).size.toDouble()
    }

    private fun contentTokens(value: String): List<String> =
        TOKEN.findAll(value.lowercase())
            .map { normalizeToken(it.value) }
            .filter { it.length >= 2 }
            .filterNot { it in STOP_WORDS || it in GENERIC_WORDS }
            .distinct()
            .toList()

    private fun normalizeToken(value: String): String {
        var token = value
        PARTICLE_SUFFIXES.firstOrNull { suffix ->
            token.length - suffix.length >= 2 && token.endsWith(suffix)
        }?.let { token = token.dropLast(it.length) }
        return token
    }

    private fun tokenAppears(token: String, source: String): Boolean {
        val compactSource = normalizeText(source).lowercase().replace(" ", "")
        return compactSource.contains(token.replace(" ", ""))
    }

    private companion object {
        val TOKEN = Regex("[가-힣A-Za-z0-9]{2,}")
        val NUMBER = Regex("""\d+(?:[.,]\d+)?""")
        val LATIN = Regex("[A-Za-z][A-Za-z0-9_-]+")
        val COMPLETE_ENDING = Regex(
            """(?:다|요|함|임|음|됨|한다|했다|된다|이다|였다|있다|없다)$""",
        )
        const val TITLE_DUPLICATE_THRESHOLD = 0.85
        const val BULLET_DUPLICATE_THRESHOLD = 0.60
        // Evidence IDs establish provenance. A lower lexical threshold permits normal Korean
        // predicate/synonym compression while number and Latin-entity checks remain strict.
        const val MIN_EVIDENCE_TOKEN_RATIO = 0.30
        val PARTICLE_SUFFIXES = listOf(
            "으로부터", "에게서", "으로", "에서", "에게", "보다", "까지", "부터",
            "하고", "이며", "에는", "으로", "은", "는", "이", "가", "을", "를",
            "와", "과", "도", "만", "의", "에", "로",
        )
        val STOP_WORDS = setOf(
            "그리고", "그러나", "그래서", "하지만", "대한", "위한", "있는", "없는",
            "합니다", "입니다", "했다", "한다", "하는", "것은", "것을", "수가", "으로",
            "에서", "에게", "까지", "부터", "또한", "이번", "현재", "정도", "통해",
            "설명한다", "정리한다", "소개한다", "나타났다",
        )
        val GENERIC_WORDS = setOf(
            "운영", "전략", "계획", "분석", "가이드", "가이드라인", "성공", "사례",
            "수익", "핵심", "내용", "설명", "정리", "소개", "사업", "결과", "방식",
            "전체", "최종", "수준", "비교",
        )
    }
}

internal const val SUMMARY_VALIDATION_PASSED = "PASSED"
internal const val SUMMARY_VALIDATION_FALLBACK = "FALLBACK_PASSED"

internal fun normalizeText(value: String): String =
    value.replace(Regex("\\s+"), " ").trim()
