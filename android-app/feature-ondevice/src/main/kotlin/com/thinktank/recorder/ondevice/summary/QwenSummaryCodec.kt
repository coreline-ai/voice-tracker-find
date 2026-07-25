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
        )
    }

    private fun JSONArray.strings(): List<String> =
        buildList {
            for (index in 0 until length()) add(getString(index))
        }
}
