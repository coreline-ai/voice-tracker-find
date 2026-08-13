package com.coreline.ai.voice.ui.notes

import com.coreline.ai.voice.data.local.NoteEntity
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
    fun `separates transcript archives from ordinary archived notes`() {
        val transcript = NoteEntity(
            serverId = "transcript",
            folder = "90-archive",
            name = "rec_20260724_054245_sample.md",
            content = "---\ntype: archive\n---\n# 전사 원본",
            revision = "1",
            updatedAt = "2026-07-24T00:00:00Z",
        )
        val archivedNote = NoteEntity(
            serverId = "archived-note",
            folder = "90-archive",
            name = "keep.md",
            content = "# 보관한 노트",
            revision = "1",
            updatedAt = "2026-07-24T00:00:00Z",
        )

        assertTrue(isArchiveFolder(transcript.folder))
        assertTrue(isTranscriptArchive(transcript))
        assertFalse(isTranscriptArchive(archivedNote))
        assertEquals("보관한 노트", noteTitle(archivedNote))
        assertEquals("원문 전사 보관함", noteFolderLabel("90-archive"))
        assertTrue(NoteListFilter.TRANSCRIPTS.matches(transcript))
        assertFalse(NoteListFilter.TRANSCRIPTS.matches(archivedNote.copy(folder = "30-ideas")))
    }

    @Test
    fun `resolves a transcript link after its archive filename gained a suffix`() {
        val transcript = NoteEntity(
            serverId = "archive-id",
            folder = "90-archive",
            name = "rec_20260724_063807_sample_2026-07-24_2.md",
            content = """
                ---
                type: archive
                date: 2026-07-24
                source_file: rec_20260724_063807_sample.m4a
                ---
                # 전사 원본
            """.trimIndent(),
            revision = "1",
            updatedAt = "2026-07-24T00:00:00Z",
        )

        assertEquals(
            "rec_20260724_063807_sample_2026-07-24",
            transcriptWikiTarget(transcript),
        )
        assertEquals(
            transcript.serverId,
            findWikiLinkedNote(
                listOf(transcript),
                "rec_20260724_063807_sample_2026-07-24",
            )?.serverId,
        )
    }
}
