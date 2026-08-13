package com.coreline.ai.voice.ondevice.summary

import com.coreline.ai.voice.ondevice.api.LocalSummary
import com.coreline.ai.voice.ondevice.api.SummaryEngineType
import org.json.JSONArray
import org.json.JSONObject

internal object GemmaSummaryParser {
    fun parse(
        rawOutput: String,
        sourceHash: String,
        source: String,
        selectedEvidence: List<String>,
    ): LocalSummary {
        val normalizedSource = GemmaSummaryInputBuilder.normalize(source)
        val candidates = extractSummaryValues(rawOutput)
            .flatMap(::sentenceCandidates)
            .map(::cleanLine)
            .filter(String::isNotBlank)
            .filterNot(::isInstructionPlaceholder)
            .distinct()
        val bullet = candidates
            .mapNotNull { candidate ->
                validateAndScore(candidate, normalizedSource)?.let { score ->
                    candidate to score
                }
            }
            .maxWithOrNull(
                compareBy<Pair<String, Int>> { it.second }
                    .thenByDescending { it.first.length },
            )
            ?.first
            ?: throw IllegalArgumentException(
                "Gemma 결과가 원문 근거·길이·완결성 검사를 통과하지 못했습니다.",
            )
        val title = deterministicTitle(bullet, selectedEvidence)

        return LocalSummary(
            title = title,
            bullets = listOf(bullet),
            actionItems = emptyList(),
            engine = SummaryEngineType.GEMMA_LOCAL,
            sourceHash = sourceHash,
            validationStatus = VALIDATION_STATUS,
        )
    }

    private fun extractSummaryValues(rawOutput: String): List<String> {
        val normalized = rawOutput
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val json = runCatching { extractJsonObject(normalized) }.getOrNull()
        if (json != null) {
            val root = JSONObject(json)
            return when (val summary = root.opt("summary")) {
                is String -> listOf(summary)
                is JSONArray -> buildList {
                    for (index in 0 until summary.length()) {
                        summary.optString(index).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
                else -> root.optJSONArray("bullets")?.strings().orEmpty()
            }
        }
        require(!normalized.contains('{') && !normalized.contains('}')) {
            "Gemma 응답의 JSON이 완전하지 않습니다."
        }
        return listOf(normalized)
    }

    private fun extractJsonObject(output: String): String {
        val start = output.indexOf('{')
        require(start >= 0) { "Gemma 응답에서 JSON 시작을 찾지 못했습니다." }
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until output.length) {
            val char = output[index]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
                continue
            }
            when (char) {
                '"' -> quoted = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return output.substring(start, index + 1)
                }
            }
        }
        error("Gemma 응답의 JSON이 끝나지 않았습니다.")
    }

    private fun sentenceCandidates(value: String): List<String> {
        val normalized = GemmaSummaryInputBuilder.normalize(value)
        if (normalized.isBlank()) return emptyList()
        return buildList {
            if (normalized.length <= MAX_BULLET_CHARS) add(normalized)
            COMPLETE_SENTENCE.findAll(normalized)
                .map { it.value.trim() }
                .filter { it.length in MIN_BULLET_CHARS..MAX_BULLET_CHARS }
                .forEach(::add)
        }
    }

    private fun validateAndScore(
        candidate: String,
        source: String,
    ): Int? {
        if (candidate.length !in MIN_BULLET_CHARS..MAX_BULLET_CHARS) return null
        if (!COMPLETE_ENDING.containsMatchIn(candidate.trim())) return null
        if (DETACHED_SENTENCE_ENDING.containsMatchIn(candidate.trim())) return null
        if (candidate.contains("...") || candidate.contains('…')) return null
        if (NUMBER.findAll(candidate).map { it.value }.any { it !in source }) return null
        val sourceLower = source.lowercase()
        if (
            LATIN.findAll(candidate)
                .map { it.value.lowercase() }
                .any { it !in sourceLower }
        ) {
            return null
        }
        val tokens = contentTokens(candidate)
        if (tokens.size < 2) return null
        if (!tokenAppears(tokens.first(), source)) return null
        val matches = tokens.count { tokenAppears(it, source) }
        val ratio = matches.toDouble() / tokens.size.toDouble()
        if (matches < 2 || ratio < MIN_EVIDENCE_RATIO) return null
        val salientTopics = salientTopicTokens(source)
        val topicMatches = salientTopics.count { tokenAppears(it, candidate) }
        val requiredTopicMatches = minOf(MIN_SALIENT_TOPIC_MATCHES, salientTopics.size)
        if (
            source.length >= MIN_SOURCE_CHARS_FOR_TOPIC_GATE &&
            salientTopics.isNotEmpty() &&
            topicMatches < requiredTopicMatches
        ) {
            return null
        }
        return matches * 100 + topicMatches * 200 + (ratio * 100).toInt()
    }

    /**
     * Selects a small deterministic set from the complete source of record, rather than the
     * prompt's reduced evidence. Frequency is preferred, while first appearance keeps
     * equal-frequency inputs stable.
     */
    private fun salientTopicTokens(value: String): List<String> {
        val tokens = TOKEN.findAll(value.lowercase())
            .map { normalizeToken(it.value) }
            .filter { it.length >= 2 }
            .filterNot { it in STOP_WORDS || it in GENERIC_TOPIC_TOKENS }
            .toList()
        if (tokens.isEmpty()) return emptyList()
        val counts = tokens.groupingBy { it }.eachCount()
        val firstIndex = tokens.withIndex().associate { it.value to it.index }
        return tokens.distinct()
            .sortedWith(
                compareByDescending<String> { counts.getValue(it) }
                    .thenBy { firstIndex.getValue(it) },
            )
            .take(MAX_SALIENT_TOPICS)
    }

    private fun deterministicTitle(summary: String, evidence: List<String>): String {
        val evidenceCompact = evidence.joinToString(" ")
            .lowercase()
            .replace(" ", "")
        val matchedTokens = contentTokens(summary)
            .filter { evidenceCompact.contains(it.lowercase().replace(" ", "")) }
        val topicTokens = matchedTokens.filter(TOPIC_TOKENS::contains)
        val tokens = if (topicTokens.size >= 2) {
            topicTokens.distinct().take(TITLE_TOKEN_COUNT)
        } else {
            (topicTokens + matchedTokens).distinct().take(TITLE_TOKEN_COUNT)
        }
        return tokens.joinToString(" ")
            .take(MAX_TITLE_CHARS)
            .ifBlank {
                summary
                    .trimEnd('.', '!', '?', '。', '！', '？')
                    .take(MAX_TITLE_CHARS)
            }
    }

    private fun contentTokens(value: String): List<String> =
        TOKEN.findAll(value.lowercase())
            .map { normalizeToken(it.value) }
            .filter { it.length >= 2 }
            .filterNot { it in STOP_WORDS }
            .distinct()
            .toList()

    private fun normalizeToken(value: String): String {
        var token = value
        PARTICLE_SUFFIXES.firstOrNull { suffix ->
            token.length - suffix.length >= 2 && token.endsWith(suffix)
        }?.let { token = token.dropLast(it.length) }
        return token
    }

    private fun tokenAppears(token: String, source: String): Boolean =
        source.lowercase().replace(" ", "").contains(token.replace(" ", ""))

    private fun isInstructionPlaceholder(value: String): Boolean {
        val compact = value.lowercase().replace(" ", "")
        return PLACEHOLDERS.any(compact::contains)
    }

    private fun cleanLine(value: String): String =
        value.trim()
            .removePrefix("-")
            .removePrefix("•")
            .removePrefix("요약:")
            .trim()

    private fun JSONArray.strings(): List<String> =
        buildList {
            for (index in 0 until length()) {
                optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }

    internal const val VALIDATION_STATUS = "PASSED_GROUNDED_V6"
    private const val MAX_TITLE_CHARS = 28
    private const val MIN_BULLET_CHARS = 10
    private const val MAX_BULLET_CHARS = 80
    private const val TITLE_TOKEN_COUNT = 3
    private const val MIN_EVIDENCE_RATIO = 0.30
    private const val MIN_SOURCE_CHARS_FOR_TOPIC_GATE = 80
    private const val MIN_SALIENT_TOPIC_MATCHES = 2
    private const val MAX_SALIENT_TOPICS = 8
    private val TOKEN = Regex("[가-힣A-Za-z0-9]{2,}")
    private val NUMBER = Regex("""\d+(?:[.,]\d+)?""")
    private val LATIN = Regex("[A-Za-z][A-Za-z0-9_-]+")
    private val COMPLETE_ENDING = Regex(
        """(?:다|요|함|임|음|됨|한다|했다|된다|이다|였다|있다|없다)[.!?。！？]?$""",
    )
    private val DETACHED_SENTENCE_ENDING = Regex("""\s[다요함임음][.!?。！？]?$""")
    private val COMPLETE_SENTENCE = Regex(
        """.{10,80}?(?:합니다|됩니다|입니다|했습니다|한다|했다|된다|이다|있다|없다|해요|돼요|예요|이에요|다|요)[.!?。！？]?""",
    )
    private val PLACEHOLDERS = setOf(
        "짧은제목",
        "핵심1",
        "핵심2",
        "핵심3",
        "완결된문장",
        "완결된핵심문장",
        "실제요약",
        "summaryvalue",
    )
    private val TOPIC_TOKENS = setOf(
        "구글",
        "메모리",
        "데이터",
        "인공지능",
        "에이전트",
        "생태계",
        "개인화",
        "모델",
        "트랜스포먼",
        "멀티모",
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
    private val STOP_WORDS = setOf(
        "그리고",
        "그러나",
        "그래서",
        "하지만",
        "대한",
        "위한",
        "있는",
        "없는",
        "합니다",
        "입니다",
        "했다",
        "한다",
        "하는",
        "것은",
        "것을",
        "으로",
        "에서",
        "에게",
        "까지",
        "부터",
        "또한",
        "이번",
        "현재",
        "정도",
        "통해",
        "것이다",
        "거예요",
        "생각",
    )
    private val GENERIC_TOPIC_TOKENS = setOf(
        "담당자",
        "관련",
        "관련하여",
        "여부",
        "보고",
        "문제",
        "발견",
        "기록",
        "원인",
        "수정",
        "단계",
        "근거",
        "사용",
        "제목",
        "핵심",
        "생성",
        "입력",
        "출력",
        "숫자",
        "나오면",
        "결과",
        "저장",
        "다시",
        "확인",
        "정확하게",
        "설명",
    )
}
