package com.coreline.ai.voice.ondevice.stt

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16WavReaderTest {
    @Test
    fun inspectsAndStreamsPcm16MonoWav() {
        val samples = shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE, 1_024)
        val file = createWav(samples, sampleRate = 16_000, channels = 1)
        try {
            val info = Pcm16WavReader.inspect(file)
            val decoded = mutableListOf<Float>()
            Pcm16WavReader.forEachFloatChunk(file, info, chunkSamples = 2) { chunk, _ ->
                decoded += chunk.toList()
            }

            assertEquals(16_000, info.sampleRate)
            assertEquals(8L, info.dataBytes)
            assertEquals(samples.size, decoded.size)
            assertTrue(decoded[1] > 0.99f)
            assertEquals(-1f, decoded[2])
        } finally {
            file.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsStereoWav() {
        val file = createWav(shortArrayOf(0, 0), sampleRate = 16_000, channels = 2)
        try {
            Pcm16WavReader.inspect(file)
        } finally {
            file.delete()
        }
    }

    private fun createWav(samples: ShortArray, sampleRate: Int, channels: Int): File {
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(dataBytes + 36)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * 2)
            putShort((channels * 2).toShort())
            putShort(16)
            put("data".toByteArray())
            putInt(dataBytes)
        }.array()
        return File.createTempFile("pcm16-", ".wav").apply {
            FileOutputStream(this).use { output ->
                output.write(header)
                val pcm = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                samples.forEach(pcm::putShort)
                output.write(pcm.array())
            }
        }
    }
}
