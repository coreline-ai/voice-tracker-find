package com.thinktank.recorder.cloudsummary

import ai.coreline.oauthllm.api.JsonResponseSchema
import ai.coreline.oauthllm.api.LlmMessage
import ai.coreline.oauthllm.api.LlmMessageRole

internal data class CloudSummaryInput(
    val messages: List<LlmMessage>,
    val schema: JsonResponseSchema,
)

internal object CloudSummaryPrompt {
    const val PROMPT_VERSION = 1
    const val MAX_INPUT_CHARS = 48_000

    fun build(transcript: String): CloudSummaryInput {
        val normalized = transcript.trim()
        require(normalized.isNotBlank()) { "요약할 전사 원문이 없습니다." }
        val bounded = bound(normalized)
        return CloudSummaryInput(
            messages = listOf(
                LlmMessage(
                    LlmMessageRole.SYSTEM,
                    """
                    당신은 한국어 음성 기록 요약기입니다.
                    제공된 전사에 명시된 사실만 사용하고 추측하지 마세요.
                    title은 한 문장, bullets는 핵심 내용, actionItems는 명시된 후속 행동만 작성하세요.
                    후속 행동이 없으면 actionItems는 빈 배열이어야 합니다.
                    반드시 지정된 JSON schema만 반환하세요.
                    """.trimIndent(),
                ),
                LlmMessage(
                    LlmMessageRole.USER,
                    "전사 시작\n---\n$bounded\n---\n전사 끝",
                ),
            ),
            schema = JsonResponseSchema(
                name = "thinktank_summary_v1",
                schemaJson = SCHEMA,
                strict = true,
            ),
        )
    }

    private fun bound(value: String): String {
        if (value.length <= MAX_INPUT_CHARS) return value
        val head = (MAX_INPUT_CHARS * 3) / 4
        val tail = MAX_INPUT_CHARS - head
        return buildString(MAX_INPUT_CHARS + TRUNCATION_MARKER.length) {
            append(value.take(head))
            append(TRUNCATION_MARKER)
            append(value.takeLast(tail))
        }
    }

    private const val TRUNCATION_MARKER = "\n\n[중간 전사 생략: 입력 상한 적용]\n\n"
    private val SCHEMA =
        """
        {
          "type":"object",
          "additionalProperties":false,
          "required":["title","bullets","actionItems"],
          "properties":{
            "title":{"type":"string","minLength":1,"maxLength":160},
            "bullets":{"type":"array","maxItems":12,"items":{"type":"string","minLength":1,"maxLength":500}},
            "actionItems":{"type":"array","maxItems":12,"items":{"type":"string","minLength":1,"maxLength":500}}
          }
        }
        """.trimIndent()
}
