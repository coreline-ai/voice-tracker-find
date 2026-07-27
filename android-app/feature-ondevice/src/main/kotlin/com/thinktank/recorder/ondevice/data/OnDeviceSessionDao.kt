package com.thinktank.recorder.ondevice.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OnDeviceSessionDao {
    @Query("SELECT * FROM ondevice_sessions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<OnDeviceSessionEntity>>

    @Query("SELECT * FROM ondevice_sessions WHERE id = :id")
    suspend fun get(id: String): OnDeviceSessionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: OnDeviceSessionEntity)

    @Update
    suspend fun update(session: OnDeviceSessionEntity)

    @Query("DELETE FROM ondevice_sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        """
        SELECT * FROM ondevice_sessions
        WHERE state IN (
            'STARTING',
            'LISTENING',
            'TRANSCRIBING',
            'SUMMARIZING',
            'CANCELLING',
            'DELETING'
        )
        """,
    )
    suspend fun interrupted(): List<OnDeviceSessionEntity>

    @Query(
        """
        UPDATE ondevice_sessions
        SET state = :targetState,
            updatedAt = :now,
            operationToken = NULL,
            failureStage = :failureStage,
            error = :error
        WHERE id = :id
          AND state = :expectedState
          AND (
              operationToken = :expectedToken
              OR (operationToken IS NULL AND :expectedToken IS NULL)
          )
        """,
    )
    suspend fun recover(
        id: String,
        expectedState: String,
        expectedToken: String?,
        targetState: String,
        now: Long,
        failureStage: String?,
        error: String?,
    ): Int

    @Query(
        """
        UPDATE ondevice_sessions
        SET audioPath = NULL,
            updatedAt = :now
        WHERE id = :id
        """,
    )
    suspend fun clearAudio(id: String, now: Long): Int

    @Query(
        """
        UPDATE ondevice_sessions
        SET state = :targetState,
            updatedAt = :now,
            operationToken = :token,
            failureStage = NULL,
            error = NULL
        WHERE id = :id
          AND state IN (:allowedStates)
          AND operationToken IS NULL
        """,
    )
    suspend fun startOperation(
        id: String,
        allowedStates: List<String>,
        targetState: String,
        token: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE ondevice_sessions
        SET state = :targetState,
            updatedAt = :now,
            failureStage = NULL,
            error = NULL
        WHERE id = :id
          AND operationToken = :token
          AND state IN (:allowedStates)
        """,
    )
    suspend fun advanceOperation(
        id: String,
        token: String,
        allowedStates: List<String>,
        targetState: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE ondevice_sessions
        SET state = :targetState,
            updatedAt = :now,
            operationToken = NULL,
            failureStage = :failureStage,
            error = :error
        WHERE id = :id
          AND operationToken = :token
        """,
    )
    suspend fun finishOperation(
        id: String,
        token: String,
        targetState: String,
        now: Long,
        failureStage: String?,
        error: String?,
    ): Int

    @Query(
        """
        UPDATE ondevice_sessions
        SET audioPath = :audioPath,
            updatedAt = :now
        WHERE id = :id
          AND operationToken = :token
        """,
    )
    suspend fun attachAudioForOperation(
        id: String,
        token: String,
        audioPath: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE ondevice_sessions
        SET audioPath = NULL,
            state = :targetState,
            updatedAt = :now,
            operationToken = NULL,
            failureStage = :failureStage,
            error = :error
        WHERE id = :id
          AND operationToken = :token
        """,
    )
    suspend fun finishCaptureWithoutAudio(
        id: String,
        token: String,
        targetState: String,
        now: Long,
        failureStage: String?,
        error: String?,
    ): Int

    @Query(
        """
        UPDATE ondevice_sessions
        SET transcript = :transcript,
            state = 'TRANSCRIPT_READY',
            updatedAt = :now,
            operationToken = NULL,
            failureStage = NULL,
            error = NULL
        WHERE id = :id
          AND operationToken = :token
        """,
    )
    suspend fun saveTranscriptForOperation(
        id: String,
        token: String,
        transcript: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE ondevice_sessions
        SET state = 'COMPLETE',
            title = :title,
            summary = :summary,
            actionItems = :actionItems,
            summaryEngine = :summaryEngine,
            requestedSummaryEngine = :requestedSummaryEngine,
            summaryFallbackReason = :fallbackReason,
            summaryPolicyVersion = :policyVersion,
            summaryPromptVersion = :promptVersion,
            summaryModelVersion = :modelVersion,
            summaryValidationStatus = :validationStatus,
            requestedSummaryModelId = :requestedModelId,
            actualSummaryModelId = :actualModelId,
            summaryRuntimeType = :runtimeType,
            summaryGenerationProfile = :generationProfile,
            summaryViolationCodes = :violationCodes,
            summaryDurationMs = :durationMs,
            summaryInputChars = :inputChars,
            summaryOutputChars = :outputChars,
            summarySourceHash = :sourceHash,
            summaryGeneratedAt = :generatedAt,
            updatedAt = :now,
            operationToken = NULL,
            failureStage = NULL,
            error = NULL
        WHERE id = :id
          AND operationToken = :token
        """,
    )
    suspend fun saveSummaryForOperation(
        id: String,
        token: String,
        title: String,
        summary: String,
        actionItems: String,
        summaryEngine: String,
        requestedSummaryEngine: String,
        fallbackReason: String?,
        policyVersion: Int?,
        promptVersion: Int?,
        modelVersion: String?,
        validationStatus: String?,
        requestedModelId: String?,
        actualModelId: String?,
        runtimeType: String?,
        generationProfile: String?,
        violationCodes: String?,
        durationMs: Long?,
        inputChars: Int?,
        outputChars: Int?,
        sourceHash: String,
        generatedAt: Long,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE ondevice_sessions
        SET state = 'COMPLETE',
            updatedAt = :now,
            failureStage = NULL,
            error = NULL
        WHERE id = :id
          AND operationToken IS NULL
          AND state IN ('TRANSCRIPT_READY', 'FAILED_RECOVERABLE')
        """,
    )
    suspend fun completeWithoutSummary(id: String, now: Long): Int

    @Query(
        """
        UPDATE ondevice_sessions
        SET state = 'DELETING',
            updatedAt = :now,
            failureStage = NULL,
            error = NULL
        WHERE id = :id
          AND operationToken IS NULL
          AND state NOT IN (
              'STARTING',
              'LISTENING',
              'TRANSCRIBING',
              'SUMMARIZING',
              'CANCELLING',
              'DELETING'
          )
        """,
    )
    suspend fun markDeleting(id: String, now: Long): Int
}
