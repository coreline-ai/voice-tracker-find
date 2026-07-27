package com.thinktank.recorder.ondevice.api

enum class SttEngineType {
    ANDROID_ON_DEVICE,
    /** Legacy system file-input path retained only so older local records remain readable. */
    ANDROID_ON_DEVICE_FILE,
    SENSEVOICE_LOCAL_FILE,
}

enum class SummaryEngineType {
    NONE,
    EXTRACTIVE_KOTLIN,
    QWEN_LOCAL,
    EXAONE_LOCAL,
    GEMMA_LOCAL,
    /** Qwen generated the title/summary, then local source sentences filled missing detail rows. */
    QWEN_LOCAL_GROUNDED,
}

/**
 * A requested sentence boundary for Android's system recognizer. The recognizer provider owns
 * the final endpointing decision, so these values are intentionally treated as a preference.
 */
enum class SttCaptureProfile(
    val silenceMillis: Long,
    val restartDelayMillis: Long,
    val title: String,
    val description: String,
) {
    QUICK(
        silenceMillis = 800L,
        restartDelayMillis = 250L,
        title = "빠르게 이어쓰기",
        description = "약 0.8초 무음 기준 · 짧은 멈춤 뒤 바로 다시 듣습니다.",
    ),
    BALANCED(
        silenceMillis = 1_500L,
        restartDelayMillis = 350L,
        title = "보통 문장 단위",
        description = "약 1.5초 무음 기준 · 일반 대화에 맞춘 기본값입니다.",
    ),
    LONG_PAUSE(
        silenceMillis = 2_500L,
        restartDelayMillis = 500L,
        title = "긴 문장 우선",
        description = "약 2.5초 무음 기준 · 생각하며 말할 때 적합합니다.",
    ),
}

enum class OnDeviceSessionState {
    IDLE,
    STARTING,
    LISTENING,
    AUDIO_READY,
    TRANSCRIBING,
    TRANSCRIPT_READY,
    SUMMARIZING,
    CANCELLING,
    DELETING,
    COMPLETE,
    CANCELLED,
    FAILED_RECOVERABLE,
    FAILED_PERMANENT,
}

enum class OnDeviceOperationKind {
    LIVE_STT,
    FILE_STT,
    KOTLIN_SUMMARY,
    QWEN_SUMMARY,
}

enum class OnDeviceFailureStage {
    CAPTURE,
    NORMALIZE,
    TRANSCRIBE,
    SUMMARIZE,
    DELETE,
}

data class TranscriptSegment(
    val startMs: Long? = null,
    val endMs: Long? = null,
    val text: String,
    val confidence: Float? = null,
)

data class SttResult(
    val text: String,
    val segments: List<TranscriptSegment>,
)

data class LocalSummary(
    val title: String,
    val bullets: List<String>,
    val actionItems: List<String>,
    val engine: SummaryEngineType = SummaryEngineType.EXTRACTIVE_KOTLIN,
    val sourceHash: String = "",
    val fallbackReason: String? = null,
    val policyVersion: Int? = null,
    val promptVersion: Int? = null,
    val modelVersion: String? = null,
    val validationStatus: String? = null,
    val requestedModelId: String? = null,
    val actualModelId: String? = null,
    val runtimeType: String? = null,
    val generationProfile: String? = null,
    val violationCodes: String? = null,
    val durationMs: Long? = null,
    val inputChars: Int? = null,
    val outputChars: Int? = null,
)

sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    data object Listening : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
}

interface LiveSttEngine {
    fun isAvailable(): Boolean
    suspend fun recognize(
        profile: SttCaptureProfile = SttCaptureProfile.BALANCED,
        onProgress: (SpeechEvent) -> Unit = {},
    ): SttResult
    fun stop()
    fun cancel()
    fun release()
}

interface SummaryEngine {
    suspend fun summarize(transcript: String): LocalSummary
}
