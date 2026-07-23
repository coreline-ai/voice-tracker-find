package com.thinktank.recorder.next.recording

import java.time.LocalTime

object RecordingWindow {
    fun contains(
        currentMinutes: Int,
        startMinutes: Int,
        endMinutes: Int,
        enabled: Boolean,
    ): Boolean {
        if (!enabled || startMinutes == endMinutes) return true
        return if (startMinutes < endMinutes) {
            currentMinutes in startMinutes until endMinutes
        } else {
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    fun contains(
        now: LocalTime,
        startMinutes: Int,
        endMinutes: Int,
        enabled: Boolean,
    ): Boolean = contains(now.hour * 60 + now.minute, startMinutes, endMinutes, enabled)
}

