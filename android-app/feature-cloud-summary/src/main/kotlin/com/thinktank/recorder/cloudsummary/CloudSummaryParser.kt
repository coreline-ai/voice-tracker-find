package com.thinktank.recorder.cloudsummary

import org.json.JSONArray
import org.json.JSONObject

internal data class ParsedCloudSummary(
    val title: String,
    val bullets: List<String>,
    val actionItems: List<String>,
)

internal object CloudSummaryParser {
    fun parse(raw: String): ParsedCloudSummary {
        val text = raw.trim().removeCodeFence()
        val json = JSONObject(text)
        val title = json.requiredText("title", 160)
        val bullets = json.requiredArray("bullets")
        val actionItems = json.requiredArray("actionItems")
        require(bullets.isNotEmpty()) { "요약 핵심 항목이 비어 있습니다." }
        return ParsedCloudSummary(title, bullets, actionItems)
    }

    private fun JSONObject.requiredText(name: String, maxLength: Int): String {
        require(has(name) && !isNull(name)) { "$name 필드가 없습니다." }
        return getString(name).trim().also {
            require(it.isNotBlank() && it.length <= maxLength) { "$name 필드가 유효하지 않습니다." }
        }
    }

    private fun JSONObject.requiredArray(name: String): List<String> {
        require(has(name) && !isNull(name)) { "$name 필드가 없습니다." }
        val values = getJSONArray(name)
        require(values.length() <= MAX_ITEMS) { "$name 항목이 너무 많습니다." }
        return values.textItems(name)
    }

    private fun JSONArray.textItems(name: String): List<String> =
        List(length()) { index ->
            getString(index).trim().also {
                require(it.isNotBlank() && it.length <= MAX_ITEM_CHARS) {
                    "$name[$index] 항목이 유효하지 않습니다."
                }
            }
        }

    private fun String.removeCodeFence(): String {
        if (!startsWith("```")) return this
        val firstLineEnd = indexOf('\n')
        val lastFence = lastIndexOf("```")
        require(firstLineEnd >= 0 && lastFence > firstLineEnd) { "JSON code fence가 닫히지 않았습니다." }
        return substring(firstLineEnd + 1, lastFence).trim()
    }

    private const val MAX_ITEMS = 12
    private const val MAX_ITEM_CHARS = 500
}
