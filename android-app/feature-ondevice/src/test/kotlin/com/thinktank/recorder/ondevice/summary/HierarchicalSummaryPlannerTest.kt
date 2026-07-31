package com.thinktank.recorder.ondevice.summary

import com.thinktank.recorder.ondevice.data.OnDeviceSummaryNodeEntity
import com.thinktank.recorder.ondevice.data.OnDeviceTranscriptSegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HierarchicalSummaryPlannerTest {
    private val planner = HierarchicalSummaryPlanner()

    @Test
    fun twoHourTranscriptBuildsBoundedDeterministicRecursiveTree() {
        val segments = segmentsFor(chars = 44_800, durationMs = 2L * 60L * 60L * 1_000L)
        val first = buildTree(segments)
        val second = buildTree(segments)

        assertTrue(first.size > 64)
        assertEquals(first.map(OnDeviceSummaryNodeEntity::id), second.map(OnDeviceSummaryNodeEntity::id))
        assertTrue(first.all { it.inputPayload.length <= 700 })
        assertEquals(1, first.count { it.level == first.maxOf(OnDeviceSummaryNodeEntity::level) })

        val expectedLeafIds = segments.map(OnDeviceTranscriptSegmentEntity::id)
        assertEquals(emptyList<String>(), HierarchyCoverageValidator.validate(expectedLeafIds, first))
    }

    @Test
    fun boundaryInputsNeverExceedConfiguredLimit() {
        listOf(699, 700, 701, 10_000, 22_400, 44_800).forEach { chars ->
            val duration = (chars * 160L).coerceAtLeast(1_000L)
            val tree = buildTree(segmentsFor(chars, duration))
            assertTrue("chars=$chars", tree.all { it.inputPayload.length <= 700 })
        }
    }

    @Test
    fun validatorRejectsMissingLeafAndDuplicateParentReference() {
        val segments = segmentsFor(chars = 2_800, durationMs = 12 * 60_000L)
        val tree = buildTree(segments).toMutableList()
        val firstLeaf = tree.first { it.level == 0 }
        tree[tree.indexOf(firstLeaf)] = firstLeaf.copy(
            childNodeIds = firstLeaf.childNodeIds.substringAfter('\n', missingDelimiterValue = ""),
        )
        val violations = HierarchyCoverageValidator.validate(
            expectedLeafIds = segments.map(OnDeviceTranscriptSegmentEntity::id),
            nodes = tree,
        )
        assertTrue("LEAF_ASSIGNMENT_MISMATCH" in violations)
    }

    private fun buildTree(segments: List<OnDeviceTranscriptSegmentEntity>): List<OnDeviceSummaryNodeEntity> {
        val all = mutableListOf<OnDeviceSummaryNodeEntity>()
        var level = 0
        var plans = planner.planLeafNodes(JOB_ID, segments)
        while (true) {
            val entities = plans.map { planner.toEntity(JOB_ID, SESSION_ID, it, now = 1L) }
                .map { entity ->
                    entity.copy(
                        state = "PASSED",
                        summary = syntheticSummary(entity.ordinal, entity.level),
                    )
                }
            all += entities
            if (entities.size == 1) break
            level += 1
            plans = planner.planParentLevel(
                jobId = JOB_ID,
                level = level,
                children = entities.map {
                    SummaryNodeReference(
                        id = it.id,
                        level = it.level,
                        ordinal = it.ordinal,
                        sourceStartMs = it.sourceStartMs,
                        sourceEndMs = it.sourceEndMs,
                        leafStartOrdinal = it.leafStartOrdinal,
                        leafEndOrdinal = it.leafEndOrdinal,
                        summary = it.summary,
                        sourceHash = it.sourceHash,
                    )
                },
            )
        }
        return all
    }

    private fun segmentsFor(chars: Int, durationMs: Long): List<OnDeviceTranscriptSegmentEntity> {
        val text = buildString {
            while (length < chars) append("장시간 녹음의 모든 구간을 빠짐없이 확인하고 핵심 내용을 정리합니다. ")
        }.take(chars)
        val chunkSize = 240
        val chunks = text.chunked(chunkSize)
        val segmentDuration = durationMs / chunks.size.coerceAtLeast(1)
        return chunks.mapIndexed { index, chunk ->
            val start = index * segmentDuration
            val end = if (index == chunks.lastIndex) durationMs else (index + 1) * segmentDuration
            OnDeviceTranscriptSegmentEntity(
                id = "segment-$index",
                jobId = JOB_ID,
                sessionId = SESSION_ID,
                passType = "PRIMARY",
                ordinal = index,
                startMs = start,
                endMs = end.coerceAtLeast(start + 1L),
                text = chunk,
                textHash = "hash-$index",
                sourceRangeHash = "range-$index",
                meaningfulChars = chunk.count(Char::isLetterOrDigit),
                state = "PASSED",
                createdAt = 1L,
                updatedAt = 1L,
            )
        }
    }

    private fun syntheticSummary(ordinal: Int, level: Int): String =
        "장시간 녹음의 ${level + 1}단계 ${ordinal + 1}번째 핵심 내용을 빠짐없이 정리합니다."

    private companion object {
        const val JOB_ID = "job"
        const val SESSION_ID = "session"
    }
}
