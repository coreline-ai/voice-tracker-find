package com.thinktank.recorder.next.recording

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderCommandGateTest {
    @Test
    fun commandsCannotPassReconciliationAndRemainSerialized() = runBlocking {
        val reconciled = CompletableDeferred<Unit>()
        val gate = RecorderCommandGate { reconciled.await() }
        val events = mutableListOf<String>()

        val start = async {
            gate.run {
                events += "start-enter"
                delay(20)
                events += "start-exit"
            }
        }
        val stop = async {
            gate.run {
                events += "stop"
            }
        }
        delay(20)
        assertEquals(emptyList<String>(), events)

        reconciled.complete(Unit)
        start.await()
        stop.await()
        assertEquals(listOf("start-enter", "start-exit", "stop"), events)
    }
}
