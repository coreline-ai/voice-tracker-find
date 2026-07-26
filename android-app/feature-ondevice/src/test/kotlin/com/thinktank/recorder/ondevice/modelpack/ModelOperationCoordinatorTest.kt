package com.thinktank.recorder.ondevice.modelpack

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelOperationCoordinatorTest {
    @Test
    fun operationsForSameModelAreSerialized() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async {
            ModelOperationCoordinator.withLock(ModelId.QWEN_SUMMARY_KO) {
                order += "first-enter"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-exit"
            }
        }
        firstEntered.await()
        val second = async {
            ModelOperationCoordinator.withLock(ModelId.QWEN_SUMMARY_KO) {
                order += "second-enter"
            }
        }
        delay(20)
        assertEquals(listOf("first-enter"), order)
        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first-enter", "first-exit", "second-enter"), order)
    }

    @Test
    fun oneHundredCompetingOperationsNeverEnterSameModelCriticalSectionTogether() = runBlocking {
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val jobs = List(100) {
            async {
                ModelOperationCoordinator.withLock(ModelId.QWEN_SUMMARY_KO) {
                    val entered = active.incrementAndGet()
                    maximum.updateAndGet { current -> maxOf(current, entered) }
                    delay(1)
                    active.decrementAndGet()
                }
            }
        }

        jobs.forEach { it.await() }
        assertEquals(1, maximum.get())
        assertEquals(0, active.get())
    }
}
