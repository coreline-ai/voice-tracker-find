package com.thinktank.recorder.ondevice.summary

import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import org.json.JSONArray
import org.json.JSONObject

internal data class ParsedQwenSummary(
    val summary: LocalSummary,
    val evidenceIds: List<Set<Int>>,
)

internal object QwenOutputParser {
    fun parse(
        raw: String,
        source: String,
        sourceSegments: List<SourceSegment> = KoreanTranscriptSegmenter().segment(source),
    ): ParsedQwenSummary {
        val json = JSONObject(extractJsonObject(raw))
        val title = normalize(json.optString("title"))
        val rows = json.optJSONArray("summary")
            ?: throw IllegalArgumentException("Qwen 응답에 summary 배열이 없습니다")
        val bullets = mutableListOf<String>()
        val evidenceIds = mutableListOf<Set<Int>>()
        for (index in 0 until minOf(rows.length(), SummaryPolicy.MAX_BULLETS + 1)) {
            val row = rows.optJSONObject(index) ?: continue
            val text = normalize(row.optString("text"))
            if (text.isBlank()) continue
            bullets += text
            evidenceIds += row.optJSONArray("evidenceIds").intSet()
        }
        val actionItems = json.optJSONArray("actionItems")
            ?.strings()
            .orEmpty()
            .map(::normalize)
            .filter(String::isNotBlank)
            .filter { hasStrongSourceEvidence(it, source) }
            .distinct()
            .take(MAX_ACTIONS)

        val summary = LocalSummary(
            title = title,
            bullets = bullets,
            actionItems = actionItems,
            engine = SummaryEngineType.QWEN_LOCAL,
            sourceHash = sourceHash(source),
            policyVersion = SummaryPolicy.VERSION,
            promptVersion = SummaryPolicy.PROMPT_VERSION,
            validationStatus = SUMMARY_VALIDATION_PASSED,
        )
        SummaryQualityGate().requireValid(summary, source, evidenceIds)
        val validIds = sourceSegments.map(SourceSegment::id).toSet()
        require(evidenceIds.all { ids -> ids.isNotEmpty() && ids.all(validIds::contains) }) {
            "Qwen 응답의 원문 근거 ID가 올바르지 않습니다"
        }
        return ParsedQwenSummary(summary, evidenceIds)
    }

    internal fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        require(start >= 0) { "Qwen 응답에 JSON 객체가 없습니다" }
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until raw.length) {
            val char = raw[index]
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
                    if (depth == 0) return raw.substring(start, index + 1)
                }
            }
        }
        error("Qwen JSON 객체가 완전하지 않습니다")
    }

    private fun hasStrongSourceEvidence(action: String, source: String): Boolean {
        val sourceCompact = normalize(source).lowercase().replace(" ", "")
        val evidenceTokens = TOKEN.findAll(action.lowercase())
            .map { it.value }
            .filterNot { it in ACTION_STOP_WORDS }
            .distinct()
            .toList()
        return evidenceTokens.count { sourceCompact.contains(it.replace(" ", "")) } >= 2
    }

    private fun JSONArray?.intSet(): Set<Int> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) {
                optInt(index).takeIf { it > 0 }?.let(::add)
            }
        }
    }

    private fun JSONArray.strings(): List<String> =
        buildList {
            for (index in 0 until length()) {
                optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }

    private fun normalize(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    private val TOKEN = Regex("[가-힣A-Za-z0-9]{2,}")
    private val ACTION_STOP_WORDS = setOf(
        "해야", "하기", "한다", "하기로", "예정", "필요", "확인", "요청", "담당",
    )
    private const val MAX_ACTIONS = 3
}
