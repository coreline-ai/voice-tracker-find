package com.coreline.ai.voice.ui.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coreline.ai.voice.R
import com.coreline.ai.voice.data.local.NoteEntity
import com.coreline.ai.voice.data.local.NoteSyncState
import com.coreline.ai.voice.ui.NotesUiState
import com.coreline.ai.voice.ui.common.ImageState
import com.coreline.ai.voice.ui.common.SectionLabel
import com.coreline.ai.voice.ui.common.StatusPill
import com.coreline.ai.voice.ui.theme.ArchiveInk
import com.coreline.ai.voice.ui.theme.ArchiveNoteCopper
import com.coreline.ai.voice.ui.theme.ArchivePaper
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun NotesScreen(
    state: NotesUiState,
    onSync: () -> Unit,
    onOpen: (String) -> Unit,
    onCreate: (String, String, String, (String) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreate by remember { mutableStateOf(false) }
    var noteFilter by remember { mutableStateOf(NoteListFilter.ALL) }
    val archiveCount = state.notes.count { it.folder == ARCHIVE_FOLDER }
    val filteredNotes = state.notes.filter { noteFilter.matches(it) }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "노트",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    "정리된 메모와 원문 전사를 함께 확인합니다",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onSync,
                enabled = !state.syncing,
                modifier = Modifier.sizeIn(48.dp, 48.dp),
            ) {
                if (state.syncing) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.sizeIn(20.dp, 20.dp))
                } else {
                    Icon(Icons.Default.Sync, contentDescription = "지금 동기화")
                }
            }
            IconButton(onClick = { showCreate = true }, modifier = Modifier.sizeIn(48.dp, 48.dp)) {
                Icon(Icons.Default.Add, contentDescription = "새 노트")
            }
        }

        state.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        if (state.notes.isEmpty()) {
            ImageState(
                image = R.drawable.empty_notes_desk,
                title = "아직 도착한 노트가 없습니다",
                body = "녹음을 마친 뒤 동기화하면 서버가 정리한 노트가 이곳에 쌓입니다.",
                action = {
                    Button(onClick = onSync, enabled = !state.syncing) {
                        if (state.syncing) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.sizeIn(18.dp, 18.dp))
                            Text(" 동기화 중")
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Text(" 동기화")
                        }
                    }
                },
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NoteListFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = noteFilter == filter,
                        onClick = { noteFilter = filter },
                        label = {
                            Text(
                                when (filter) {
                                    NoteListFilter.ALL -> "전체 ${state.notes.size}"
                                    NoteListFilter.MEMOS -> "정리 노트 ${state.notes.size - archiveCount}"
                                    NoteListFilter.TRANSCRIPTS -> "원문 전사 $archiveCount"
                                },
                            )
                        },
                    )
                }
            }
            if (filteredNotes.isEmpty()) {
                Text(
                    "이 분류에 보관된 항목이 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                val grouped = filteredNotes.groupBy(NoteEntity::folder)
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 32.dp,
                ),
            ) {
                grouped.keys.sortedBy(::noteFolderOrder).forEach { folder ->
                    val notes = grouped.getValue(folder)
                    item(key = "header:$folder") {
                        SectionLabel(noteFolderLabel(folder), Modifier.padding(top = 22.dp))
                    }
                    items(notes, key = NoteEntity::serverId) { note ->
                        NoteRow(note = note, onClick = { onOpen(note.serverId) })
                    }
                }
            }
            }
        }
    }

    if (showCreate) {
        CreateNoteDialog(
            busy = state.busy,
            onDismiss = { showCreate = false },
            onCreate = { folder, name, content ->
                onCreate(folder, name, content) {
                    showCreate = false
                    onOpen(it)
                }
            },
        )
    }
}

@Composable
private fun NoteRow(note: NoteEntity, onClick: () -> Unit) {
    val visibleContent = stripLeadingFrontMatter(note.content)
    val title = noteTitle(note)
    val preview = visibleContent.lineSequence()
        .filterNot { it.startsWith("#") || it.isBlank() }
        .take(2)
        .joinToString(" ")
        .ifBlank { "내용 없음" }

    Row(
        Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 82.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusPill(
            text = when (note.syncState) {
                NoteSyncState.SYNCED -> "동기화"
                NoteSyncState.DIRTY -> "편집됨"
                NoteSyncState.CONFLICT -> "충돌"
                NoteSyncState.PENDING_DELETE -> "보관 중"
                else -> "확인"
            },
            good = note.syncState == NoteSyncState.SYNCED,
        )
    }
}

@Composable
private fun CreateNoteDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("30-ideas") }
    var content by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("새 노트") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("30-ideas", "10-daily", "1 wiki").forEach {
                        FilterChip(
                            selected = folder == it,
                            onClick = { folder = it },
                            label = { Text(it) },
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("파일 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("내용") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = { onCreate(folder, name, content.ifBlank { "# $name\n" }) },
            ) { Text("만들기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    note: NoteEntity?,
    allNotes: List<NoteEntity>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onSave: (String, String) -> Unit,
    onArchive: (String, () -> Unit) -> Unit,
) {
    var editing by remember(note?.serverId) { mutableStateOf(false) }
    var content by remember(note?.serverId, note?.content) {
        mutableStateOf(stripLeadingFrontMatter(note?.content.orEmpty()))
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        note?.let(::noteTitle) ?: "노트",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    if (note != null && !isArchiveFolder(note.folder)) {
                        IconButton(
                            onClick = {
                                if (editing) {
                                    onSave(note.serverId, restoreLeadingFrontMatter(note.content, content))
                                    editing = false
                                } else {
                                    editing = true
                                }
                            },
                        ) {
                            Icon(
                                if (editing) Icons.Default.Save else Icons.Default.Edit,
                                contentDescription = if (editing) "저장" else "편집",
                            )
                        }
                        IconButton(onClick = { onArchive(note.serverId, onBack) }) {
                            Icon(Icons.Default.Archive, contentDescription = "보관")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(ArchivePaper),
        ) {
            Image(
                painter = painterResource(R.drawable.texture_archive_paper),
                contentDescription = null,
                alpha = 0.12f,
                modifier = Modifier.fillMaxSize(),
            )
            if (note == null) {
                Text(
                    "노트를 찾을 수 없습니다",
                    color = ArchiveInk,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                ) {
                    if (note.syncState == NoteSyncState.CONFLICT) {
                        Text(
                            note.lastError ?: "편집 충돌이 있습니다",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }
                    if (isArchiveFolder(note.folder)) {
                        StatusPill(
                            text = if (isTranscriptArchive(note)) {
                                "원문 전사 · 읽기 전용"
                            } else {
                                "보관 노트 · 읽기 전용"
                            },
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }
                    if (editing) {
                        OutlinedTextField(
                            value = content,
                            onValueChange = { content = it },
                            minLines = 18,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ArchiveInk),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        MarkdownDocument(
                            content = note.content,
                            onWikiLink = { target ->
                                findWikiLinkedNote(allNotes, target)?.let { onOpen(it.serverId) }
                            },
                        )
                    }
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }
}

@Composable
private fun WikiLinkText(
    content: String,
    onWikiLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val annotated = remember(content) { buildWikiText(content, ArchiveNoteCopper) }
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = style.copy(color = ArchiveInk),
        onClick = { offset ->
            annotated.getStringAnnotations("wiki", offset, offset)
                .firstOrNull()
                ?.let { onWikiLink(it.item) }
        },
    )
}

private val WIKI = Regex("""\[\[([^]|]+)(?:\|([^]]+))?]]""")

private fun buildWikiText(content: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    WIKI.findAll(content).forEach { match ->
        append(content.substring(cursor, match.range.first))
        val target = match.groupValues[1]
        val alias = match.groupValues[2].ifBlank { target }
        pushStringAnnotation("wiki", target)
        pushStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold))
        append(alias)
        pop()
        pop()
        cursor = match.range.last + 1
    }
    append(content.substring(cursor))
}

internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

private val leadingFrontMatter = Regex(
    """\A---[ \t]*\r?\n[\s\S]*?\r?\n---[ \t]*(?:\r?\n)?""",
)

internal fun stripLeadingFrontMatter(content: String): String =
    leadingFrontMatter.replaceFirst(content, "")

internal fun restoreLeadingFrontMatter(originalContent: String, editedBody: String): String {
    val frontMatter = leadingFrontMatter.find(originalContent)?.value.orEmpty()
    return frontMatter + editedBody.trimStart('\r', '\n')
}

internal fun noteDisplayTitle(name: String, content: String): String =
    stripLeadingFrontMatter(content)
        .lineSequence()
        .firstOrNull { it.startsWith("# ") }
        ?.removePrefix("# ")
        ?.trim()
        .orEmpty()
        .ifBlank { name.removeSuffix(".md").replace('_', ' ') }

internal fun noteTitle(note: NoteEntity): String =
    if (isTranscriptArchive(note)) {
        archiveRecordingTitle(note.name)
    } else if (isRecordingMemo(note)) {
        recordingMemoTitle(note.name)
    } else {
        noteDisplayTitle(note.name, note.content)
    }

private const val ARCHIVE_FOLDER = "90-archive"

internal enum class NoteListFilter {
    ALL,
    MEMOS,
    TRANSCRIPTS;

    fun matches(note: NoteEntity): Boolean = when (this) {
        ALL -> true
        MEMOS -> note.folder != ARCHIVE_FOLDER
        TRANSCRIPTS -> note.folder == ARCHIVE_FOLDER
    }
}

internal fun isArchiveFolder(folder: String): Boolean = folder == ARCHIVE_FOLDER

internal fun isTranscriptArchive(note: NoteEntity): Boolean =
    isArchiveFolder(note.folder) && "\ntype: archive\n" in note.content

private val FRONTMATTER_VALUE = Regex("""(?m)^([A-Za-z_]+):\s*(.+?)\s*$""")

/**
 * A transcript can acquire a `_2` suffix when a prior client tried to archive
 * it again. Resolve memo links from immutable frontmatter instead of relying
 * on the mutable archive filename.
 */
internal fun transcriptWikiTarget(note: NoteEntity): String? {
    if (!isTranscriptArchive(note)) return null
    val values = FRONTMATTER_VALUE.findAll(note.content).associate {
        it.groupValues[1] to it.groupValues[2]
    }
    val sourceFile = values["source_file"] ?: return null
    val date = values["date"] ?: return null
    return "${sourceFile.substringBeforeLast('.', sourceFile)}_$date"
}

internal fun findWikiLinkedNote(allNotes: List<NoteEntity>, target: String): NoteEntity? {
    val normalizedTarget = target.removeSuffix(".md")
    return allNotes.firstOrNull {
        it.name.removeSuffix(".md") == normalizedTarget
    } ?: allNotes.firstOrNull {
        transcriptWikiTarget(it) == normalizedTarget
    }
}

internal fun noteFolderLabel(folder: String): String = when (folder) {
    "30-ideas" -> "정리된 음성 메모"
    ARCHIVE_FOLDER -> "원문 전사 보관함"
    "10-daily" -> "오늘의 기록"
    "1 wiki" -> "연결된 주제"
    else -> folder
}

private fun noteFolderOrder(folder: String): Int = when (folder) {
    "30-ideas" -> 0
    ARCHIVE_FOLDER -> 1
    "10-daily" -> 2
    "1 wiki" -> 3
    else -> 4
}

internal fun isRecordingMemo(note: NoteEntity): Boolean =
    note.folder == "30-ideas" && "\ntype: recording_memo\n" in note.content

private val ARCHIVE_RECORDING_FILENAME = Regex(
    """^rec_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})_""",
)
private val ARCHIVE_SOURCE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
private val ARCHIVE_DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

internal fun archiveRecordingTitle(name: String, zone: ZoneId = ZoneId.systemDefault()): String {
    val filename = name.removeSuffix(".md")
    val match = ARCHIVE_RECORDING_FILENAME.find(filename)
        ?: return "녹음 전사 · $filename"
    val recordedAt = runCatching {
        LocalDateTime.parse(match.groupValues.drop(1).joinToString(""), ARCHIVE_SOURCE_TIME)
            .atOffset(ZoneOffset.UTC)
            .atZoneSameInstant(zone)
            .format(ARCHIVE_DISPLAY_TIME)
    }.getOrNull() ?: return "녹음 전사 · $filename"
    return "녹음 전사 · $recordedAt"
}

internal fun recordingMemoTitle(name: String, zone: ZoneId = ZoneId.systemDefault()): String =
    archiveRecordingTitle(name, zone).replaceFirst("녹음 전사", "음성 메모")

internal fun parseMarkdown(content: String): List<MarkdownBlock> {
    val lines = content.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        when {
            trimmed.isBlank() -> index += 1
            trimmed.startsWith("```") -> {
                val code = mutableListOf<String>()
                index += 1
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    code += lines[index]
                    index += 1
                }
                if (index < lines.size) index += 1
                blocks += MarkdownBlock.Code(code.joinToString("\n"))
            }
            isTableLine(trimmed) -> {
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && isTableLine(lines[index].trim())) {
                    val cells = lines[index].trim().trim('|').split('|').map(String::trim)
                    if (!cells.all { it.matches(Regex(""":?-{3,}:?""")) }) rows += cells
                    index += 1
                }
                if (rows.isNotEmpty()) blocks += MarkdownBlock.Table(rows)
            }
            trimmed.matches(Regex("""#{1,3}\s+.+""")) -> {
                val level = trimmed.takeWhile { it == '#' }.length
                blocks += MarkdownBlock.Heading(level, trimmed.drop(level).trim())
                index += 1
            }
            trimmed in setOf("---", "***", "___") -> {
                blocks += MarkdownBlock.Divider
                index += 1
            }
            trimmed.startsWith("> ") -> {
                blocks += MarkdownBlock.Quote(trimmed.removePrefix("> ").trim())
                index += 1
            }
            trimmed.matches(Regex("""[-*+]\s+.+""")) -> {
                blocks += MarkdownBlock.Bullet(trimmed.drop(2).trim())
                index += 1
            }
            else -> {
                val paragraph = mutableListOf(trimmed)
                index += 1
                while (
                    index < lines.size &&
                    lines[index].isNotBlank() &&
                    !isSpecialMarkdownLine(lines[index].trim())
                ) {
                    paragraph += lines[index].trim()
                    index += 1
                }
                blocks += MarkdownBlock.Paragraph(paragraph.joinToString(" "))
            }
        }
    }
    return blocks
}

private fun isTableLine(line: String): Boolean =
    line.startsWith("|") && line.endsWith("|") && line.count { it == '|' } >= 3

private fun isSpecialMarkdownLine(line: String): Boolean =
    line.startsWith("```") ||
        isTableLine(line) ||
        line.matches(Regex("""#{1,3}\s+.+""")) ||
        line in setOf("---", "***", "___") ||
        line.startsWith("> ") ||
        line.matches(Regex("""[-*+]\s+.+"""))

@Composable
private fun MarkdownDocument(content: String, onWikiLink: (String) -> Unit) {
    val blocks = remember(content) { parseMarkdown(stripLeadingFrontMatter(content)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> WikiLinkText(
                    content = block.text,
                    onWikiLink = onWikiLink,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineLarge
                        2 -> MaterialTheme.typography.headlineMedium
                        else -> MaterialTheme.typography.titleLarge
                    },
                )
                is MarkdownBlock.Paragraph -> WikiLinkText(
                    content = block.text,
                    onWikiLink = onWikiLink,
                )
                is MarkdownBlock.Quote -> WikiLinkText(
                    content = block.text,
                    onWikiLink = onWikiLink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, ArchiveNoteCopper.copy(alpha = 0.45f))
                        .padding(14.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = com.coreline.ai.voice.ui.theme.MaruBuri,
                    ),
                )
                is MarkdownBlock.Bullet -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("•", color = ArchiveNoteCopper)
                    WikiLinkText(
                        content = block.text,
                        onWikiLink = onWikiLink,
                        modifier = Modifier.weight(1f),
                    )
                }
                is MarkdownBlock.Code -> Text(
                    block.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ArchiveInk.copy(alpha = 0.08f))
                        .padding(14.dp),
                    color = ArchiveInk,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
                is MarkdownBlock.Table -> MarkdownTable(
                    rows = block.rows,
                    onWikiLink = onWikiLink,
                )
                MarkdownBlock.Divider -> HorizontalDivider(
                    color = ArchiveInk.copy(alpha = 0.18f),
                )
            }
        }
    }
}

@Composable
private fun MarkdownTable(rows: List<List<String>>, onWikiLink: (String) -> Unit) {
    val columnCount = rows.maxOfOrNull { it.size } ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, ArchiveInk.copy(alpha = 0.2f)),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row(
                Modifier.background(
                    if (rowIndex == 0) ArchiveNoteCopper.copy(alpha = 0.12f) else Color.Transparent,
                ),
            ) {
                repeat(columnCount) { columnIndex ->
                    WikiLinkText(
                        content = row.getOrElse(columnIndex) { "" },
                        onWikiLink = onWikiLink,
                        modifier = Modifier
                            .weight(1f)
                            .border(0.5.dp, ArchiveInk.copy(alpha = 0.14f))
                            .padding(10.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (rowIndex == 0) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            },
                        ),
                    )
                }
            }
        }
    }
}
