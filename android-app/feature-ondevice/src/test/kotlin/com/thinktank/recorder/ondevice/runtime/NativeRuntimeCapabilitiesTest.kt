package com.thinktank.recorder.ondevice.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRuntimeCapabilitiesTest {
    @Test
    fun arm64BitProcessSupportsNativeAi() {
        assertTrue(
            NativeRuntimeCapabilities.evaluate(setOf("arm64-v8a"), processIs64Bit = true).supported,
        )
    }

    @Test
    fun nonArm64Or32BitProcessKeepsNativeAiDisabled() {
        assertFalse(
            NativeRuntimeCapabilities.evaluate(setOf("x86_64"), processIs64Bit = true).supported,
        )
        assertFalse(
            NativeRuntimeCapabilities.evaluate(setOf("arm64-v8a"), processIs64Bit = false).supported,
        )
    }
}
