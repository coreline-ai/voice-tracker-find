package com.coreline.ai.voice.ondevice.stt

import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalResultGateTest {
    @Test
    fun partialBurstCannotConsumeTerminalCompletion() {
        var partialCount = 0
        val completions = mutableListOf<Result<String>>()
        val gate = TerminalResultGate<String>(completions::add)

        repeat(100) { partialCount += 1 }
        assertTrue(gate.tryComplete(Result.success("final")))

        assertEquals(100, partialCount)
        assertEquals("final", completions.single().getOrThrow())
    }

    @Test
    fun finalAndErrorRaceCompletesExactlyOnce() {
        repeat(100) {
            val completions = Collections.synchronizedList(mutableListOf<Result<String>>())
            val gate = TerminalResultGate<String>(completions::add)
            val start = CountDownLatch(1)
            val first = thread {
                start.await()
                gate.tryComplete(Result.success("final"))
            }
            val second = thread {
                start.await()
                gate.tryComplete(Result.failure(IllegalStateException("error")))
            }

            start.countDown()
            first.join()
            second.join()
            assertEquals(1, completions.size)
            assertTrue(completions.single().isSuccess || completions.single().isFailure)
        }
    }
}
