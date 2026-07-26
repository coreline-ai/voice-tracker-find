package com.thinktank.recorder.next.ondevice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in device-QA utility: selects the product's Qwen mode without touching recordings. */
@RunWith(AndroidJUnit4::class)
class DeviceQaQwenSelectionTest {
    @Test
    fun selectsQwenForTheNextProductAnalysis() {
        assumeTrue(
            "명시적 Qwen 선택 QA에서만 실행합니다.",
            InstrumentationRegistry.getArguments().getString(ARG_SELECT_QWEN) == "true",
        )
        val preferences = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences(PREFERENCES, 0)

        assertEquals(
            true,
            preferences.edit().putString(KEY_SUMMARY, SummaryEngineType.QWEN_LOCAL.name).commit(),
        )
        assertEquals(SummaryEngineType.QWEN_LOCAL.name, preferences.getString(KEY_SUMMARY, null))
    }

    private companion object {
        const val ARG_SELECT_QWEN = "selectQwen"
        const val PREFERENCES = "ondevice-settings"
        const val KEY_SUMMARY = "summary-engine"
    }
}
