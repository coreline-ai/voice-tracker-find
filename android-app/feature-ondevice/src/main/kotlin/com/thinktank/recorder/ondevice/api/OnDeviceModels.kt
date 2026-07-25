package com.thinktank.recorder.ondevice.api

import java.io.File

enum class SttEngineType {
    ANDROID_ON_DEVICE,
    MOONSHINE_LOCAL,
}

enum class SummaryEngineType {
    NONE,
    EXTRACTIVE_KOTLIN,
    QWEN_LOCAL,
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
    LOCAL_CAPTURE,
    MOONSHINE_TRANSCRIBE,
    KOTLIN_SUMMARY,
    QWEN_SUMMARY,
}

enum class OnDeviceFailureStage {
    CAPTURE,
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
)

sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    data object Listening : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
}

interface LiveSttEngine {
    fun isAvailable(): Boolean
    suspend fun recognize(onProgress: (SpeechEvent) -> Unit = {}): SttResult
    fun stop()
    fun cancel()
    fun release()
}

interface FileSttEngine {
    suspend fun transcribe(
        audioFile: File,
        onProgress: (Float) -> Unit = {},
    ): SttResult

    fun cancel()
    fun release()
}

interface SummaryEngine {
    suspend fun summarize(transcript: String): LocalSummary
}
