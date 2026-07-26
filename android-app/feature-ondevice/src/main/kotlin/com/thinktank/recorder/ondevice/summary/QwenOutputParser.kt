package com.thinktank.recorder.ondevice.summary

import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import org.json.JSONObject

internal object QwenOutputParser {
    fun parse(raw: String, source: String): LocalSummary {
        val json = JSONObject(extractJsonObject(raw))
        val title = json.optString("title").normalize(MAX_TITLE)
        val bullets = json.optJSONArray("bullets")
            ?.let { array ->
                buildList {
                    for (index in 0 until minOf(array.length(), MAX_BULLETS)) {
                        array.optString(index)
                            .normalize(MAX_ITEM)
                            .takeIf(String::isNotBlank)
                            ?.takeIf { hasSpecificSourceEvidence(it, source) }
                            ?.let(::add)
                    }
                }
            }
            .orEmpty()
        val actionItems = json.optJSONArray("actionItems")
            ?.let { array ->
                buildList {
                    for (index in 0 until minOf(array.length(), MAX_ACTIONS)) {
                        array.optString(index)
                            .normalize(MAX_ITEM)
                            .takeIf(String::isNotBlank)
                            ?.takeIf { hasSourceEvidence(it, source) }
                            ?.let(::add)
                    }
                }
            }
            .orEmpty()

        require(title.isNotBlank()) { "Qwen 응답에 제목이 없습니다" }
        require(bullets.isNotEmpty()) { "Qwen 응답에 핵심 요약이 없습니다" }
        return LocalSummary(
            title = title,
            bullets = bullets.distinct(),
            actionItems = actionItems.distinct(),
            engine = SummaryEngineType.QWEN_LOCAL,
            sourceHash = sourceHash(source),
        )
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

    private fun String.normalize(maxLength: Int): String =
        replace(Regex("\\s+"), " ").trim().take(maxLength)

    private fun hasSourceEvidence(action: String, source: String): Boolean {
        val sourceLower = source.lowercase()
        val evidenceTokens = TOKEN.findAll(action.lowercase())
            .map { it.value }
            .filterNot { it in ACTION_STOP_WORDS }
            .toSet()
        return evidenceTokens.isNotEmpty() && evidenceTokens.any(sourceLower::contains)
    }

    private fun hasSpecificSourceEvidence(bullet: String, source: String): Boolean {
        val sourceLower = source.lowercase()
        val evidenceTokens = TOKEN.findAll(bullet.lowercase())
            .map { it.value }
            .filterNot { it in GENERIC_SUMMARY_WORDS }
            .toSet()
        return evidenceTokens.isNotEmpty() && evidenceTokens.any(sourceLower::contains)
    }

    private val TOKEN = Regex("[가-힣A-Za-z0-9]{2,}")
    private val ACTION_STOP_WORDS = setOf(
        "해야", "하기", "한다", "하기로", "예정", "필요", "확인", "요청", "담당",
    )
    private val GENERIC_SUMMARY_WORDS = setOf(
        "운영", "전략", "계획", "분석", "가이드", "가이드라인", "성공", "사례", "수익",
        "핵심", "내용", "설명", "정리", "소개", "사업", "결과", "방식", "전체", "최종",
    )
    private const val MAX_TITLE = 60
    private const val MAX_ITEM = 300
    private const val MAX_BULLETS = 2
    private const val MAX_ACTIONS = 5
}
