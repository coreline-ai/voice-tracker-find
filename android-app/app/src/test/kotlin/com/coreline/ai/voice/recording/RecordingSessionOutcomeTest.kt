package com.coreline.ai.voice.recording

import com.coreline.ai.voice.data.local.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingSessionOutcomeTest {
    @Test
    fun captureFailureIsNeverOverwrittenBySuccessfulCleanup() {
        val outcome = terminalRecordingOutcome(
            captureFailure = "IllegalStateException: microphone lost",
            chunkFinalized = true,
        )

        assertEquals(RecordingState.FAILED, outcome.state)
        assertEquals("IllegalStateException: microphone lost", outcome.error)
    }

    @Test
    fun successfulStopHasNoTerminalError() {
        val outcome = terminalRecordingOutcome(captureFailure = null, chunkFinalized = true)

        assertEquals(RecordingState.STOPPED, outcome.state)
        assertNull(outcome.error)
    }

    @Test
    fun failedFinalizationIsExplicitlyFailed() {
        val outcome = terminalRecordingOutcome(captureFailure = null, chunkFinalized = false)

        assertEquals(RecordingState.FAILED, outcome.state)
        assertEquals("FINALIZE_FAILED", outcome.error)
    }
}
