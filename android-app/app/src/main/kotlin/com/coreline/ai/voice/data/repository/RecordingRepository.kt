package com.coreline.ai.voice.data.repository

import com.coreline.ai.voice.data.local.ChunkEntity
import com.coreline.ai.voice.data.local.ChunkState
import com.coreline.ai.voice.data.local.RecordingDao
import com.coreline.ai.voice.data.local.RecordingSessionEntity
import com.coreline.ai.voice.recording.RecordingFileManager
import com.coreline.ai.voice.recording.RecordingRuntime
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface QueueActionResult {
    data object Completed : QueueActionResult
    data class Rejected(val message: String) : QueueActionResult
}

@Singleton
class RecordingRepository @Inject constructor(
    private val dao: RecordingDao,
    runtime: RecordingRuntime,
    private val files: RecordingFileManager,
) {
    val latestSession: Flow<RecordingSessionEntity?> = dao.observeLatestSession()
    val latestChunk: Flow<ChunkEntity?> = dao.observeLatestChunk()
    val recentChunks: Flow<List<ChunkEntity>> = dao.observeRecentChunks(MAX_RECENT_CHUNKS)
    val onDeviceAnalysisCandidates: Flow<List<ChunkEntity>> =
        dao.observeOnDeviceAnalysisCandidates(MAX_ONDEVICE_ANALYSIS_CANDIDATES)
    val pendingUploads: Flow<Int> = dao.observePendingCount()
    val attentionUploads: Flow<Int> = dao.observeAttentionCount()
    val syncQueue: Flow<List<ChunkEntity>> = dao.observeSyncQueue(MAX_SYNC_QUEUE_ITEMS)
    val amplitude: StateFlow<Float> = runtime.amplitude

    suspend fun retryUpload(id: String): QueueActionResult {
        val chunk = dao.chunk(id) ?: return QueueActionResult.Rejected("업로드 항목을 찾을 수 없습니다.")
        if (chunk.state !in setOf(ChunkState.RETRY, ChunkState.FAILED)) {
            return QueueActionResult.Rejected("현재 상태에서는 재시도할 수 없습니다.")
        }
        if (!files.isVerifiedFinalizedFile(chunk.path, chunk.sha256)) {
            return QueueActionResult.Rejected("원본 파일 또는 무결성 정보를 확인할 수 없어 재시도하지 않았습니다.")
        }
        return if (dao.requeueForManualRetry(id) == 1) {
            QueueActionResult.Completed
        } else {
            QueueActionResult.Rejected("업로드 상태가 변경되어 재시도하지 않았습니다.")
        }
    }

    suspend fun deleteStoredChunk(id: String): QueueActionResult {
        val chunk = dao.chunk(id) ?: return QueueActionResult.Rejected("정리할 항목을 찾을 수 없습니다.")
        if (dao.markDeleting(id) != 1) {
            return QueueActionResult.Rejected("업로드 중이거나 녹음 중인 항목은 정리할 수 없습니다.")
        }
        if (!files.isManagedPath(chunk.path)) {
            dao.failDeletingChunk(id, "MANAGED_PATH_REQUIRED")
            return QueueActionResult.Rejected("앱 보관함 밖의 파일은 정리하지 않았습니다.")
        }
        return if (files.deleteManagedFinalizedFile(chunk.path) && dao.deleteDeletingChunk(id) == 1) {
            QueueActionResult.Completed
        } else {
            dao.failDeletingChunk(id, "LOCAL_DELETE_FAILED")
            QueueActionResult.Rejected("원본 파일을 정리하지 못했습니다. 다시 시도하세요.")
        }
    }

    suspend fun recoverInterruptedDeletes() {
        dao.deletingChunks().forEach { chunk ->
            when {
                !files.isManagedPath(chunk.path) ->
                    dao.failDeletingChunk(chunk.id, "MANAGED_PATH_REQUIRED")
                !java.io.File(chunk.path).exists() -> dao.deleteDeletingChunk(chunk.id)
                else -> dao.failDeletingChunk(chunk.id, "DELETE_INTERRUPTED")
            }
        }
    }

    /**
     * Produces an analysis-only copy after checking the same finalized-file integrity contract
     * used by retry upload. The caller owns and removes [destination].
     */
    suspend fun copyVerifiedChunkForOnDeviceAnalysis(id: String, destination: File): ChunkEntity {
        val chunk = dao.chunk(id) ?: error("선택한 녹음 항목을 찾을 수 없습니다.")
        check(chunk.state in ONDEVICE_ANALYSIS_STATES) {
            "녹음 중이거나 정리 중인 원본은 분석할 수 없습니다."
        }
        check((chunk.durationMs ?: 0L) > 0L && (chunk.sizeBytes ?: 0L) > 0L) {
            "분석에 필요한 원본 길이 또는 크기 정보가 없습니다."
        }
        check(!chunk.sha256.isNullOrBlank()) { "원본 무결성 정보가 없습니다." }
        check(files.copyVerifiedFinalizedFile(chunk.path, chunk.sha256, destination)) {
            "원본 파일 또는 무결성 정보를 확인하지 못했습니다."
        }
        return chunk
    }

    private companion object {
        const val MAX_RECENT_CHUNKS = 5
        const val MAX_ONDEVICE_ANALYSIS_CANDIDATES = 50
        const val MAX_SYNC_QUEUE_ITEMS = 20
        val ONDEVICE_ANALYSIS_STATES = setOf(
            ChunkState.READY,
            ChunkState.UPLOADED,
            ChunkState.RETRY,
            ChunkState.FAILED,
            ChunkState.CONFLICT,
        )
    }
}
