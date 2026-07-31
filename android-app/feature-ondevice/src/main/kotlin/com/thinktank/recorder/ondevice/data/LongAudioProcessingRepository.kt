package com.thinktank.recorder.ondevice.data

import androidx.room.withTransaction
import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.api.MainRecordingSource
import com.thinktank.recorder.ondevice.api.OnDeviceSessionState
import com.thinktank.recorder.ondevice.api.SttEngineType
import com.thinktank.recorder.ondevice.processing.LongAudioJobState
import com.thinktank.recorder.ondevice.processing.LongAudioStage
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class LongAudioProcessingRepository(
    private val database: OnDeviceDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.longProcessingDao()
    private val sessionDao = database.sessionDao()

    val activeJob: Flow<OnDeviceProcessingJobEntity?> = dao.observeLatestActive()

    suspend fun createJob(
        source: MainRecordingSource,
        sourceSnapshot: File,
        pcmFile: File,
        jobId: String = UUID.randomUUID().toString(),
        sessionId: String = UUID.randomUUID().toString(),
    ): OnDeviceProcessingJobEntity {
        require(source.durationMs in 1..MAX_SOURCE_DURATION_MS) {
            "2시간을 초과하는 녹음은 현재 장시간 처리 범위에 포함되지 않습니다."
        }
        require(source.sha256.isNotBlank()) { "원본 녹음 무결성 정보가 없습니다." }
        val now = clock()
        val operationToken = UUID.randomUUID().toString()
        val estimatedSegments =
            ((source.durationMs + ESTIMATED_SEGMENT_MS - 1) / ESTIMATED_SEGMENT_MS).toInt()
        val job = OnDeviceProcessingJobEntity(
            id = jobId,
            sessionId = sessionId,
            createdAt = now,
            updatedAt = now,
            state = LongAudioJobState.QUEUED.name,
            stage = LongAudioStage.NORMALIZING.name,
            sourceFingerprint = source.sha256,
            sourceDurationMs = source.durationMs,
            sourceSizeBytes = source.sizeBytes,
            sourceSnapshotPath = sourceSnapshot.absolutePath,
            pcmPath = pcmFile.absolutePath,
            totalSttSegments = estimatedSegments.coerceAtLeast(1),
        )
        database.withTransaction {
            sessionDao.insert(
                OnDeviceSessionEntity(
                    id = sessionId,
                    createdAt = now,
                    updatedAt = now,
                    state = OnDeviceSessionState.STARTING.name,
                    sttEngine = SttEngineType.SENSEVOICE_LOCAL_FILE.name,
                    summaryEngine = SUMMARY_ENGINE,
                    requestedSummaryEngine = SUMMARY_ENGINE,
                    sourceType = OnDeviceSessionEntity.SOURCE_TYPE_MAIN_RECORDER_CHUNK,
                    sourceChunkId = source.id,
                    sourceDisplayName =
                        "${source.extension.uppercase()} · ${source.durationMs / 1_000}초",
                    sourceDurationMs = source.durationMs,
                    activeProcessingJobId = jobId,
                    processingVersion = CHECKPOINT_VERSION,
                    operationToken = operationToken,
                ),
            )
            dao.insertJob(job)
        }
        return job
    }

    suspend fun createSummaryOnlyJob(
        sessionId: String,
        jobId: String = UUID.randomUUID().toString(),
    ): OnDeviceProcessingJobEntity = database.withTransaction {
        val session = requireNotNull(sessionDao.get(sessionId)) {
            "다시 요약할 로컬 기록을 찾을 수 없습니다."
        }
        require(session.transcript.isNotBlank()) { "다시 요약할 전사 원문이 없습니다." }
        require(session.activeProcessingJobId == null) { "이 기록의 장시간 작업이 이미 진행 중입니다." }
        val now = clock()
        val job = OnDeviceProcessingJobEntity(
            id = jobId,
            sessionId = sessionId,
            createdAt = now,
            updatedAt = now,
            state = LongAudioJobState.QUEUED.name,
            stage = LongAudioStage.PLANNING_SUMMARY.name,
            sourceFingerprint = sha256(session.transcript),
            sourceDurationMs = session.sourceDurationMs
                ?: session.sttInputDurationMs
                ?: (session.transcript.length * ESTIMATED_TRANSCRIPT_CHAR_MS)
                    .coerceIn(MIN_SYNTHETIC_DURATION_MS, MAX_SOURCE_DURATION_MS),
            sourceSizeBytes = 0L,
            sourceSnapshotPath = "",
            pcmPath = "",
            completedSttSegments = 0,
            totalSttSegments = 0,
        )
        dao.insertJob(job)
        check(
            sessionDao.attachSummaryOnlyJob(
                id = sessionId,
                jobId = jobId,
                processingVersion = CHECKPOINT_VERSION,
                now = now,
            ) == 1,
        ) { "현재 기록은 다시 요약할 수 없는 상태입니다." }
        job
    }

    suspend fun getJob(id: String): OnDeviceProcessingJobEntity? = dao.getJob(id)

    suspend fun claim(id: String, serviceToken: String): OnDeviceProcessingJobEntity? {
        if (dao.claim(id, serviceToken, clock()) != 1) return null
        return dao.getJob(id)
    }

    suspend fun recoverInterrupted(): Int = database.withTransaction {
        val interrupted = dao.interruptedJobs()
        if (interrupted.isEmpty()) return@withTransaction 0
        val now = clock()
        val recovered = dao.recoverInterrupted(now)
        interrupted.forEach { job ->
            sessionDao.finishLongProcessingSession(
                id = job.sessionId,
                state = OnDeviceSessionState.FAILED_RECOVERABLE.name,
                failureStage = when (job.stage) {
                    LongAudioStage.NORMALIZING.name -> "NORMALIZE"
                    LongAudioStage.TRANSCRIBING.name -> "TRANSCRIBE"
                    else -> "SUMMARIZE"
                },
                error = "앱 또는 처리 서비스가 종료되었습니다. 마지막 완료 지점부터 다시 시작할 수 있습니다.",
                clearJob = false,
                now = now,
            )
        }
        recovered
    }

    suspend fun requestPause(id: String): Boolean = dao.requestPause(id, clock()) == 1

    suspend fun requestCancel(id: String): Boolean = dao.requestCancel(id, clock()) == 1

    suspend fun updateProgress(
        job: OnDeviceProcessingJobEntity,
        serviceToken: String,
        stage: LongAudioStage,
        completedSttSegments: Int = job.completedSttSegments,
        totalSttSegments: Int = job.totalSttSegments,
        completedSummaryNodes: Int = job.completedSummaryNodes,
        totalSummaryNodes: Int = job.totalSummaryNodes,
        currentSummaryLevel: Int = job.currentSummaryLevel,
    ): Boolean = dao.updateProgress(
        id = job.id,
        serviceToken = serviceToken,
        stage = stage.name,
        completedSttSegments = completedSttSegments,
        totalSttSegments = totalSttSegments,
        completedSummaryNodes = completedSummaryNodes,
        totalSummaryNodes = totalSummaryNodes,
        currentSummaryLevel = currentSummaryLevel,
        now = clock(),
    ) == 1

    suspend fun checkpointSegment(
        job: OnDeviceProcessingJobEntity,
        passType: String,
        ordinal: Int,
        startMs: Long,
        endMs: Long,
        text: String,
    ): Boolean {
        val normalized = text.trim()
        val now = clock()
        val sourceRangeHash = sha256("$startMs:$endMs")
        return dao.insertSegment(
            OnDeviceTranscriptSegmentEntity(
                id = sha256("${job.id}:$passType:$ordinal:$sourceRangeHash"),
                jobId = job.id,
                sessionId = job.sessionId,
                passType = passType,
                ordinal = ordinal,
                startMs = startMs,
                endMs = endMs,
                text = normalized,
                textHash = sha256(normalized),
                sourceRangeHash = sourceRangeHash,
                meaningfulChars = normalized.count(Char::isLetterOrDigit),
                state = if (normalized.isBlank()) "EMPTY" else "PASSED",
                createdAt = now,
                updatedAt = now,
            ),
        ) != -1L
    }

    suspend fun passedSegments(jobId: String): List<OnDeviceTranscriptSegmentEntity> =
        dao.passedSegments(jobId)

    suspend fun primarySegments(jobId: String): List<OnDeviceTranscriptSegmentEntity> =
        dao.primarySegments(jobId)

    suspend fun latestPassedSttSegmentsForSession(
        sessionId: String,
        excludingJobId: String,
    ): List<OnDeviceTranscriptSegmentEntity> =
        dao.latestPassedSttSegmentsForSession(sessionId, excludingJobId)

    suspend fun insertNode(node: OnDeviceSummaryNodeEntity): Boolean =
        dao.insertNode(node) != -1L

    suspend fun updateNode(node: OnDeviceSummaryNodeEntity) = dao.updateNode(node)

    suspend fun getNode(id: String): OnDeviceSummaryNodeEntity? = dao.getNode(id)

    suspend fun nodesAtLevel(jobId: String, level: Int): List<OnDeviceSummaryNodeEntity> =
        dao.nodesAtLevel(jobId, level)

    suspend fun allNodes(jobId: String): List<OnDeviceSummaryNodeEntity> = dao.allNodes(jobId)

    suspend fun finish(
        jobId: String,
        serviceToken: String,
        state: LongAudioJobState,
        stage: LongAudioStage,
        rootNodeId: String? = null,
        failureCode: String? = null,
        error: String? = null,
    ): Boolean = dao.finish(
        id = jobId,
        serviceToken = serviceToken,
        state = state.name,
        stage = stage.name,
        rootNodeId = rootNodeId,
        failureCode = failureCode,
        error = error,
        now = clock(),
    ) == 1

    suspend fun finalizeSuccess(
        job: OnDeviceProcessingJobEntity,
        serviceToken: String,
        summaryToken: String,
        rootNodeId: String,
        processingVersion: Int,
        result: LocalSummary,
    ): Boolean = database.withTransaction {
        val now = clock()
        val projected = sessionDao.saveHierarchicalSummaryForOperation(
            id = job.sessionId,
            jobId = job.id,
            token = summaryToken,
            rootNodeId = rootNodeId,
            processingVersion = processingVersion,
            title = result.title,
            summary = result.bullets.joinToString("\n"),
            actionItems = result.actionItems.joinToString("\n"),
            modelVersion = result.modelVersion,
            validationStatus = result.validationStatus,
            requestedModelId = result.requestedModelId,
            actualModelId = result.actualModelId,
            runtimeType = result.runtimeType,
            generationProfile = result.generationProfile,
            durationMs = result.durationMs,
            inputChars = result.inputChars,
            outputChars = result.outputChars,
            sourceHash = result.sourceHash,
            generatedAt = now,
            now = now,
        ) == 1
        if (!projected) return@withTransaction false
        check(
            dao.finish(
                id = job.id,
                serviceToken = serviceToken,
                state = LongAudioJobState.COMPLETE.name,
                stage = LongAudioStage.COMPLETE.name,
                rootNodeId = rootNodeId,
                failureCode = null,
                error = null,
                now = now,
            ) == 1,
        ) { "최종 job 상태를 같은 transaction에서 저장하지 못했습니다." }
        true
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SUMMARY_ENGINE = "GEMMA_LOCAL"
        const val CHECKPOINT_VERSION = 1
        const val ESTIMATED_SEGMENT_MS = 28_000L
        const val MAX_SOURCE_DURATION_MS = 2L * 60L * 60L * 1_000L
        const val ESTIMATED_TRANSCRIPT_CHAR_MS = 200L
        const val MIN_SYNTHETIC_DURATION_MS = 1_000L
    }
}
