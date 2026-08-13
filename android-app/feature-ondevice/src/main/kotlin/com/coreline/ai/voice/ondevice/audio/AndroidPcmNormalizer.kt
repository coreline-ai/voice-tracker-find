package com.coreline.ai.voice.ondevice.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.AudioFormat
import com.coreline.ai.voice.ondevice.stt.Pcm16WavReader
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class NormalizedPcm(
    val file: File,
    val durationMs: Long,
    val sampleCount: Long,
)

/** Converts a finalized main-recorder source into raw signed PCM for the system recognizer. */
class AndroidPcmNormalizer {
    suspend fun normalize(
        source: File,
        destination: File,
        onProgress: (Float) -> Unit = {},
    ): NormalizedPcm = withContext(Dispatchers.IO) {
        require(source.isFile && source.length() > 0L) { "분석할 원본 파일을 찾을 수 없습니다." }
        require(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "PCM 임시 저장소를 만들 수 없습니다."
        }
        if (destination.exists() && !destination.delete()) {
            error("기존 PCM 임시 파일을 정리하지 못했습니다.")
        }
        try {
            val result = if (source.extension.equals("wav", ignoreCase = true)) {
                copyCompatibleWav(source, destination, onProgress)
                    ?: decodeWithMediaCodec(source, destination, onProgress)
            } else {
                decodeWithMediaCodec(source, destination, onProgress)
            }
            check(result.sampleCount > 0L && destination.length() > 0L) { "PCM 변환 결과가 비어 있습니다." }
            result
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    /** Returns null when the WAV needs MediaCodec decoding/resampling instead of a direct copy. */
    private suspend fun copyCompatibleWav(
        source: File,
        destination: File,
        onProgress: (Float) -> Unit,
    ): NormalizedPcm? {
        val info = runCatching { Pcm16WavReader.inspect(source) }.getOrNull() ?: return null
        require(info.durationMs <= MAX_SOURCE_DURATION_MS) {
            "${MAX_SOURCE_DURATION_MS / 60_000}분을 초과하는 녹음은 파일 전사할 수 없습니다."
        }
        var copied = 0L
        RandomAccessFile(source, "r").use { input ->
            input.seek(info.dataOffset)
            BufferedOutputStream(FileOutputStream(destination)).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                var remaining = info.dataBytes
                while (remaining > 0L) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(count > 0) { "WAV PCM 데이터를 끝까지 읽지 못했습니다." }
                    output.write(buffer, 0, count)
                    copied += count
                    remaining -= count
                    onProgress((copied.toDouble() / info.dataBytes).toFloat())
                }
            }
        }
        return NormalizedPcm(
            file = destination,
            durationMs = info.durationMs,
            sampleCount = copied / PCM_BYTES_PER_SAMPLE,
        )
    }

    private suspend fun decodeWithMediaCodec(
        source: File,
        destination: File,
        onProgress: (Float) -> Unit,
    ): NormalizedPcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("지원되는 오디오 트랙을 찾을 수 없습니다.")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
            inputFormat.longOrNull(MediaFormat.KEY_DURATION)?.let { durationUs ->
                require(durationUs <= MAX_SOURCE_DURATION_MS * 1_000L) {
                    "${MAX_SOURCE_DURATION_MS / 60_000}분을 초과하는 녹음은 파일 전사할 수 없습니다."
                }
            }
            extractor.selectTrack(trackIndex)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var sampleRate = inputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)
                ?: error("원본 샘플레이트를 읽지 못했습니다.")
            var channels = inputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            var resampler: Pcm16MonoResampler? = null
            var writtenSamples = 0L
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var inputBytes = 0L
            val sourceBytes = source.length().coerceAtLeast(1L)

            BufferedPcmWriter(destination).use { writer ->
                fun ensureResampler(): Pcm16MonoResampler = resampler ?: Pcm16MonoResampler(
                    inputRate = sampleRate,
                    outputRate = TARGET_SAMPLE_RATE,
                    emit = { sample ->
                        writer.write(sample)
                        writtenSamples += 1
                        check(writtenSamples <= MAX_NORMALIZED_SAMPLES) {
                            "${MAX_SOURCE_DURATION_MS / 60_000}분을 초과하는 녹음은 파일 전사할 수 없습니다."
                        }
                    },
                ).also { resampler = it }

                while (!outputEnded) {
                    coroutineContext.ensureActive()
                    if (!inputEnded) {
                        val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = checkNotNull(decoder.getInputBuffer(inputIndex))
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime.coerceAtLeast(0L),
                                    0,
                                )
                                extractor.advance()
                                inputBytes += sampleSize
                                onProgress((inputBytes.toDouble() / sourceBytes).toFloat().coerceAtMost(0.95f))
                            }
                        }
                    }

                    when (val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(resampler == null) { "오디오 형식이 전사 도중 변경되었습니다." }
                            val outputFormat = decoder.outputFormat
                            sampleRate = outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                            channels = outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                            val encoding = outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)
                            check(
                                encoding == null || encoding == AudioFormat.ENCODING_PCM_16BIT,
                            ) { "16-bit PCM으로 변환할 수 없는 오디오 형식입니다." }
                            check(sampleRate > 0 && channels in 1..8) { "지원되지 않는 PCM 출력 형식입니다." }
                        }
                        else -> if (outputIndex >= 0) {
                            if (info.size > 0) {
                                val outputBuffer = checkNotNull(decoder.getOutputBuffer(outputIndex))
                                val bytes = ByteArray(info.size)
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                outputBuffer.get(bytes)
                                ensureResampler().acceptInterleavedPcm16(bytes, channels)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
                resampler?.finish()
            }
            onProgress(1f)
            return NormalizedPcm(
                file = destination,
                durationMs = writtenSamples * 1_000L / TARGET_SAMPLE_RATE,
                sampleCount = writtenSamples,
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null

    private companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val PCM_BYTES_PER_SAMPLE = 2L
        const val CODEC_TIMEOUT_US = 10_000L
        const val MAX_SOURCE_DURATION_MS = 2L * 60L * 60L * 1_000L
        const val MAX_NORMALIZED_SAMPLES = TARGET_SAMPLE_RATE * 2L * 60L * 60L
    }
}

/** Streaming linear resampler that never holds a whole recording in memory. */
internal class Pcm16MonoResampler(
    private val inputRate: Int,
    private val outputRate: Int,
    private val emit: (Short) -> Unit,
) {
    private var previous: Short? = null
    private var inputFrames = 0L
    private var outputFrames = 0L

    init {
        require(inputRate > 0 && outputRate > 0)
    }

    fun acceptInterleavedPcm16(bytes: ByteArray, channels: Int) {
        require(channels > 0)
        val frameBytes = channels * 2
        require(bytes.size % frameBytes == 0) { "PCM frame 크기가 채널 수와 맞지 않습니다." }
        var offset = 0
        while (offset < bytes.size) {
            var sum = 0
            repeat(channels) { channel ->
                val index = offset + channel * 2
                val sample = ((bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8))
                    .toShort()
                    .toInt()
                sum += sample
            }
            acceptMono((sum / channels).toShort())
            offset += frameBytes
        }
    }

    private fun acceptMono(current: Short) {
        val currentIndex = inputFrames
        previous?.let { prior ->
            while (sourceIndexFor(outputFrames) <= currentIndex - 1L) {
                emit(interpolate(prior, current, outputFrames))
                outputFrames += 1
            }
        }
        previous = current
        inputFrames += 1
    }

    fun finish() {
        val last = previous ?: return
        while (sourceIndexFor(outputFrames) < inputFrames) {
            emit(last)
            outputFrames += 1
        }
    }

    private fun sourceIndexFor(outputIndex: Long): Long =
        outputIndex * inputRate / outputRate

    private fun interpolate(first: Short, second: Short, outputIndex: Long): Short {
        val numerator = outputIndex * inputRate % outputRate
        if (numerator == 0L) return first
        val ratio = numerator.toDouble() / outputRate
        return (first + (second - first) * ratio).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}

private class BufferedPcmWriter(file: File) : AutoCloseable {
    private val output = BufferedOutputStream(FileOutputStream(file))
    private val buffer = ByteArray(8_192)
    private var count = 0

    fun write(sample: Short) {
        if (count + 2 > buffer.size) flushBuffer()
        val value = sample.toInt()
        buffer[count++] = (value and 0xff).toByte()
        buffer[count++] = ((value ushr 8) and 0xff).toByte()
    }

    override fun close() {
        flushBuffer()
        output.close()
    }

    private fun flushBuffer() {
        if (count == 0) return
        output.write(buffer, 0, count)
        count = 0
    }
}
