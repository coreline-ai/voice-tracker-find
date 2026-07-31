package com.thinktank.recorder.ondevice.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OnDeviceLongProcessingDao {
    @Query(
        """
        SELECT * FROM ondevice_processing_jobs
        WHERE state NOT IN ('COMPLETE', 'CANCELLED', 'FAILED_PERMANENT')
        ORDER BY updatedAt DESC
        LIMIT 1
        """,
    )
    fun observeLatestActive(): Flow<OnDeviceProcessingJobEntity?>

    @Query("SELECT * FROM ondevice_processing_jobs WHERE id = :id")
    suspend fun getJob(id: String): OnDeviceProcessingJobEntity?

    @Query(
        """
        SELECT * FROM ondevice_processing_jobs
        WHERE state IN ('RUNNING', 'PAUSING', 'CANCELLING')
        ORDER BY updatedAt ASC
        """,
    )
    suspend fun interruptedJobs(): List<OnDeviceProcessingJobEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJob(job: OnDeviceProcessingJobEntity)

    @Update
    suspend fun updateJob(job: OnDeviceProcessingJobEntity)

    @Query(
        """
        UPDATE ondevice_processing_jobs
        SET state = 'RUNNING',
            serviceToken = :serviceToken,
            pauseRequested = 0,
            cancelRequested = 0,
            failureCode = NULL,
            error = NULL,
            updatedAt = :now
        WHERE id = :id
          AND serviceToken IS NULL
          AND state IN ('QUEUED', 'INTERRUPTED', 'PAUSED', 'FAILED_RECOVERABLE')
        """,
    )
    suspend fun claim(id: String, serviceToken: String, now: Long): Int

    @Query(
        """
        UPDATE ondevice_processing_jobs
        SET stage = :stage,
            completedSttSegments = :completedSttSegments,
            totalSttSegments = :totalSttSegments,
            completedSummaryNodes = :completedSummaryNodes,
            totalSummaryNodes = :totalSummaryNodes,
            currentSummaryLevel = :currentSummaryLevel,
            updatedAt = :now
        WHERE id = :id
          AND serviceToken = :serviceToken
          AND state = 'RUNNING'
        """,
    )
    suspend fun updateProgress(
        id: String,
        serviceToken: String,
        stage: String,
        completedSttSegments: Int,
        totalSttSegments: Int,
        completedSummaryNodes: Int,
        totalSummaryNodes: Int,
        currentSummaryLevel: Int,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE ondevice_processing_jobs
        SET pauseRequested = 1,
            state = CASE WHEN state = 'RUNNING' THEN 'PAUSING' ELSE state END,
            updatedAt = :now
        WHERE id = :id
          AND state IN ('RUNNING', 'QUEUED')
        """,
    )
    suspend fun requestPause(id: String, now: Long): Int

    @Query(
        """
        UPDATE ondevice_processing_jobs
        SET cancelRequested = 1,
            state = CASE
                WHEN state IN ('RUNNING', 'PAUSING') THEN 'CANCELLING'
                ELSE state
            END,
            updatedAt = :now
        WHERE id = :id
          AND state NOT IN ('COMPLETE', 'CANCELLED', 'FAILED_PERMANENT')
        """,
    )
    suspend fun requestCancel(id: String, now: Long): Int

    @Query(
        """
        UPDATE ondevice_processing_jobs
        SET state = :state,
            stage = :stage,
            serviceToken = NULL,
            rootNodeId = :rootNodeId,
            failureCode = :failureCode,
            error = :error,
            updatedAt = :now
        WHERE id = :id
          AND serviceToken = :serviceToken
        """,
    )
    suspend fun finish(
        id: String,
        serviceToken: String,
        state: String,
        stage: String,
        rootNodeId: String?,
        failureCode: String?,
        error: String?,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE ondevice_processing_jobs
        SET state = 'INTERRUPTED',
            serviceToken = NULL,
            failureCode = 'PROCESS_INTERRUPTED',
            error = '앱 또는 처리 서비스가 종료되었습니다. 마지막 완료 지점부터 다시 시작할 수 있습니다.',
            updatedAt = :now
        WHERE state IN ('RUNNING', 'PAUSING', 'CANCELLING')
        """,
    )
    suspend fun recoverInterrupted(now: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSegment(segment: OnDeviceTranscriptSegmentEntity): Long

    @Query(
        """
        SELECT * FROM ondevice_transcript_segments
        WHERE jobId = :jobId AND state = 'PASSED'
        ORDER BY startMs ASC, ordinal ASC
        """,
    )
    suspend fun passedSegments(jobId: String): List<OnDeviceTranscriptSegmentEntity>

    @Query(
        """
        SELECT * FROM ondevice_transcript_segments
        WHERE jobId = :jobId AND passType = 'PRIMARY'
        ORDER BY ordinal ASC
        """,
    )
    suspend fun primarySegments(jobId: String): List<OnDeviceTranscriptSegmentEntity>

    @Query("SELECT COUNT(*) FROM ondevice_transcript_segments WHERE jobId = :jobId AND state = 'PASSED'")
    suspend fun passedSegmentCount(jobId: String): Int

    @Query(
        """
        SELECT segment.* FROM ondevice_transcript_segments AS segment
        WHERE segment.jobId = (
            SELECT job.id FROM ondevice_processing_jobs AS job
            WHERE job.sessionId = :sessionId
              AND job.id != :excludingJobId
              AND job.completedSttSegments > 0
            ORDER BY job.createdAt DESC
            LIMIT 1
        )
          AND segment.state = 'PASSED'
        ORDER BY segment.startMs ASC, segment.ordinal ASC
        """,
    )
    suspend fun latestPassedSttSegmentsForSession(
        sessionId: String,
        excludingJobId: String,
    ): List<OnDeviceTranscriptSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNode(node: OnDeviceSummaryNodeEntity): Long

    @Update
    suspend fun updateNode(node: OnDeviceSummaryNodeEntity)

    @Query("SELECT * FROM ondevice_summary_nodes WHERE id = :id")
    suspend fun getNode(id: String): OnDeviceSummaryNodeEntity?

    @Query(
        """
        SELECT * FROM ondevice_summary_nodes
        WHERE jobId = :jobId AND level = :level
        ORDER BY ordinal ASC
        """,
    )
    suspend fun nodesAtLevel(jobId: String, level: Int): List<OnDeviceSummaryNodeEntity>

    @Query(
        """
        SELECT * FROM ondevice_summary_nodes
        WHERE jobId = :jobId
        ORDER BY level ASC, ordinal ASC
        """,
    )
    suspend fun allNodes(jobId: String): List<OnDeviceSummaryNodeEntity>

    @Query("SELECT COUNT(*) FROM ondevice_summary_nodes WHERE jobId = :jobId AND state = 'PASSED'")
    suspend fun passedNodeCount(jobId: String): Int
}
