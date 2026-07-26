package com.thinktank.recorder.ondevice.summary

import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import org.json.JSONArray
import org.json.JSONObject

internal object QwenSummaryCodec {
    fun encode(summary: LocalSummary): String =
        JSONObject()
            .put("title", summary.title)
            .put("bullets", JSONArray(summary.bullets))
            .put("actionItems", JSONArray(summary.actionItems))
            .put("engine", summary.engine.name)
            .put("sourceHash", summary.sourceHash)
            .put("fallbackReason", summary.fallbackReason)
            .put("policyVersion", summary.policyVersion)
            .put("promptVersion", summary.promptVersion)
            .put("modelVersion", summary.modelVersion)
            .put("validationStatus", summary.validationStatus)
            .toString()

    fun decode(json: String): LocalSummary {
        val root = JSONObject(json)
        return LocalSummary(
            title = root.getString("title"),
            bullets = root.getJSONArray("bullets").strings(),
            actionItems = root.getJSONArray("actionItems").strings(),
            engine = runCatching {
                SummaryEngineType.valueOf(root.optString("engine"))
            }.getOrDefault(SummaryEngineType.QWEN_LOCAL),
            sourceHash = root.getString("sourceHash"),
            fallbackReason = root.optNullableString("fallbackReason"),
            policyVersion = root.optNullableInt("policyVersion"),
            promptVersion = root.optNullableInt("promptVersion"),
            modelVersion = root.optNullableString("modelVersion"),
            validationStatus = root.optNullableString("validationStatus"),
        )
    }

    private fun JSONArray.strings(): List<String> =
        buildList {
            for (index in 0 until length()) add(getString(index))
        }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key).takeIf { it > 0 }
}
