package com.thinktank.recorder.ondevice.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

data class RuntimeResourceSnapshot(
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val lowMemory: Boolean,
    val thermalStatus: Int,
    val powerSaveMode: Boolean,
    val batteryPercent: Int?,
)

object RuntimeResourcePolicy {
    private const val MIN_QWEN_TOTAL_MEMORY = 3L * 1024 * 1024 * 1024
    private const val MIN_QWEN_AVAILABLE_MEMORY = 1_050L * 1024 * 1024

    fun qwenBlockReason(snapshot: RuntimeResourceSnapshot): String? = when {
        snapshot.totalMemoryBytes < MIN_QWEN_TOTAL_MEMORY ->
            "Qwen 실행에는 RAM 3GB 이상이 필요합니다."
        snapshot.lowMemory || snapshot.availableMemoryBytes < MIN_QWEN_AVAILABLE_MEMORY ->
            "Qwen 실행에 필요한 여유 메모리가 부족합니다. 다른 앱을 닫고 다시 시도하세요."
        snapshot.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ->
            "기기가 과열되어 Qwen 실행을 중단했습니다. 식힌 뒤 다시 시도하세요."
        snapshot.powerSaveMode && snapshot.batteryPercent != null && snapshot.batteryPercent <= 15 ->
            "배터리가 부족하고 절전 모드가 켜져 있어 Qwen 실행을 중단했습니다."
        else -> null
    }
}

class DeviceResourceGuard(context: Context) {
    private val applicationContext = context.applicationContext

    fun requireQwenCapacity() {
        val reason = RuntimeResourcePolicy.qwenBlockReason(snapshot())
        if (reason != null) error(reason)
    }

    private fun snapshot(): RuntimeResourceSnapshot {
        val activityManager = applicationContext.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val power = applicationContext.getSystemService(PowerManager::class.java)
        val battery = applicationContext.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
        return RuntimeResourceSnapshot(
            totalMemoryBytes = memory.totalMem,
            availableMemoryBytes = memory.availMem,
            lowMemory = memory.lowMemory,
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                power.currentThermalStatus
            } else {
                PowerManager.THERMAL_STATUS_NONE
            },
            powerSaveMode = power.isPowerSaveMode,
            batteryPercent = battery,
        )
    }
}
