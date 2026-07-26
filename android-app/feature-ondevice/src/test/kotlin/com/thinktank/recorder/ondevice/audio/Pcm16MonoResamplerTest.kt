package com.thinktank.recorder.ondevice.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16MonoResamplerTest {
    @Test
    fun keepsSamplesAtSameRate() {
        assertEquals(
            listOf<Short>(10, 20, 30),
            resample(inputRate = 16_000, outputRate = 16_000, samples = shortArrayOf(10, 20, 30)),
        )
    }

    @Test
    fun downsamplesWithoutHoldingWholeInput() {
        assertEquals(
            listOf<Short>(0, 20),
            resample(inputRate = 4, outputRate = 2, samples = shortArrayOf(0, 10, 20, 30)),
        )
    }

    @Test
    fun upsamplesWithLinearInterpolationAndLastSampleClamp() {
        assertEquals(
            listOf<Short>(0, 50, 100, 100),
            resample(inputRate = 2, outputRate = 4, samples = shortArrayOf(0, 100)),
        )
    }

    private fun resample(inputRate: Int, outputRate: Int, samples: ShortArray): List<Short> {
        val output = mutableListOf<Short>()
        val resampler = Pcm16MonoResampler(inputRate, outputRate, output::add)
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xff).toByte()
            bytes[index * 2 + 1] = ((sample.toInt() ushr 8) and 0xff).toByte()
        }
        resampler.acceptInterleavedPcm16(bytes, channels = 1)
        resampler.finish()
        return output
    }
}
