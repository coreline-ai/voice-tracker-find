package com.thinktank.recorder.next.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingRuntimeTest {
    @Test
    fun captureResetDoesNotHideCommandFailure() {
        val runtime = RecordingRuntime()
        runtime.updateAmplitude(16_000)
        runtime.reportCommandError("마이크 사용 중")

        runtime.reset()

        assertEquals(0f, runtime.amplitude.value)
        assertEquals("마이크 사용 중", runtime.commandError.value)
        runtime.clearCommandError()
        assertNull(runtime.commandError.value)
    }
}
