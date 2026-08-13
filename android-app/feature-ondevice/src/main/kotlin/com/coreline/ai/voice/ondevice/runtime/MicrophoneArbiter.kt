package com.coreline.ai.voice.ondevice.runtime

import java.util.concurrent.atomic.AtomicReference

enum class MicrophoneOwner {
    MAIN_RECORDER,
    LOCAL_AI,
}

/**
 * Process-wide ownership guard shared by the existing recorder service and the
 * local-AI capture flows. UI disabling alone cannot close the race between two
 * start commands, so both implementations acquire this guard before capture.
 */
object MicrophoneArbiter {
    private val owner = AtomicReference<MicrophoneOwner?>(null)

    fun tryAcquire(candidate: MicrophoneOwner): Boolean =
        owner.compareAndSet(null, candidate)

    fun release(candidate: MicrophoneOwner) {
        owner.compareAndSet(candidate, null)
    }

    fun currentOwner(): MicrophoneOwner? = owner.get()
}
