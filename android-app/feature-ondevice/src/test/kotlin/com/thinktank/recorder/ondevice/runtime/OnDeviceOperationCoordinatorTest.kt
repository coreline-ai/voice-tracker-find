package com.thinktank.recorder.ondevice.runtime

import com.thinktank.recorder.ondevice.api.OnDeviceOperationKind
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceOperationCoordinatorTest {
    private val coordinator = OnDeviceOperationCoordinator()

    @Test
    fun reservationIsSingleFlightAndStaleTokenCannotFinishNewOperation() {
        val first = coordinator.reserve("old", "session-1", OnDeviceOperationKind.LIVE_STT)
        assertEquals("old", first?.token)
        assertNull(
            coordinator.reserve("blocked", "session-2", OnDeviceOperationKind.QWEN_SUMMARY),
        )

        assertTrue(coordinator.finish("old"))
        assertEquals(
            "new",
            coordinator.reserve(
                "new",
                "session-2",
                OnDeviceOperationKind.QWEN_SUMMARY,
            )?.token,
        )
        assertFalse(coordinator.finish("old"))
        assertEquals("new", coordinator.active.value?.token)
    }

    @Test
    fun cancelTargetsOnlyAttachedActiveJob() {
        val job: CompletableJob = Job()
        coordinator.reserve("token", "session", OnDeviceOperationKind.LOCAL_CAPTURE)
        assertTrue(coordinator.attach("token", job))

        assertEquals("token", coordinator.cancelActive()?.token)
        assertTrue(job.isCancelled)
        assertEquals("token", coordinator.active.value?.token)
        assertTrue(coordinator.finish("token"))
        assertNull(coordinator.active.value)
    }
}
