package com.coreline.ai.voice.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingWindowTest {
    @Test
    fun normalWindowIncludesStartAndExcludesEnd() {
        assertTrue(RecordingWindow.contains(7 * 60, 7 * 60, 22 * 60, true))
        assertTrue(RecordingWindow.contains(21 * 60 + 59, 7 * 60, 22 * 60, true))
        assertFalse(RecordingWindow.contains(22 * 60, 7 * 60, 22 * 60, true))
    }

    @Test
    fun overnightWindowCrossesMidnight() {
        assertTrue(RecordingWindow.contains(23 * 60, 22 * 60, 7 * 60, true))
        assertTrue(RecordingWindow.contains(6 * 60 + 59, 22 * 60, 7 * 60, true))
        assertFalse(RecordingWindow.contains(12 * 60, 22 * 60, 7 * 60, true))
    }

    @Test
    fun disabledOrSameBoundaryMeansAllDay() {
        assertTrue(RecordingWindow.contains(0, 7 * 60, 22 * 60, false))
        assertTrue(RecordingWindow.contains(12 * 60, 7 * 60, 7 * 60, true))
    }
}

