package com.thinktank.recorder.ondevice.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneArbiterTest {
    @After
    fun releaseOwners() {
        MicrophoneArbiter.release(MicrophoneOwner.MAIN_RECORDER)
        MicrophoneArbiter.release(MicrophoneOwner.LOCAL_AI)
    }

    @Test
    fun ownersAreMutuallyExclusiveAndReleaseIsOwnerChecked() {
        assertTrue(MicrophoneArbiter.tryAcquire(MicrophoneOwner.LOCAL_AI))
        assertEquals(MicrophoneOwner.LOCAL_AI, MicrophoneArbiter.currentOwner())
        assertFalse(MicrophoneArbiter.tryAcquire(MicrophoneOwner.MAIN_RECORDER))

        MicrophoneArbiter.release(MicrophoneOwner.MAIN_RECORDER)
        assertEquals(MicrophoneOwner.LOCAL_AI, MicrophoneArbiter.currentOwner())

        MicrophoneArbiter.release(MicrophoneOwner.LOCAL_AI)
        assertNull(MicrophoneArbiter.currentOwner())
        assertTrue(MicrophoneArbiter.tryAcquire(MicrophoneOwner.MAIN_RECORDER))
    }
}
