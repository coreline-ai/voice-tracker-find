package com.thinktank.recorder.next.data.repository

import com.thinktank.recorder.next.data.local.ChunkState
import com.thinktank.recorder.next.data.local.ChunkEntity
import com.thinktank.recorder.next.data.local.RecordingDao
import com.thinktank.recorder.next.data.local.UploadAttemptEntity
import com.thinktank.recorder.next.data.remote.ApiException
import com.thinktank.recorder.next.data.remote.ReceiverApi
import com.thinktank.recorder.next.data.remote.UploadReceipt
import com.thinktank.recorder.next.data.settings.SettingsReader
import java.io.File
import kotlin.math.min
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncRunResult {
    data class Success(val uploaded: Int, val notes: Int) : SyncRunResult
    data class Retry(val reason: String) : SyncRunResult
    data class Failure(val reason: String) : SyncRunResult
}

/** A 2xx upload is trustworthy only when the V1 receipt identifies this file. */
internal fun UploadReceipt.matches(chunk: ChunkEntity, file: File): Boolean =
    uploadId == chunk.uploadId &&
        recordingId == chunk.sessionId &&
        chunkId == chunk.id &&
        filename == file.name &&
        size == file.length() &&
        sha256 == chunk.sha256 &&
        status in setOf("created", "already_exists")

@Singleton
class SyncRepository @Inject constructor(
    private val dao: RecordingDao,
    private val api: ReceiverApi,
    private val preferences: SettingsReader,
    private val notesRepository: NotesRepository,
) {
    suspend fun run(owner: String): SyncRunResult {
        val settings = preferences.current()
        if (!settings.isServerConfigured) return SyncRunResult.Failure("서버 설정이 필요합니다")
        var uploaded = 0
        while (true) {
            val now = System.currentTimeMillis()
            val chunk = dao.claimNextReady(owner, now, now + LEASE_MS) ?: break
            val file = File(chunk.path)
            if (!file.isFile || chunk.sha256.isNullOrBlank()) {
                dao.finishClaim(
                    id = chunk.id,
                    owner = owner,
                    state = ChunkState.FAILED,
                    attempts = chunk.attempts + 1,
                    error = "FILE_MISSING_OR_UNVERIFIED",
                )
                continue
            }
            dao.markUploading(chunk.id, owner)
            val attemptId = dao.insertAttempt(
                UploadAttemptEntity(
                    chunkId = chunk.id,
                    startedAt = now,
                    outcome = "STARTED",
                ),
            )
            try {
                val receipt = api.upload(
                    settings = settings,
                    file = file,
                    uploadId = chunk.uploadId,
                    recordingId = chunk.sessionId,
                    chunkId = chunk.id,
                    sha256 = chunk.sha256,
                )
                if (!receipt.matches(chunk, file)) {
                    dao.finishAttempt(
                        attemptId,
                        System.currentTimeMillis(),
                        "CONFLICT",
                        "SERVER_IDENTITY_MISMATCH",
                        receipt.requestId,
                    )
                    dao.finishClaim(
                        chunk.id,
                        owner,
                        ChunkState.CONFLICT,
                        chunk.attempts + 1,
                        error = "SERVER_RECEIPT_MISMATCH",
                    )
                    return SyncRunResult.Failure("서버 업로드 영수증이 로컬 파일과 일치하지 않습니다")
                }
                dao.finishClaim(
                    chunk.id,
                    owner,
                    ChunkState.UPLOADED,
                    chunk.attempts + 1,
                    serverHash = receipt.sha256,
                )
                dao.finishAttempt(
                    attemptId,
                    System.currentTimeMillis(),
                    "SUCCEEDED",
                    requestId = receipt.requestId,
                )
                uploaded += 1
            } catch (error: ApiException) {
                dao.finishAttempt(
                    attemptId,
                    System.currentTimeMillis(),
                    "FAILED",
                    error.code,
                    error.requestId,
                )
                val retryable = error.status == 408 || error.status == 429 || error.status >= 500
                if (retryable) {
                    val attempts = chunk.attempts + 1
                    dao.finishClaim(
                        chunk.id,
                        owner,
                        ChunkState.RETRY,
                        attempts,
                        nextRetryAt = System.currentTimeMillis() + backoff(attempts),
                        error = error.code,
                    )
                    return SyncRunResult.Retry(error.code)
                }
                val state = if (error.status == 409) ChunkState.CONFLICT else ChunkState.FAILED
                dao.finishClaim(
                    chunk.id,
                    owner,
                    state,
                    chunk.attempts + 1,
                    error = error.code,
                )
                if (error.status == 401) return SyncRunResult.Failure("인증 정보를 확인하세요")
            } catch (error: Exception) {
                dao.finishAttempt(
                    attemptId,
                    System.currentTimeMillis(),
                    "FAILED",
                    error::class.simpleName ?: "NETWORK_ERROR",
                )
                val attempts = chunk.attempts + 1
                dao.finishClaim(
                    chunk.id,
                    owner,
                    ChunkState.RETRY,
                    attempts,
                    nextRetryAt = System.currentTimeMillis() + backoff(attempts),
                    error = error::class.simpleName ?: "NETWORK_ERROR",
                )
                return SyncRunResult.Retry(error.message ?: "네트워크 오류")
            }
        }

        return when (val notes = notesRepository.syncAll()) {
            is NotesSyncResult.Success -> SyncRunResult.Success(uploaded, notes.count)
            is NotesSyncResult.Retryable -> SyncRunResult.Retry(notes.reason)
            is NotesSyncResult.Failed ->
                if (uploaded > 0) SyncRunResult.Success(uploaded, 0)
                else SyncRunResult.Failure(notes.reason)
        }
    }

    private fun backoff(attempt: Int): Long {
        val base = min(MAX_BACKOFF_MS, 30_000L * (1L shl min(attempt, 8)))
        return base + Random.nextLong(0, base / 5 + 1)
    }

    private companion object {
        const val LEASE_MS = 15 * 60 * 1000L
        const val MAX_BACKOFF_MS = 6 * 60 * 60 * 1000L
    }
}
