package com.coreline.ai.voice.ondevice.stt

import java.io.File
import java.io.RandomAccessFile

data class Pcm16WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val dataOffset: Long,
    val dataBytes: Long,
) {
    val durationMs: Long
        get() = dataBytes * 1_000L / (sampleRate * channels * 2L)
}

object Pcm16WavReader {
    fun inspect(file: File): Pcm16WavInfo {
        require(file.isFile) { "WAV 파일을 찾을 수 없습니다" }
        RandomAccessFile(file, "r").use { input ->
            require(input.readAscii(4) == "RIFF") { "RIFF WAV 파일이 아닙니다" }
            input.readUInt32Le()
            require(input.readAscii(4) == "WAVE") { "WAVE 헤더가 올바르지 않습니다" }

            var format: Int? = null
            var channels: Int? = null
            var sampleRate: Int? = null
            var bitsPerSample: Int? = null
            var dataOffset: Long? = null
            var dataBytes: Long? = null
            while (input.filePointer + 8 <= input.length()) {
                val chunkId = input.readAscii(4)
                val chunkSize = input.readUInt32Le()
                val chunkStart = input.filePointer
                val chunkEnd = chunkStart + chunkSize
                require(chunkEnd <= input.length()) { "손상된 WAV chunk입니다" }
                when (chunkId) {
                    "fmt " -> {
                        require(chunkSize >= 16) { "WAV fmt chunk가 너무 짧습니다" }
                        format = input.readUInt16Le()
                        channels = input.readUInt16Le()
                        sampleRate = input.readUInt32Le().toInt()
                        input.readUInt32Le()
                        input.readUInt16Le()
                        bitsPerSample = input.readUInt16Le()
                    }
                    "data" -> {
                        dataOffset = chunkStart
                        dataBytes = chunkSize
                    }
                }
                input.seek(chunkEnd + (chunkSize and 1L))
                if (format != null && dataOffset != null) break
            }

            require(format == 1) { "PCM WAV만 지원합니다" }
            require(channels == 1) { "mono WAV만 지원합니다" }
            require(sampleRate == 16_000) { "16kHz WAV만 지원합니다" }
            require(bitsPerSample == 16) { "16-bit WAV만 지원합니다" }
            val length = requireNotNull(dataBytes) { "WAV 오디오 데이터가 없습니다" }
            require(length > 0 && length % 2L == 0L) { "WAV 오디오 데이터가 비어 있거나 손상되었습니다" }
            return Pcm16WavInfo(
                sampleRate = requireNotNull(sampleRate),
                channels = requireNotNull(channels),
                dataOffset = requireNotNull(dataOffset),
                dataBytes = length,
            )
        }
    }

    fun forEachFloatChunk(
        file: File,
        info: Pcm16WavInfo,
        chunkSamples: Int = 16_000,
        block: (samples: FloatArray, consumedBytes: Long) -> Unit,
    ) {
        require(chunkSamples > 0)
        RandomAccessFile(file, "r").use { input ->
            input.seek(info.dataOffset)
            var remaining = info.dataBytes
            var consumed = 0L
            val bytes = ByteArray(chunkSamples * 2)
            while (remaining > 0) {
                val requested = minOf(bytes.size.toLong(), remaining).toInt()
                input.readFully(bytes, 0, requested)
                val samples = FloatArray(requested / 2)
                var source = 0
                for (index in samples.indices) {
                    val lo = bytes[source].toInt() and 0xff
                    val hi = bytes[source + 1].toInt()
                    samples[index] = ((hi shl 8) or lo).toShort() / 32_768f
                    source += 2
                }
                consumed += requested
                remaining -= requested
                block(samples, consumed)
            }
        }
    }

    private fun RandomAccessFile.readAscii(size: Int): String {
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readUInt16Le(): Int {
        val b0 = read()
        val b1 = read()
        require(b0 >= 0 && b1 >= 0) { "WAV 헤더가 잘렸습니다" }
        return b0 or (b1 shl 8)
    }

    private fun RandomAccessFile.readUInt32Le(): Long {
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        require(b0 >= 0 && b1 >= 0 && b2 >= 0 && b3 >= 0) { "WAV 헤더가 잘렸습니다" }
        return (b0.toLong() or
            (b1.toLong() shl 8) or
            (b2.toLong() shl 16) or
            (b3.toLong() shl 24)) and 0xffff_ffffL
    }
}
