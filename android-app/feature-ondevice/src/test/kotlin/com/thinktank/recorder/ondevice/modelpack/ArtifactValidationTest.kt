package com.thinktank.recorder.ondevice.modelpack

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ArtifactValidationTest {
    @Test
    fun completeArtifactGateRequiresAnExistingFileWithTheExactSize() {
        val artifact = File.createTempFile("artifact", ".bin")
        try {
            artifact.writeBytes(ByteArray(4))
            assertTrue(hasCompleteArtifactFile(artifact, expectedBytes = 4))
            assertFalse(hasCompleteArtifactFile(artifact, expectedBytes = 3))
            assertFalse(hasCompleteArtifactFile(File(artifact.parentFile, "missing.bin"), expectedBytes = 4))
        } finally {
            artifact.delete()
        }
    }

    @Test
    fun exactSizeGuardRejectsOversizeAndUndersizeStreams() {
        val oversized = ExactArtifactSizeGuard(expectedBytes = 4, initialBytes = 0)
        oversized.accept(4)
        assertThrows(ArtifactValidationException::class.java) { oversized.accept(1) }

        val undersized = ExactArtifactSizeGuard(expectedBytes = 4, initialBytes = 0)
        undersized.accept(3)
        assertThrows(ArtifactValidationException::class.java) { undersized.verifyEof() }

        val exact = ExactArtifactSizeGuard(expectedBytes = 4, initialBytes = 1)
        exact.accept(3)
        exact.verifyEof()
        assertEquals(4L, exact.copiedBytes)
    }

    @Test
    fun range416AcceptsOnlyExactHashOrRetriesFullOnce() {
        assertEquals(
            Range416Decision.ACCEPT_COMPLETE,
            range416Decision(10, 10, shaMatches = true, fullRetryUsed = false),
        )
        assertEquals(
            Range416Decision.RETRY_FULL,
            range416Decision(9, 10, shaMatches = false, fullRetryUsed = false),
        )
        assertEquals(
            Range416Decision.FAIL,
            range416Decision(9, 10, shaMatches = false, fullRetryUsed = true),
        )
    }
}
