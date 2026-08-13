package com.coreline.ai.voice.ondevice.processing

import android.content.Context
import android.os.StatFs
import com.coreline.ai.voice.ondevice.api.LocalSummary
import com.coreline.ai.voice.ondevice.api.OnDeviceFailureStage
import com.coreline.ai.voice.ondevice.api.OnDeviceSessionState
import com.coreline.ai.voice.ondevice.api.SttSegmentDiagnostic
import com.coreline.ai.voice.ondevice.api.TranscriptSegment
import com.coreline.ai.voice.ondevice.audio.AndroidPcmNormalizer
import com.coreline.ai.voice.ondevice.data.LongAudioProcessingRepository
import com.coreline.ai.voice.ondevice.data.OnDeviceDatabase
import com.coreline.ai.voice.ondevice.data.OnDeviceProcessingJobEntity
import com.coreline.ai.voice.ondevice.data.OnDeviceRepository
import com.coreline.ai.voice.ondevice.data.OnDeviceSummaryNodeEntity
import com.coreline.ai.voice.ondevice.modelpack.ModelStore
import com.coreline.ai.voice.ondevice.recording.LocalAudioFileManager
import com.coreline.ai.voice.ondevice.stt.SenseVoiceFileSpeechEngine
import com.coreline.ai.voice.ondevice.stt.SttResumeState
import com.coreline.ai.voice.ondevice.summary.GemmaSummaryEngine
import com.coreline.ai.voice.ondevice.summary.HierarchicalSummaryGrounding
import com.coreline.ai.voice.ondevice.summary.HierarchicalSummaryProjection
import com.coreline.ai.voice.ondevice.summary.HierarchicalSummaryPlanner
import com.coreline.ai.voice.ondevice.summary.HierarchyCoverageValidator
import com.coreline.ai.voice.ondevice.summary.SummaryEvidenceCandidate
import com.coreline.ai.voice.ondevice.summary.SummaryNodeReference
import com.coreline.ai.voice.ondevice.summary.SummaryNodePlan
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class LongAudioProcessingRunner(
    context: Context,
    private val onProgress: suspend (OnDeviceProcessingJobEntity) -> Unit = {},
) {
    private val applicationContext = context.applicationContext
    private val database = OnDeviceDatabase.get(applicationContext)
    private val jobs = LongAudioProcessingRepository(database)
    private val sessions = OnDeviceRepository(
        dao = database.sessionDao(),
        audioFiles = LocalAudioFileManager(applicationContext),
    )
    private val normalizer = AndroidPcmNormalizer()
    private val speech = SenseVoiceFileSpeechEngine(applicationContext, ModelStore(applicationContext))
    private val summary = GemmaSummaryEngine(applicationContext, ModelStore(applicationContext))
    private val planner = HierarchicalSummaryPlanner()

    suspend fun run(jobId: String, serviceToken: String): OnDeviceProcessingJobEntity {
        var job = jobs.claim(jobId, serviceToken)
            ?: error("이미 실행 중이거나 완료된 장시간 작업입니다.")
        onProgress(job)
        val sessionBefore = requireNotNull(sessions.get(job.sessionId)) {
            "장시간 처리 session을 찾을 수 없습니다."
        }
        val summaryOnly = job.sourceSnapshotPath.isBlank()
        val snapshot = File(job.sourceSnapshotPath)
        val pcm = File(job.pcmPath)
        if (summaryOnly) {
            require(sessionBefore.transcript.isNotBlank()) {
                "계층형 재요약에 사용할 전사 원문이 없습니다."
            }
        } else {
            if (!snapshot.isFile || snapshot.length() != job.sourceSizeBytes) {
                throw LongAudioProcessingException(
                    code = "SOURCE_SNAPSHOT_INVALID",
                    message = "원본 snapshot이 없거나 크기가 변경되었습니다.",
                    stage = OnDeviceFailureStage.CAPTURE,
                )
            }
            checkStorage(job, pcm)
        }
        if (sessionBefore.transcript.isBlank()) {
            check(!summaryOnly) { "전사 원문 없는 요약 전용 작업은 실행할 수 없습니다." }
            val sttToken = prepareSttSession(sessionBefore, job)
            if (!pcm.isFile || pcm.length() == 0L) {
                update(job, serviceToken, LongAudioStage.NORMALIZING)
                normalizer.normalize(snapshot, pcm)
            }
            ensureRunnable(job.id)
            update(job, serviceToken, LongAudioStage.TRANSCRIBING)

            val existing = jobs.primarySegments(job.id)
            val resume = SttResumeState(
                processedThroughMs = existing.maxOfOrNull { it.endMs } ?: 0L,
                segments = existing.filter { it.text.isNotBlank() }.map {
                    TranscriptSegment(startMs = it.startMs, endMs = it.endMs, text = it.text)
                },
                diagnostics = existing.map {
                    SttSegmentDiagnostic(
                        startMs = it.startMs,
                        endMs = it.endMs,
                        meaningfulChars = it.meaningfulChars,
                    )
                },
            )
            val result = speech.transcribe(
                pcmFile = pcm,
                resumeState = resume,
                allowFullRetry = false,
                onSegmentCompleted = { checkpoint ->
                    jobs.checkpointSegment(
                        job = job,
                        passType = checkpoint.passType,
                        ordinal = checkpoint.ordinal,
                        startMs = checkpoint.startMs,
                        endMs = checkpoint.endMs,
                        text = checkpoint.text,
                    )
                    val latest = requireNotNull(jobs.getJob(job.id))
                    val completed = jobs.primarySegments(job.id).size
                    jobs.updateProgress(
                        job = latest,
                        serviceToken = serviceToken,
                        stage = LongAudioStage.TRANSCRIBING,
                        completedSttSegments = completed,
                        totalSttSegments = maxOf(latest.totalSttSegments, completed),
                    )
                    ensureRunnable(job.id)
                    onProgress(requireNotNull(jobs.getJob(job.id)))
                },
            )
            val diagnostics = requireNotNull(result.diagnostics) {
                "장시간 STT 처리 범위 진단이 없습니다."
            }
            if (!diagnostics.passed) {
                throw LongAudioProcessingException(
                    code = "STT_QUALITY_INSUFFICIENT",
                    message = "장시간 녹음을 끝까지 신뢰할 수 있게 전사하지 못했습니다.",
                    stage = OnDeviceFailureStage.TRANSCRIBE,
                )
            }
            check(sessions.saveTranscript(job.sessionId, sttToken, result)) {
                "만료된 장시간 STT 결과는 저장하지 않았습니다."
            }
        }

        ensureRunnable(job.id)
        job = requireNotNull(jobs.getJob(job.id))
        update(job, serviceToken, LongAudioStage.PLANNING_SUMMARY)
        var sourceSegments = jobs.passedSegments(job.id).filter { it.text.isNotBlank() }
        if (sourceSegments.isEmpty() && summaryOnly) {
            sourceSegments = jobs.latestPassedSttSegmentsForSession(
                sessionId = job.sessionId,
                excludingJobId = job.id,
            ).filter { it.text.isNotBlank() }
        }
        if (sourceSegments.isEmpty() && summaryOnly) {
            jobs.checkpointSegment(
                job = job,
                passType = "SUMMARY_SOURCE",
                ordinal = 0,
                startMs = 0L,
                endMs = job.sourceDurationMs.coerceAtLeast(1L),
                text = sessionBefore.transcript,
            )
            sourceSegments = jobs.passedSegments(job.id).filter { it.text.isNotBlank() }
        }
        require(sourceSegments.isNotEmpty()) { "계층형 요약에 사용할 STT 세그먼트가 없습니다." }
        var level = 0
        val leafPlans = planner.planLeafNodes(job.id, sourceSegments)
        if (jobs.nodesAtLevel(job.id, level).isEmpty()) {
            leafPlans.forEach { plan ->
                jobs.insertNode(planner.toEntity(job.id, job.sessionId, plan, System.currentTimeMillis()))
            }
        }
        val estimatedNodeCount =
            (jobs.nodesAtLevel(job.id, 0).size * 2 + EXTRA_REDUCTION_HEADROOM).coerceAtLeast(1)
        var root: OnDeviceSummaryNodeEntity? = null
        summary.withBatch(estimatedNodeCount) { summarize ->
            while (root == null) {
                ensureRunnable(job.id)
                var nodes = jobs.nodesAtLevel(job.id, level)
                require(nodes.isNotEmpty()) { "요약 level $level 노드가 없습니다." }
                val latestForCount = requireNotNull(jobs.getJob(job.id))
                jobs.updateProgress(
                    job = latestForCount,
                    serviceToken = serviceToken,
                    stage = if (level == 0) {
                        LongAudioStage.SUMMARIZING_LEAVES
                    } else {
                        LongAudioStage.REDUCING
                    },
                    completedSummaryNodes = jobs.allNodes(job.id).count { it.state == "PASSED" },
                    totalSummaryNodes = jobs.allNodes(job.id).size,
                    currentSummaryLevel = level,
                )
                val lowerLevelNodes = if (level == 0) {
                    emptyList()
                } else {
                    jobs.nodesAtLevel(job.id, level - 1)
                }
                nodes.filter { it.state != "PASSED" }.forEach { node ->
                    ensureRunnable(job.id)
                    val startedAt = System.currentTimeMillis()
                    jobs.updateNode(
                        node.copy(
                            state = "RUNNING",
                            attemptCount = node.attemptCount + 1,
                            startedAt = startedAt,
                            updatedAt = startedAt,
                        ),
                    )
                    val generated = try {
                        summarize(node.inputPayload)
                    } catch (error: Throwable) {
                        val now = System.currentTimeMillis()
                        jobs.updateNode(
                            node.copy(
                                state = "FAILED_RECOVERABLE",
                                attemptCount = node.attemptCount + 1,
                                failureCode = "GEMMA_NODE_FAILED",
                                completedAt = now,
                                updatedAt = now,
                            ),
                        )
                        throw error
                    }
                    val now = System.currentTimeMillis()
                    val generatedText = generated.bullets.joinToString("\n").trim()
                    val grounding = HierarchicalSummaryGrounding.evaluate(
                        summary = generatedText,
                        candidates = evidenceCandidates(
                            node = node,
                            sourceSegments = sourceSegments,
                            lowerLevelNodes = lowerLevelNodes,
                        ),
                    )
                    if (!grounding.passed) {
                        jobs.updateNode(
                            node.copy(
                                state = "QUALITY_REJECTED",
                                attemptCount = node.attemptCount + 1,
                                title = generated.title,
                                summary = generatedText,
                                evidenceChildIds = grounding.evidenceIds.joinToString(
                                    HierarchicalSummaryPlanner.CHILD_SEPARATOR,
                                ),
                                outputHash = sha256(generatedText),
                                failureCode = "SUMMARY_NODE_UNGROUNDED",
                                violationCodes = grounding.violationCodes.joinToString(","),
                                modelVersion = generated.modelVersion,
                                runtimeType = generated.runtimeType,
                                generationProfile = generated.generationProfile,
                                durationMs = generated.durationMs,
                                startedAt = startedAt,
                                completedAt = now,
                                updatedAt = now,
                            ),
                        )
                        throw LongAudioProcessingException(
                            code = "SUMMARY_NODE_UNGROUNDED",
                            message = "하위 원문에 근거하지 않은 요약은 최종 결과로 사용하지 않습니다.",
                            stage = OnDeviceFailureStage.SUMMARIZE,
                        )
                    }
                    jobs.updateNode(
                        node.copy(
                            state = "PASSED",
                            attemptCount = node.attemptCount + 1,
                            title = generated.title,
                            summary = generatedText,
                            evidenceChildIds = grounding.evidenceIds.joinToString(
                                HierarchicalSummaryPlanner.CHILD_SEPARATOR,
                            ),
                            outputHash = sha256(generatedText),
                            failureCode = null,
                            violationCodes = null,
                            modelVersion = generated.modelVersion,
                            runtimeType = generated.runtimeType,
                            generationProfile = generated.generationProfile,
                            durationMs = generated.durationMs,
                            startedAt = startedAt,
                            completedAt = now,
                            updatedAt = now,
                        ),
                    )
                    val latest = requireNotNull(jobs.getJob(job.id))
                    jobs.updateProgress(
                        job = latest,
                        serviceToken = serviceToken,
                        stage = if (level == 0) {
                            LongAudioStage.SUMMARIZING_LEAVES
                        } else {
                            LongAudioStage.REDUCING
                        },
                        completedSummaryNodes = jobs.allNodes(job.id).count { it.state == "PASSED" },
                        totalSummaryNodes = jobs.allNodes(job.id).size,
                        currentSummaryLevel = level,
                    )
                    onProgress(requireNotNull(jobs.getJob(job.id)))
                }
                nodes = jobs.nodesAtLevel(job.id, level)
                check(nodes.all { it.state == "PASSED" }) {
                    "통과하지 못한 요약 노드가 있어 상위 요약을 만들지 않습니다."
                }
                if (nodes.size == 1) {
                    root = nodes.single()
                } else {
                    level += 1
                    if (jobs.nodesAtLevel(job.id, level).isEmpty()) {
                        val parents = planner.planParentLevel(
                            jobId = job.id,
                            level = level,
                            children = nodes.map { it.toReference() },
                        )
                        parents.forEach { plan ->
                            jobs.insertNode(
                                planner.toEntity(
                                    jobId = job.id,
                                    sessionId = job.sessionId,
                                    plan = plan,
                                    now = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                }
            }
        }

        update(requireNotNull(jobs.getJob(job.id)), serviceToken, LongAudioStage.VALIDATING)
        val allNodes = jobs.allNodes(job.id)
        val expectedLeaves = leafPlans.flatMap(SummaryNodePlan::childIds)
        val violations = HierarchyCoverageValidator.validate(expectedLeaves, allNodes)
        check(violations.isEmpty()) {
            "계층형 요약 범위 검증 실패: ${violations.joinToString()}"
        }
        val finalRoot = requireNotNull(root)
        val currentSession = requireNotNull(sessions.get(job.sessionId))
        val sourceUnchanged = if (summaryOnly) {
            sha256(currentSession.transcript) == job.sourceFingerprint
        } else {
            snapshot.isFile &&
                snapshot.length() == job.sourceSizeBytes &&
                sha256(snapshot) == job.sourceFingerprint
        }
        if (!sourceUnchanged) {
            throw LongAudioProcessingException(
                code = "SOURCE_FINGERPRINT_CHANGED",
                message = "처리 중 원본이 변경되어 최종 결과를 적용하지 않았습니다.",
                stage = OnDeviceFailureStage.SUMMARIZE,
            )
        }
        val summaryToken = UUID.randomUUID().toString()
        check(
            sessions.startOperation(
                id = job.sessionId,
                allowedStates = setOf(
                    OnDeviceSessionState.TRANSCRIPT_READY,
                    OnDeviceSessionState.FAILED_RECOVERABLE,
                    OnDeviceSessionState.COMPLETE,
                ),
                targetState = OnDeviceSessionState.SUMMARIZING,
                token = summaryToken,
            ),
        ) { "최종 계층형 요약을 session에 적용할 수 없습니다." }
        val projection = HierarchicalSummaryProjection.build(
            rootSummary = finalRoot.summary,
            sectionSummaries = allNodes
                .filter { it.level == 0 && it.state == "PASSED" }
                .sortedBy(OnDeviceSummaryNodeEntity::ordinal)
                .map(OnDeviceSummaryNodeEntity::summary),
        )
        val finalSummary = LocalSummary(
            title = finalRoot.title,
            bullets = projection.bullets,
            actionItems = emptyList(),
            sourceHash = sha256(currentSession.transcript),
            modelVersion = finalRoot.modelVersion,
            validationStatus = "PASSED_HIERARCHICAL_V2",
            requestedModelId = "GEMMA_SUMMARY_KO",
            actualModelId = "GEMMA_SUMMARY_KO",
            runtimeType = finalRoot.runtimeType,
            generationProfile = finalRoot.generationProfile,
            durationMs = allNodes.sumOf { it.durationMs ?: 0L },
            inputChars = finalRoot.inputPayload.length,
            outputChars = projection.bullets.joinToString("\n").length,
        )
        check(
            jobs.finalizeSuccess(
                job = requireNotNull(jobs.getJob(job.id)),
                serviceToken = serviceToken,
                summaryToken = summaryToken,
                rootNodeId = finalRoot.id,
                processingVersion = PROCESSING_VERSION,
                result = finalSummary,
            ),
        ) { "최종 계층형 Gemma 결과와 job 완료 상태를 저장하지 못했습니다." }
        return requireNotNull(jobs.getJob(job.id)).also { onProgress(it) }
    }

    fun cancelNative() {
        speech.cancel()
    }

    private suspend fun prepareSttSession(
        session: com.coreline.ai.voice.ondevice.data.OnDeviceSessionEntity,
        job: OnDeviceProcessingJobEntity,
    ): String {
        if (
            session.state == OnDeviceSessionState.STARTING.name &&
            !session.operationToken.isNullOrBlank()
        ) {
            check(
                sessions.advanceOperation(
                    id = session.id,
                    token = session.operationToken,
                    allowedStates = setOf(OnDeviceSessionState.STARTING),
                    targetState = OnDeviceSessionState.TRANSCRIBING,
                ),
            )
            return session.operationToken
        }
        val token = UUID.randomUUID().toString()
        check(
            sessions.startOperation(
                id = job.sessionId,
                allowedStates = setOf(
                    OnDeviceSessionState.FAILED_RECOVERABLE,
                    OnDeviceSessionState.AUDIO_READY,
                    OnDeviceSessionState.TRANSCRIPT_READY,
                ),
                targetState = OnDeviceSessionState.TRANSCRIBING,
                token = token,
            ),
        ) { "장시간 STT session을 재개할 수 없습니다." }
        return token
    }

    private suspend fun update(
        job: OnDeviceProcessingJobEntity,
        serviceToken: String,
        stage: LongAudioStage,
    ) {
        check(jobs.updateProgress(job, serviceToken, stage)) {
            "장시간 작업 진행 상태가 만료되었습니다."
        }
        onProgress(requireNotNull(jobs.getJob(job.id)))
    }

    private suspend fun ensureRunnable(jobId: String) {
        currentCoroutineContext().ensureActive()
        val latest = requireNotNull(jobs.getJob(jobId))
        if (latest.cancelRequested) throw LongAudioCancellationException(cancel = true)
        if (latest.pauseRequested) throw LongAudioCancellationException(cancel = false)
    }

    private fun checkStorage(job: OnDeviceProcessingJobEntity, pcm: File) {
        val expectedPcmBytes = job.sourceDurationMs * PCM_BYTES_PER_MILLISECOND
        val remainingPcmBytes = (expectedPcmBytes - pcm.length()).coerceAtLeast(0L)
        val required = remainingPcmBytes + MIN_STORAGE_RESERVE_BYTES
        val available = StatFs(applicationContext.filesDir.absolutePath).availableBytes
        if (available < required) {
            throw LongAudioProcessingException(
                code = "STORAGE_BLOCKED",
                message = "장시간 PCM 변환에 필요한 저장공간이 부족합니다. " +
                    "필요 ${required / MIB}MB, 사용 가능 ${available / MIB}MB",
                stage = OnDeviceFailureStage.NORMALIZE,
            )
        }
    }

    private fun OnDeviceSummaryNodeEntity.toReference(): SummaryNodeReference =
        SummaryNodeReference(
            id = id,
            level = level,
            ordinal = ordinal,
            sourceStartMs = sourceStartMs,
            sourceEndMs = sourceEndMs,
            leafStartOrdinal = leafStartOrdinal,
            leafEndOrdinal = leafEndOrdinal,
            summary = summary,
            sourceHash = sourceHash,
        )

    private fun evidenceCandidates(
        node: OnDeviceSummaryNodeEntity,
        sourceSegments: List<com.coreline.ai.voice.ondevice.data.OnDeviceTranscriptSegmentEntity>,
        lowerLevelNodes: List<OnDeviceSummaryNodeEntity>,
    ): List<SummaryEvidenceCandidate> {
        val childIds = node.childNodeIds
            .split(HierarchicalSummaryPlanner.CHILD_SEPARATOR)
            .filter(String::isNotBlank)
        if (node.level == 0) {
            val segmentsById = sourceSegments.associateBy { it.id }
            return childIds.mapNotNull { childId ->
                segmentsById[childId.substringBefore('#')]?.let { segment ->
                    SummaryEvidenceCandidate(id = childId, text = segment.text)
                }
            }
        }
        val nodesById = lowerLevelNodes.associateBy(OnDeviceSummaryNodeEntity::id)
        return childIds.mapNotNull { childId ->
            nodesById[childId]?.let { child ->
                SummaryEvidenceCandidate(id = childId, text = child.summary)
            }
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PROCESSING_VERSION = 1
        const val PCM_BYTES_PER_MILLISECOND = 32L
        const val MIN_STORAGE_RESERVE_BYTES = 64L * 1024L * 1024L
        const val MIB = 1024L * 1024L
        const val EXTRA_REDUCTION_HEADROOM = 16
    }
}

class LongAudioProcessingException(
    val code: String,
    override val message: String,
    val stage: OnDeviceFailureStage,
) : IllegalStateException(message)

class LongAudioCancellationException(
    val cancel: Boolean,
) : CancellationException(if (cancel) "장시간 처리를 취소했습니다." else "장시간 처리를 일시 중지했습니다.")
