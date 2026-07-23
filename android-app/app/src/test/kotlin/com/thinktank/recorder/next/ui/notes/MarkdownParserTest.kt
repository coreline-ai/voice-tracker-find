package com.thinktank.recorder.next.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun `parses headings wiki paragraphs and fenced code`() {
        val blocks = parseMarkdown(
            """
            # 기록

            [[노트|연결된 노트]]를 읽습니다.

            ```kotlin
            val answer = 42
            ```
            """.trimIndent(),
        )

        assertEquals(MarkdownBlock.Heading(1, "기록"), blocks[0])
        assertTrue(blocks[1] is MarkdownBlock.Paragraph)
        assertEquals(MarkdownBlock.Code("val answer = 42"), blocks[2])
    }

    @Test
    fun `parses a table and removes its delimiter row`() {
        val blocks = parseMarkdown(
            """
            | 항목 | 상태 |
            | --- | --- |
            | 업로드 | 완료 |
            """.trimIndent(),
        )

        val table = blocks.single() as MarkdownBlock.Table
        assertEquals(2, table.rows.size)
        assertEquals(listOf("항목", "상태"), table.rows.first())
        assertEquals(listOf("업로드", "완료"), table.rows.last())
    }
}
