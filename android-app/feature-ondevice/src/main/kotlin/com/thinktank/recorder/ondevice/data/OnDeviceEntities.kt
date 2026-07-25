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
    val summarySourceHash: String = "",
    val summaryGeneratedAt: Long? = null,
    val error: String? = null,
    val operationToken: String? = null,
    val failureStage: String? = null,
    val dataPolicy: String = DATA_POLICY_LOCAL_ONLY,
) {
    companion object {
        const val DATA_POLICY_LOCAL_ONLY = "LOCAL_ONLY"
    }
}
