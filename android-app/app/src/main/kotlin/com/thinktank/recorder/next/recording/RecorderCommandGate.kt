package com.thinktank.recorder.next.recording

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes START/STOP commands behind the one-time file/DB reconciliation barrier. */
internal class RecorderCommandGate(
    private val awaitReconciliation: suspend () -> Unit,
) {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T =
        mutex.withLock {
            awaitReconciliation()
            block()
        }
}
