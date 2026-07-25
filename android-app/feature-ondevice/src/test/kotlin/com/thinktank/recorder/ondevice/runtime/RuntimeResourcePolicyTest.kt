package com.thinktank.recorder.ondevice.runtime

import android.os.PowerManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeResourcePolicyTest {
    @Test
    fun healthyFourGigabyteDeviceCanRunQwen() {
        assertNull(
            RuntimeResourcePolicy.qwenBlockReason(
                snapshot(totalGb = 4, availableMb = 1_600),
            ),
        )
    }

    @Test
    fun lowMemoryAndSevereThermalStatesAreBlocked() {
        assertNotNull(
            RuntimeResourcePolicy.qwenBlockReason(
                snapshot(totalGb = 4, availableMb = 600),
            ),
        )
        assertNotNull(
            RuntimeResourcePolicy.qwenBlockReason(
                snapshot(
                    totalGb = 4,
                    availableMb = 1_600,
                    thermalStatus = PowerManager.THERMAL_STATUS_SEVERE,
                ),
            ),
        )
    }

    private fun snapshot(
        totalGb: Long,
        availableMb: Long,
        thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    ) = RuntimeResourceSnapshot(
        totalMemoryBytes = totalGb * 1024 * 1024 * 1024,
        availableMemoryBytes = availableMb * 1024 * 1024,
        lowMemory = false,
        thermalStatus = thermalStatus,
        powerSaveMode = false,
        batteryPercent = 80,
    )
}
