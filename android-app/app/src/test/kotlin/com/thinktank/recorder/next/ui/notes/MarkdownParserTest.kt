package com.thinktank.recorder.next.ui.notes

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun `hides leading frontmatter while retaining it when an edited note is saved`() {
        val original = """
            ---
            type: daily_section
            date: 2026-07-24
            ---
            # 중요한 기록

            본문입니다.
            """.trimIndent()

        assertEquals("# 중요한 기록\n\n본문입니다.", stripLeadingFrontMatter(original))
        assertEquals(
            "---\ntype: daily_section\ndate: 2026-07-24\n---\n# 수정한 기록",
            restoreLeadingFrontMatter(original, "# 수정한 기록"),
        )
        assertEquals("중요한 기록", noteDisplayTitle("2026-07-24_중요.md", original))
        assertEquals("2026-07-24 중요", noteDisplayTitle("2026-07-24_중요.md", "본문"))
        assertEquals(
            "녹음 전사 · 2026.07.24 14:42",
            archiveRecordingTitle(
                "rec_20260724_054245_6cfb701e-7d03-414e-bde0-b82de2aff60e_2026-07-24.md",
                ZoneId.of("Asia/Seoul"),
            ),
        )
        assertEquals(
            "음성 메모 · 2026.07.24 14:42",
            recordingMemoTitle(
                "rec_20260724_054245_6cfb701e-7d03-414e-bde0-b82de2aff60e_2026-07-24_memo.md",
                ZoneId.of("Asia/Seoul"),
            ),
        )
    }

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

    @Test
    fun `keeps transcript archives read only`() {
        assertTrue(isTranscriptArchive("90-archive"))
        assertFalse(isTranscriptArchive("20-daily"))
    }
}
