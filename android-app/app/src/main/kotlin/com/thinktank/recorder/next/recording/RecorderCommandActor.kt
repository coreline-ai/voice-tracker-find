package com.thinktank.recorder.next.recording

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A single FIFO command stream for the foreground recorder service.
 *
 * Android can deliver START and STOP intents faster than individual coroutine launches are
 * scheduled.  Keeping them in one channel makes the delivery order the execution order.
 */
internal sealed interface RecorderServiceCommand {
    data class Start(val startId: Int) : RecorderServiceCommand

    data class Stop(val startId: Int) : RecorderServiceCommand

    data class RunnerCompleted(val runner: Job) : RecorderServiceCommand
}

internal class RecorderCommandActor(
    scope: CoroutineScope,
    private val handle: suspend (RecorderServiceCommand) -> Unit,
) {
    private val commands = Channel<RecorderServiceCommand>(Channel.UNLIMITED)

    val job: Job = scope.launch {
        for (command in commands) handle(command)
    }

    fun send(command: RecorderServiceCommand) {
        check(commands.trySend(command).isSuccess) { "녹음 명령을 처리할 수 없습니다" }
    }

    fun close() {
        commands.close()
    }
}
