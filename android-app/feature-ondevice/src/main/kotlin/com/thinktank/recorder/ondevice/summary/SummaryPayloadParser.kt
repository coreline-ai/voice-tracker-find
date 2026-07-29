package com.thinktank.recorder.ondevice.summary

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the compact summary contract shared by local LLM runtimes.
 *
 * LiteRT-LM Gemma can end a Markdown-fenced response after the complete `summary` array but
 * before the outer JSON object closes. The complete prefix is accepted only for LiteRT-LM and
 * still goes through [SummaryQualityGate] before a note can be persisted.
 */
internal object SummaryPayloadParser {
    fun parse(raw: String, allowCompletePrefix: Boolean): SummaryPayload =
        runCatching { parseStrict(raw) }.getOrElse { strictFailure ->
            if (!allowCompletePrefix) throw strictFailure
            parseCompletePrefix(raw, strictFailure)
        }

    private fun parseStrict(raw: String): SummaryPayload {
        val root = JSONObject(QwenOutputParser.extractJsonObject(raw))
        return SummaryPayload(
            title = normalizeText(root.optString("title")),
            bullets = root.summaryStrings(),
        )
    }

    private fun parseCompletePrefix(raw: String, strictFailure: Throwable): SummaryPayload {
        val body = raw
            .replace("```json", "", ignoreCase = true)
            .replace("```", "")
            .trim()
        val title = TITLE_VALUE.find(body)?.groupValues?.get(1)
            ?.let(::decodeJsonString)
            ?.let(::normalizeText)
            ?: throw strictFailure
        val summaryKey = SUMMARY_KEY.find(body)?.range?.last?.plus(1) ?: throw strictFailure
        val valueStart = body.indexOfFirstNonWhitespace(summaryKey)
        if (valueStart < 0) throw strictFailure
        val bullets = when (body[valueStart]) {
            '[' -> {
                val closeArray = matchingArrayEnd(body, valueStart) ?: throw strictFailure
                JSONArray(body.substring(valueStart, closeArray + 1)).toSummaryStrings()
            }
            '"' -> listOf(readJsonString(body, valueStart) ?: throw strictFailure)
            else -> throw strictFailure
        }
        return SummaryPayload(title = title, bullets = bullets.map(::normalizeText).filter(String::isNotBlank))
    }

    private fun matchingArrayEnd(value: String, start: Int): Int? {
        var quoted = false
        var escaped = false
        for (index in start until value.length) {
            val char = value[index]
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
                ']' -> return index
            }
        }
        return null
    }

    private fun JSONArray.toSummaryStrings(): List<String> = buildList {
        for (index in 0 until minOf(length(), SummaryPolicy.MAX_BULLETS + 1)) {
            val text = when (val value = opt(index)) {
                is String -> value
                is JSONObject -> value.optString("text")
                else -> ""
            }
            normalizeText(text).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun JSONObject.summaryStrings(): List<String> = when (val value = opt("summary")) {
        is JSONArray -> value.toSummaryStrings()
        is String -> listOf(normalizeText(value)).filter(String::isNotBlank)
        else -> throw IllegalArgumentException("응답에 summary 배열 또는 문자열이 없습니다")
    }

    private fun String.indexOfFirstNonWhitespace(startIndex: Int): Int {
        for (index in startIndex until length) if (!this[index].isWhitespace()) return index
        return -1
    }

    private fun readJsonString(value: String, start: Int): String? {
        var escaped = false
        for (index in start + 1 until value.length) {
            when {
                escaped -> escaped = false
                value[index] == '\\' -> escaped = true
                value[index] == '"' -> return decodeJsonString(value.substring(start + 1, index))
            }
        }
        return null
    }

    private fun decodeJsonString(value: String): String =
        JSONObject("{\"value\":\"$value\"}").getString("value")

    private val TITLE_VALUE = Regex("""\"title\"\s*:\s*\"((?:\\.|[^\"])*)\"""")
    private val SUMMARY_KEY = Regex("""\"summary\"\s*:""")
}

internal data class SummaryPayload(
    val title: String,
    val bullets: List<String>,
)
