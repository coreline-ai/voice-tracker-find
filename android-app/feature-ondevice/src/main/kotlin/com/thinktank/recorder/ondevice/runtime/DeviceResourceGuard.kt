package com.thinktank.recorder.ondevice.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

class DeviceResourceGuard(context: Context) {
    private val applicationContext = context.applicationContext

    fun requireGemmaCapacity() {
        val activityManager = applicationContext.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val power = applicationContext.getSystemService(PowerManager::class.java)
        val battery = applicationContext.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power.currentThermalStatus
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

        check(memory.totalMem >= MIN_TOTAL_MEMORY_BYTES) {
            "Gemma 3 1B 실행에는 RAM 3GB 이상이 필요합니다."
        }
        check(!memory.lowMemory && memory.availMem >= MIN_AVAILABLE_MEMORY_BYTES) {
            "Gemma 3 1B 실행에 필요한 여유 메모리가 부족합니다."
        }
        check(thermalStatus < PowerManager.THERMAL_STATUS_SEVERE) {
            "기기가 과열되어 Gemma 요약을 시작하지 않았습니다. 식힌 뒤 다시 시도하세요."
        }
        check(!(power.isPowerSaveMode && battery != null && battery <= 15)) {
            "배터리가 부족하고 절전 모드가 켜져 있어 Gemma 요약을 시작하지 않았습니다."
        }
    }

    private companion object {
        const val MIN_TOTAL_MEMORY_BYTES = 3L * 1024 * 1024 * 1024
        const val MIN_AVAILABLE_MEMORY_BYTES = 900L * 1024 * 1024
    }
}
