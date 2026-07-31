package com.thinktank.recorder.ondevice.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ondevice_sessions",
    indices = [Index(value = ["createdAt"])],
)
data class OnDeviceSessionEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val state: String,
    val sttEngine: String,
    val summaryEngine: String,
    val transcript: String = "",
    val title: String = "",
    val summary: String = "",
    val actionItems: String = "",
    val audioPath: String? = null,
    val sourceType: String = SOURCE_TYPE_LIVE_MIC,
    val sourceChunkId: String? = null,
    val sourceDisplayName: String? = null,
    val sourceDurationMs: Long? = null,
    val summarySourceHash: String = "",
    val summaryGeneratedAt: Long? = null,
    val requestedSummaryEngine: String? = null,
    val summaryFallbackReason: String? = null,
    val summaryPolicyVersion: Int? = null,
    val summaryPromptVersion: Int? = null,
    val summaryModelVersion: String? = null,
    val summaryValidationStatus: String? = null,
    val requestedSummaryModelId: String? = null,
    val actualSummaryModelId: String? = null,
    val summaryRuntimeType: String? = null,
    val summaryGenerationProfile: String? = null,
    val summaryViolationCodes: String? = null,
    val summaryDurationMs: Long? = null,
    val summaryInputChars: Int? = null,
    val summaryOutputChars: Int? = null,
    val sttInputDurationMs: Long? = null,
    val sttProcessedThroughMs: Long? = null,
    val sttSegmentCount: Int? = null,
    val sttRecognizedSegmentCount: Int? = null,
    val sttRetryCount: Int? = null,
    val sttMeaningfulChars: Int? = null,
    val sttCharsPerSecond: Float? = null,
    val sttQualityStatus: String? = null,
    val sttSegmentDiagnostics: String? = null,
    val sttCoverageStatus: String? = null,
    val sttRecognitionQualityStatus: String? = null,
    val sttRecognitionDiagnostics: String? = null,
    val selectedSummaryRunId: String? = null,
    val activeProcessingJobId: String? = null,
    val summaryRootNodeId: String? = null,
    val processingVersion: Int? = null,
    val error: String? = null,
    val operationToken: String? = null,
    val failureStage: String? = null,
    val dataPolicy: String = DATA_POLICY_LOCAL_ONLY,
) {
    companion object {
        const val DATA_POLICY_LOCAL_ONLY = "LOCAL_ONLY"
        const val SOURCE_TYPE_LIVE_MIC = "LIVE_MIC"
        const val SOURCE_TYPE_MAIN_RECORDER_CHUNK = "MAIN_RECORDER_CHUNK"
    }
}

@Entity(
    tableName = "ondevice_summary_batches",
    foreignKeys = [
        ForeignKey(
            entity = OnDeviceSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["createdAt"]),
    ],
)
data class OnDeviceSummaryBatchEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val state: String,
    val sourceHash: String,
    val inputHash: String,
    val inputBuilderVersion: Int,
    val inputPayload: String,
    val requestedEngines: String,
    val selectedRunId: String? = null,
    val error: String? = null,
    val operationToken: String? = null,
    val dataPolicy: String = OnDeviceSessionEntity.DATA_POLICY_LOCAL_ONLY,
)

@Entity(
    tableName = "ondevice_summary_runs",
    foreignKeys = [
        ForeignKey(
            entity = OnDeviceSummaryBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["batchId"]),
        Index(value = ["sessionId"]),
        Index(value = ["createdAt"]),
        Index(value = ["state"]),
    ],
)
data class OnDeviceSummaryRunEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val sessionId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val orderIndex: Int,
    val requestedEngine: String,
    val attemptedEngine: String,
    val state: String,
    val failureStage: String? = null,
    val failureCode: String? = null,
    val violationCodes: String? = null,
    val title: String = "",
    val summary: String = "",
    val actionItems: String = "",
    val evidenceIds: String = "",
    val rawOutput: String? = null,
    val rawOutputLength: Int? = null,
    val rawOutputHash: String? = null,
    val rawOutputTruncated: Boolean = false,
    val requestedModelId: String? = null,
    val modelId: String? = null,
    val modelVersion: String? = null,
    val runtimeType: String? = null,
    val generationProfile: String? = null,
    val policyVersion: Int? = null,
    val promptVersion: Int? = null,
    val validationStatus: String? = null,
    val durationMs: Long? = null,
    val inputChars: Int? = null,
    val outputChars: Int? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val fallbackForRunId: String? = null,
    val operationToken: String? = null,
    val dataPolicy: String = OnDeviceSessionEntity.DATA_POLICY_LOCAL_ONLY,
)

@Entity(
    tableName = "ondevice_processing_jobs",
    foreignKeys = [
        ForeignKey(
            entity = OnDeviceSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["state"]),
        Index(value = ["updatedAt"]),
    ],
)
data class OnDeviceProcessingJobEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val state: String,
    val stage: String,
    val sourceFingerprint: String,
    val sourceDurationMs: Long,
    val sourceSizeBytes: Long,
    val sourceSnapshotPath: String,
    val pcmPath: String,
    val serviceToken: String? = null,
    val completedSttSegments: Int = 0,
    val totalSttSegments: Int = 0,
    val completedSummaryNodes: Int = 0,
    val totalSummaryNodes: Int = 0,
    val currentSummaryLevel: Int = 0,
    val rootNodeId: String? = null,
    val pauseRequested: Boolean = false,
    val cancelRequested: Boolean = false,
    val retryCount: Int = 0,
    val failureCode: String? = null,
    val error: String? = null,
    val checkpointVersion: Int = 1,
    val dataPolicy: String = OnDeviceSessionEntity.DATA_POLICY_LOCAL_ONLY,
)

@Entity(
    tableName = "ondevice_transcript_segments",
    foreignKeys = [
        ForeignKey(
            entity = OnDeviceProcessingJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["jobId"]),
        Index(value = ["sessionId"]),
        Index(value = ["jobId", "passType", "ordinal"], unique = true),
        Index(value = ["jobId", "startMs", "endMs"]),
    ],
)
data class OnDeviceTranscriptSegmentEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val sessionId: String,
    val passType: String,
    val ordinal: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val textHash: String,
    val sourceRangeHash: String,
    val meaningfulChars: Int,
    val state: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "ondevice_summary_nodes",
    foreignKeys = [
        ForeignKey(
            entity = OnDeviceProcessingJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["jobId"]),
        Index(value = ["sessionId"]),
        Index(value = ["jobId", "level", "ordinal", "inputHash"], unique = true),
        Index(value = ["jobId", "state"]),
    ],
)
data class OnDeviceSummaryNodeEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val sessionId: String,
    val level: Int,
    val ordinal: Int,
    val nodeType: String,
    val state: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val leafStartOrdinal: Int,
    val leafEndOrdinal: Int,
    val childNodeIds: String,
    val inputPayload: String,
    val inputHash: String,
    val sourceHash: String,
    val title: String = "",
    val summary: String = "",
    val evidenceChildIds: String = "",
    val outputHash: String = "",
    val attemptCount: Int = 0,
    val failureCode: String? = null,
    val violationCodes: String? = null,
    val modelVersion: String? = null,
    val runtimeType: String? = null,
    val generationProfile: String? = null,
    val durationMs: Long? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val dataPolicy: String = OnDeviceSessionEntity.DATA_POLICY_LOCAL_ONLY,
)
