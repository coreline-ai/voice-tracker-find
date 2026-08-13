package com.coreline.ai.voice.cloudsummary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSummaryPromptParserTest {
    @Test
    fun promptContainsOnlyBoundedTranscriptAndStructuredContract() {
        val transcript = "가".repeat(CloudSummaryPrompt.MAX_INPUT_CHARS + 1_000)

        val input = CloudSummaryPrompt.build(transcript)

        assertEquals("air_voice_summary_v1", input.schema.name)
        assertTrue(input.schema.strict)
        assertTrue(input.messages.last().content.contains("중간 전사 생략"))
        assertTrue(input.messages.last().content.length < transcript.length + 200)
        assertTrue(input.messages.none { it.content.contains("token", ignoreCase = true) })
    }

    @Test
    fun parserAcceptsStrictSummaryJsonAndCodeFence() {
        val parsed = CloudSummaryParser.parse(
            """
            ```json
            {"title":"회의","bullets":["결정 1","결정 2"],"actionItems":["후속 확인"]}
            ```
            """.trimIndent(),
        )

        assertEquals("회의", parsed.title)
        assertEquals(listOf("결정 1", "결정 2"), parsed.bullets)
        assertEquals(listOf("후속 확인"), parsed.actionItems)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parserRejectsMissingBullets() {
        CloudSummaryParser.parse("""{"title":"회의","actionItems":[]}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parserRejectsEmptyBullets() {
        CloudSummaryParser.parse("""{"title":"회의","bullets":[],"actionItems":[]}""")
    }
}
