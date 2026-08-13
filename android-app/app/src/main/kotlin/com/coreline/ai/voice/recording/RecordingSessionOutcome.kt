package com.coreline.ai.voice.recording

import com.coreline.ai.voice.data.local.RecordingState

internal data class RecordingSessionOutcome(
    val state: String,
    val error: String? = null,
)

/** Keeps a capture failure terminal even if best-effort file cleanup later succeeds. */
internal fun terminalRecordingOutcome(
    captureFailure: String?,
    chunkFinalized: Boolean,
): RecordingSessionOutcome = when {
    captureFailure != null -> RecordingSessionOutcome(RecordingState.FAILED, captureFailure)
    chunkFinalized -> RecordingSessionOutcome(RecordingState.STOPPED)
    else -> RecordingSessionOutcome(RecordingState.FAILED, "FINALIZE_FAILED")
}
