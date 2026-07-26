package com.thinktank.recorder.ondevice.data

import androidx.room.Entity
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
