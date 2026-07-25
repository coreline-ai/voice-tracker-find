package com.thinktank.recorder.ondevice.modelpack

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ModelOperationCoordinator {
    private val locks = ConcurrentHashMap<ModelId, Mutex>()

    suspend fun <T> withLock(id: ModelId, block: suspend () -> T): T =
        locks.getOrPut(id) { Mutex() }.withLock { block() }
}
