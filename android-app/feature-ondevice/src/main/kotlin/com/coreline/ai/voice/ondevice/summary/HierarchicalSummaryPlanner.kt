package com.coreline.ai.voice.ondevice.summary

import com.coreline.ai.voice.ondevice.data.OnDeviceSummaryNodeEntity
import com.coreline.ai.voice.ondevice.data.OnDeviceTranscriptSegmentEntity
import java.security.MessageDigest

data class SummaryNodePlan(
    val id: String,
    val level: Int,
    val ordinal: Int,
    val nodeType: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val leafStartOrdinal: Int,
    val leafEndOrdinal: Int,
    val childIds: List<String>,
    val inputPayload: String,
    val inputHash: String,
    val sourceHash: String,
)

data class SummaryNodeReference(
    val id: String,
    val level: Int,
    val ordinal: Int,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val leafStartOrdinal: Int,
    val leafEndOrdinal: Int,
    val summary: String,
    val sourceHash: String,
)

/**
 * Builds a deterministic, input-bounded reduction tree.
 *
 * The planner never chooses "important" portions of a long transcript. Every leaf part is assigned
 * in time order, and every parent consumes every child summary in order.
 */
class HierarchicalSummaryPlanner(
    private val maxInputChars: Int = MAX_NODE_INPUT_CHARS,
    private val targetWindowMs: Long = TARGET_WINDOW_MS,
    private val hardWindowMs: Long = HARD_WINDOW_MS,
) {
    init {
        require(maxInputChars >= MIN_INPUT_CHARS)
        require(targetWindowMs > 0L)
        require(hardWindowMs >= targetWindowMs)
    }

    fun planLeafNodes(
        jobId: String,
        segments: List<OnDeviceTranscriptSegmentEntity>,
    ): List<SummaryNodePlan> {
        val leaves = segments
            .sortedWith(compareBy<OnDeviceTranscriptSegmentEntity> { it.startMs }.thenBy { it.ordinal })
            .flatMap(::splitSegment)
        require(leaves.isNotEmpty()) { "요약할 STT 세그먼트가 없습니다." }
        val groups = mutableListOf<List<LeafPart>>()
        var current = mutableListOf<LeafPart>()

        fun closeCurrent() {
            if (current.isNotEmpty()) {
                groups += current.toList()
                current = mutableListOf()
            }
        }

        leaves.forEach { leaf ->
            val proposed = current + leaf
            val payload = proposed.joinToString("\n") { it.text }
            val duration = proposed.last().endMs - proposed.first().startMs
            val exceeds = payload.length > maxInputChars || duration > hardWindowMs
            if (current.isNotEmpty() && exceeds) closeCurrent()
            current += leaf
            val currentDuration = current.last().endMs - current.first().startMs
            if (
                current.joinToString("\n") { it.text }.length >= maxInputChars ||
                currentDuration >= targetWindowMs
            ) {
                closeCurrent()
            }
        }
        closeCurrent()

        return groups.mapIndexed { index, group ->
            val payload = group.joinToString("\n") { it.text }
            check(payload.length <= maxInputChars) { "SECTION 입력 상한을 넘었습니다." }
            plan(
                jobId = jobId,
                level = 0,
                ordinal = index,
                nodeType = NODE_TYPE_SECTION,
                sourceStartMs = group.first().startMs,
                sourceEndMs = group.last().endMs,
                leafStartOrdinal = group.first().leafOrdinal,
                leafEndOrdinal = group.last().leafOrdinal,
                childIds = group.map(LeafPart::id),
                inputPayload = payload,
                sourceHash = sha256(group.joinToString("|") { it.sourceHash }),
            )
        }
    }

    fun planParentLevel(
        jobId: String,
        level: Int,
        children: List<SummaryNodeReference>,
    ): List<SummaryNodePlan> {
        require(level > 0) { "상위 요약 level은 1 이상이어야 합니다." }
        require(children.isNotEmpty()) { "상위 요약에 사용할 하위 노드가 없습니다." }
        val sorted = children.sortedWith(compareBy<SummaryNodeReference> { it.ordinal }.thenBy { it.id })
        val groups = mutableListOf<List<SummaryNodeReference>>()
        var current = mutableListOf<SummaryNodeReference>()

        fun closeCurrent() {
            if (current.isNotEmpty()) {
                groups += current.toList()
                current = mutableListOf()
            }
        }

        sorted.forEach { child ->
            val normalized = normalize(child.summary)
            require(normalized.isNotBlank()) { "통과하지 않은 빈 하위 요약은 축약할 수 없습니다." }
            require(normalized.length <= maxInputChars) { "하위 요약 하나가 입력 상한을 넘었습니다." }
            val proposed = current + child.copy(summary = normalized)
            val payload = proposed.joinToString("\n") { it.summary }
            if (current.isNotEmpty() && payload.length > maxInputChars) closeCurrent()
            current += child.copy(summary = normalized)
            if (current.joinToString("\n") { it.summary }.length >= maxInputChars) closeCurrent()
        }
        closeCurrent()

        return groups.mapIndexed { index, group ->
            val payload = group.joinToString("\n") { it.summary }
            check(payload.length <= maxInputChars) { "REDUCE 입력 상한을 넘었습니다." }
            plan(
                jobId = jobId,
                level = level,
                ordinal = index,
                nodeType = if (groups.size == 1) NODE_TYPE_ROOT else NODE_TYPE_REDUCE,
                sourceStartMs = group.first().sourceStartMs,
                sourceEndMs = group.last().sourceEndMs,
                leafStartOrdinal = group.minOf(SummaryNodeReference::leafStartOrdinal),
                leafEndOrdinal = group.maxOf(SummaryNodeReference::leafEndOrdinal),
                childIds = group.map(SummaryNodeReference::id),
                inputPayload = payload,
                sourceHash = sha256(group.joinToString("|") { it.sourceHash }),
            )
        }
    }

    fun toEntity(
        jobId: String,
        sessionId: String,
        plan: SummaryNodePlan,
        now: Long,
    ): OnDeviceSummaryNodeEntity = OnDeviceSummaryNodeEntity(
        id = plan.id,
        jobId = jobId,
        sessionId = sessionId,
        level = plan.level,
        ordinal = plan.ordinal,
        nodeType = plan.nodeType,
        state = "PENDING",
        sourceStartMs = plan.sourceStartMs,
        sourceEndMs = plan.sourceEndMs,
        leafStartOrdinal = plan.leafStartOrdinal,
        leafEndOrdinal = plan.leafEndOrdinal,
        childNodeIds = plan.childIds.joinToString(CHILD_SEPARATOR),
        inputPayload = plan.inputPayload,
        inputHash = plan.inputHash,
        sourceHash = plan.sourceHash,
        createdAt = now,
        updatedAt = now,
    )

    private fun plan(
        jobId: String,
        level: Int,
        ordinal: Int,
        nodeType: String,
        sourceStartMs: Long,
        sourceEndMs: Long,
        leafStartOrdinal: Int,
        leafEndOrdinal: Int,
        childIds: List<String>,
        inputPayload: String,
        sourceHash: String,
    ): SummaryNodePlan {
        val inputHash = sha256(inputPayload)
        return SummaryNodePlan(
            id = sha256("$jobId:$level:$ordinal:$inputHash"),
            level = level,
            ordinal = ordinal,
            nodeType = nodeType,
            sourceStartMs = sourceStartMs,
            sourceEndMs = sourceEndMs,
            leafStartOrdinal = leafStartOrdinal,
            leafEndOrdinal = leafEndOrdinal,
            childIds = childIds,
            inputPayload = inputPayload,
            inputHash = inputHash,
            sourceHash = sourceHash,
        )
    }

    private fun splitSegment(segment: OnDeviceTranscriptSegmentEntity): List<LeafPart> {
        val normalized = normalize(segment.text)
        if (normalized.isBlank()) return emptyList()
        val chunks = splitBounded(normalized)
        val duration = (segment.endMs - segment.startMs).coerceAtLeast(1L)
        val totalChars = chunks.sumOf(String::length).coerceAtLeast(1)
        var consumedChars = 0
        return chunks.mapIndexed { index, text ->
            val start = segment.startMs + duration * consumedChars / totalChars
            consumedChars += text.length
            val end = if (index == chunks.lastIndex) {
                segment.endMs
            } else {
                segment.startMs + duration * consumedChars / totalChars
            }
            LeafPart(
                id = if (chunks.size == 1) segment.id else "${segment.id}#${index + 1}",
                leafOrdinal = segment.ordinal * LEAF_PART_MULTIPLIER + index,
                startMs = start,
                endMs = end.coerceAtLeast(start + 1L),
                text = text,
                sourceHash = segment.textHash,
            )
        }
    }

    private fun splitBounded(value: String): List<String> {
        if (value.length <= maxInputChars) return listOf(value)
        val words = value.split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return value.chunked(maxInputChars)
        return buildList {
            val current = StringBuilder()
            words.forEach { word ->
                if (word.length > maxInputChars) {
                    if (current.isNotEmpty()) {
                        add(current.toString())
                        current.clear()
                    }
                    addAll(word.chunked(maxInputChars))
                } else {
                    val separator = if (current.isEmpty()) 0 else 1
                    if (current.isNotEmpty() && current.length + separator + word.length > maxInputChars) {
                        add(current.toString())
                        current.clear()
                    }
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(word)
                }
            }
            if (current.isNotEmpty()) add(current.toString())
        }
    }

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private data class LeafPart(
        val id: String,
        val leafOrdinal: Int,
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val sourceHash: String,
    )

    companion object {
        const val MAX_NODE_INPUT_CHARS = 700
        const val TARGET_WINDOW_MS = 3L * 60L * 1_000L
        const val HARD_WINDOW_MS = 4L * 60L * 1_000L
        const val NODE_TYPE_SECTION = "SECTION"
        const val NODE_TYPE_REDUCE = "REDUCE"
        const val NODE_TYPE_ROOT = "ROOT"
        const val CHILD_SEPARATOR = "\n"
        private const val MIN_INPUT_CHARS = 80
        private const val LEAF_PART_MULTIPLIER = 1_000
    }
}

object HierarchyCoverageValidator {
    fun validate(
        expectedLeafIds: List<String>,
        nodes: List<OnDeviceSummaryNodeEntity>,
        maxInputChars: Int = HierarchicalSummaryPlanner.MAX_NODE_INPUT_CHARS,
    ): List<String> {
        if (nodes.isEmpty()) return listOf("TREE_EMPTY")
        val violations = mutableListOf<String>()
        if (nodes.any { it.inputPayload.length > maxInputChars }) violations += "NODE_INPUT_TOO_LARGE"
        val byLevel = nodes.groupBy(OnDeviceSummaryNodeEntity::level)
        val maxLevel = nodes.maxOf(OnDeviceSummaryNodeEntity::level)
        val roots = byLevel[maxLevel].orEmpty()
        if (roots.size != 1) violations += "ROOT_COUNT_${roots.size}"

        val leafChildren = byLevel[0].orEmpty()
            .flatMap { it.childNodeIds.split(HierarchicalSummaryPlanner.CHILD_SEPARATOR) }
            .filter(String::isNotBlank)
        if (leafChildren != expectedLeafIds) violations += "LEAF_ASSIGNMENT_MISMATCH"
        if (leafChildren.size != leafChildren.distinct().size) violations += "LEAF_DUPLICATED"

        for (level in 0 until maxLevel) {
            val children = byLevel[level].orEmpty().map(OnDeviceSummaryNodeEntity::id)
            val parentReferences = byLevel[level + 1].orEmpty()
                .flatMap { it.childNodeIds.split(HierarchicalSummaryPlanner.CHILD_SEPARATOR) }
                .filter(String::isNotBlank)
            if (parentReferences != children) violations += "LEVEL_${level}_PARENT_MISMATCH"
            if (parentReferences.size != parentReferences.distinct().size) {
                violations += "LEVEL_${level}_DUPLICATED"
            }
        }
        if (nodes.any { it.sourceStartMs >= it.sourceEndMs }) violations += "INVALID_TIME_RANGE"
        return violations.distinct()
    }
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
