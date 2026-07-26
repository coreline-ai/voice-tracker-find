package com.thinktank.recorder.next.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePolicyTest {
    @Test
    fun `recording requirement keeps a minimum free space floor`() {
        assertEquals(
            StoragePolicy.LOW_FREE_SPACE_BYTES,
            StoragePolicy.requiredBytesForRecording(5),
        )
    }

    @Test
    fun `recording requirement grows for a long chunk`() {
        assertTrue(
            StoragePolicy.requiredBytesForRecording(120) >
                StoragePolicy.requiredBytesForRecording(5),
        )
    }

    @Test
    fun `recording start is allowed only at or above required capacity`() {
        val required = StoragePolicy.requiredBytesForRecording(20)
        assertFalse(StoragePolicy.recordingCheck(required - 1, 20).canStart)
        assertTrue(StoragePolicy.recordingCheck(required, 20).canStart)
    }
}
