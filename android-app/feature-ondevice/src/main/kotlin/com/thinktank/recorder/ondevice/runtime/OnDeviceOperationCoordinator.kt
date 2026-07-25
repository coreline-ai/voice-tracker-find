package com.thinktank.recorder.ondevice.runtime

import com.thinktank.recorder.ondevice.api.OnDeviceOperationKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveOperation(
    val token: String,
    val sessionId: String,
    val kind: OnDeviceOperationKind,
    val job: Job? = null,
)

/**
 * Process-local single-flight gate for local capture and inference.
 *
 * Reservation is synchronous so UI/lifecycle cancellation can observe STARTING
 * before the coroutine reaches Room or a native engine.
 */
class OnDeviceOperationCoordinator {
    private val lock = Any()
    private val mutableActive = MutableStateFlow<ActiveOperation?>(null)

    val active: StateFlow<ActiveOperation?> = mutableActive.asStateFlow()

    fun reserve(
        token: String,
        sessionId: String,
        kind: OnDeviceOperationKind,
    ): ActiveOperation? = synchronized(lock) {
        if (mutableActive.value != null) return@synchronized null
        ActiveOperation(token, sessionId, kind).also { mutableActive.value = it }
    }

    fun attach(token: String, job: Job): Boolean = synchronized(lock) {
        val current = mutableActive.value
        if (current?.token != token) {
            job.cancel()
            return@synchronized false
        }
        mutableActive.value = current.copy(job = job)
        true
    }

    fun cancelActive(): ActiveOperation? = synchronized(lock) {
        mutableActive.value?.also { it.job?.cancel() }
    }

    fun finish(token: String): Boolean = synchronized(lock) {
        if (mutableActive.value?.token != token) return@synchronized false
        mutableActive.value = null
        true
    }

    fun isActive(sessionId: String): Boolean =
        mutableActive.value?.sessionId == sessionId
}
