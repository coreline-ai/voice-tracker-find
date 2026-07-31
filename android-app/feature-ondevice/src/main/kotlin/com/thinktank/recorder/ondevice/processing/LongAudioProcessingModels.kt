package com.thinktank.recorder.ondevice.processing

import com.thinktank.recorder.ondevice.data.OnDeviceProcessingJobEntity

enum class LongAudioJobState {
    QUEUED,
    RUNNING,
    PAUSING,
    PAUSED,
    CANCELLING,
    CANCELLED,
    INTERRUPTED,
    FAILED_RECOVERABLE,
    FAILED_PERMANENT,
    COMPLETE,
}

enum class LongAudioStage {
    PREPARING_SOURCE,
    NORMALIZING,
    TRANSCRIBING,
    PLANNING_SUMMARY,
    SUMMARIZING_LEAVES,
    REDUCING,
    VALIDATING,
    COMPLETE,
}

val OnDeviceProcessingJobEntity.jobState: LongAudioJobState
    get() = runCatching { LongAudioJobState.valueOf(state) }
        .getOrDefault(LongAudioJobState.FAILED_RECOVERABLE)

val OnDeviceProcessingJobEntity.jobStage: LongAudioStage
    get() = runCatching { LongAudioStage.valueOf(stage) }
        .getOrDefault(LongAudioStage.PREPARING_SOURCE)

val OnDeviceProcessingJobEntity.isTerminal: Boolean
    get() = jobState in setOf(
        LongAudioJobState.CANCELLED,
        LongAudioJobState.FAILED_PERMANENT,
        LongAudioJobState.COMPLETE,
    )

val OnDeviceProcessingJobEntity.canResume: Boolean
    get() = jobState in setOf(
        LongAudioJobState.PAUSED,
        LongAudioJobState.INTERRUPTED,
        LongAudioJobState.FAILED_RECOVERABLE,
    )

val OnDeviceProcessingJobEntity.progress: Float
    get() = when (jobStage) {
        LongAudioStage.PREPARING_SOURCE -> 0.01f
        LongAudioStage.NORMALIZING -> 0.05f
        LongAudioStage.TRANSCRIBING -> {
            val ratio = completedSttSegments.toFloat() / totalSttSegments.coerceAtLeast(1)
            0.10f + ratio.coerceIn(0f, 1f) * 0.55f
        }
        LongAudioStage.PLANNING_SUMMARY -> 0.66f
        LongAudioStage.SUMMARIZING_LEAVES,
        LongAudioStage.REDUCING,
        -> {
            val ratio = completedSummaryNodes.toFloat() / totalSummaryNodes.coerceAtLeast(1)
            0.67f + ratio.coerceIn(0f, 1f) * 0.28f
        }
        LongAudioStage.VALIDATING -> 0.97f
        LongAudioStage.COMPLETE -> 1f
    }

val OnDeviceProcessingJobEntity.displayLabel: String
    get() = when (jobState) {
        LongAudioJobState.PAUSING -> "현재 구간을 저장한 뒤 일시 중지 중"
        LongAudioJobState.PAUSED -> if (failureCode == "THERMAL_PAUSED") {
            "기기 과열로 장시간 처리가 일시 중지됨"
        } else {
            "장시간 처리가 일시 중지됨"
        }
        LongAudioJobState.CANCELLING -> "장시간 처리를 안전하게 취소 중"
        LongAudioJobState.INTERRUPTED -> "중단된 장시간 처리 · 재개 가능"
        LongAudioJobState.FAILED_RECOVERABLE -> "장시간 처리 오류 · 재개 가능"
        else -> when (jobStage) {
            LongAudioStage.PREPARING_SOURCE -> "원본 녹음 확인 중"
            LongAudioStage.NORMALIZING -> "장시간 녹음 PCM 변환 중"
            LongAudioStage.TRANSCRIBING ->
                "SenseVoice STT ${completedSttSegments}/${totalSttSegments.coerceAtLeast(completedSttSegments)}"
            LongAudioStage.PLANNING_SUMMARY -> "계층형 요약 구간 구성 중"
            LongAudioStage.SUMMARIZING_LEAVES ->
                "Gemma 구간 요약 ${completedSummaryNodes}/${totalSummaryNodes.coerceAtLeast(completedSummaryNodes)}"
            LongAudioStage.REDUCING ->
                "Gemma 상위 요약 ${completedSummaryNodes}/${totalSummaryNodes.coerceAtLeast(completedSummaryNodes)}"
            LongAudioStage.VALIDATING -> "전체 범위와 최종 요약 검증 중"
            LongAudioStage.COMPLETE -> "장시간 로컬 분석 완료"
        }
    }
