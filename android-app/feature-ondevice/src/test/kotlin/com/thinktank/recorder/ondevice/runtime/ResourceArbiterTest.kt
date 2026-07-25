package com.thinktank.recorder.ondevice.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ResourceArbiterTest {
    @Test
    fun nativeWorkloadsNeverOverlap() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async {
            ResourceArbiter.withLease(NativeWorkload.MOONSHINE_STT) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        assertEquals(NativeWorkload.MOONSHINE_STT, ResourceArbiter.activeWorkload())

        val second = async {
            ResourceArbiter.withLease(NativeWorkload.QWEN_SUMMARY) {
                ResourceArbiter.activeWorkload()
            }
        }
        yield()
        assertFalse(second.isCompleted)

        releaseFirst.complete(Unit)
        first.await()
        assertEquals(NativeWorkload.QWEN_SUMMARY, second.await())
        assertNull(ResourceArbiter.activeWorkload())
    }
}
