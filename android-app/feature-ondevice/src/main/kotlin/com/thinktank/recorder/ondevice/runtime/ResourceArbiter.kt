package com.thinktank.recorder.ondevice.runtime

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class NativeWorkload {
    SENSEVOICE_FILE_STT,
    QWEN_SUMMARY,
    MODEL_MAINTENANCE,
}

/**
 * A process-wide gate for native model residency.
 *
 * Native STT and Qwen inference are isolated to one active lease. The active value is
 * observable for diagnostics, while [withLease] provides the exclusion guarantee.
 */
object ResourceArbiter {
    private val mutex = Mutex()
    private val active = AtomicReference<NativeWorkload?>(null)

    fun activeWorkload(): NativeWorkload? = active.get()

    suspend fun <T> withLease(workload: NativeWorkload, block: suspend () -> T): T =
        mutex.withLock {
            check(active.compareAndSet(null, workload)) {
                "다른 로컬 AI 작업이 실행 중입니다"
            }
            try {
                block()
            } finally {
                active.compareAndSet(workload, null)
            }
        }
}
