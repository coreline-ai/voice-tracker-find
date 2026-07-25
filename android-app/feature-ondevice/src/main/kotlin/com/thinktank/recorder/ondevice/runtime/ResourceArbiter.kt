package com.thinktank.recorder.ondevice.runtime

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class NativeWorkload {
    MOONSHINE_STT,
    QWEN_SUMMARY,
}

/**
 * A process-wide gate for native model residency.
 *
 * Moonshine and Qwen are intentionally never loaded at the same time. The
 * active value is observable for diagnostics, while [withLease] provides the
 * actual mutual exclusion guarantee.
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
