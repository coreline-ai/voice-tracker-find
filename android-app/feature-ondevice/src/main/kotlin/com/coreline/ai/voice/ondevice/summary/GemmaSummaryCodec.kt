package com.coreline.ai.voice.ondevice.summary

import com.coreline.ai.voice.ondevice.api.LocalSummary
import com.coreline.ai.voice.ondevice.api.SummaryEngineType
import org.json.JSONArray
import org.json.JSONObject

internal object GemmaSummaryCodec {
    fun encode(summary: LocalSummary): String =
        JSONObject()
            .put("title", summary.title)
            .put("bullets", JSONArray(summary.bullets))
            .put("actionItems", JSONArray(summary.actionItems))
            .put("engine", summary.engine.name)
            .put("sourceHash", summary.sourceHash)
            .put("modelVersion", summary.modelVersion)
            .put("validationStatus", summary.validationStatus)
            .put("requestedModelId", summary.requestedModelId)
            .put("actualModelId", summary.actualModelId)
            .put("runtimeType", summary.runtimeType)
            .put("generationProfile", summary.generationProfile)
            .put("durationMs", summary.durationMs)
            .put("inputChars", summary.inputChars)
            .put("outputChars", summary.outputChars)
            .toString()

    fun decode(json: String): LocalSummary {
        val root = JSONObject(json)
        return LocalSummary(
            title = root.getString("title"),
            bullets = root.getJSONArray("bullets").strings(),
            actionItems = root.optJSONArray("actionItems")?.strings().orEmpty(),
            engine = SummaryEngineType.GEMMA_LOCAL,
            sourceHash = root.getString("sourceHash"),
            modelVersion = root.optNullableString("modelVersion"),
            validationStatus = root.optNullableString("validationStatus"),
            requestedModelId = root.optNullableString("requestedModelId"),
            actualModelId = root.optNullableString("actualModelId"),
            runtimeType = root.optNullableString("runtimeType"),
            generationProfile = root.optNullableString("generationProfile"),
            durationMs = root.optNullableLong("durationMs"),
            inputChars = root.optNullableInt("inputChars"),
            outputChars = root.optNullableInt("outputChars"),
        )
    }

    private fun JSONArray.strings(): List<String> =
        buildList {
            for (index in 0 until length()) {
                optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key).takeIf { it >= 0 }

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key).takeIf { it >= 0 }
}
