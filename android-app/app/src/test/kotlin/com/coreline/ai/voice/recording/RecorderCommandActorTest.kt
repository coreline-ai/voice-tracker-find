package com.coreline.ai.voice.recording

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderCommandActorTest {
    @Test
    fun commandsRunInTheSameOrderTheyAreAccepted() = runBlocking {
        val handled = mutableListOf<String>()
        val allHandled = CompletableDeferred<Unit>()
        val actor = RecorderCommandActor(this) { command ->
            handled += when (command) {
                is RecorderServiceCommand.Start -> "start:${command.startId}"
                is RecorderServiceCommand.Stop -> "stop:${command.startId}"
                is RecorderServiceCommand.RunnerCompleted -> "completed"
            }
            if (handled.size == 3) allHandled.complete(Unit)
        }

        actor.send(RecorderServiceCommand.Start(1))
        actor.send(RecorderServiceCommand.Start(2))
        actor.send(RecorderServiceCommand.Stop(3))

        withTimeout(1_000) { allHandled.await() }
        assertEquals(listOf("start:1", "start:2", "stop:3"), handled)
        actor.close()
        actor.job.join()
    }
}
