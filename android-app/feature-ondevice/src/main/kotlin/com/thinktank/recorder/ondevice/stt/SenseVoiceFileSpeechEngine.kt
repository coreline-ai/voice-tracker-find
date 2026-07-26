package com.thinktank.recorder.ondevice.stt

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.thinktank.recorder.ondevice.api.SpeechEvent
import com.thinktank.recorder.ondevice.api.SttResult
import com.thinktank.recorder.ondevice.api.TranscriptSegment
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.modelpack.ModelIntegrityVerifier
import com.thinktank.recorder.ondevice.modelpack.ModelStore
import com.thinktank.recorder.ondevice.runtime.NativeRuntimeCapabilities
import com.thinktank.recorder.ondevice.runtime.NativeWorkload
import com.thinktank.recorder.ondevice.runtime.ResourceArbiter
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Explicit state for completed-recording STT. It never consults Android SpeechRecognizer. */
enum class SenseVoiceFileSttAvailability {
    NATIVE_UNSUPPORTED,
    MODEL_NOT_INSTALLED,
    READY,
}

/**
 * SenseVoice runs only on an already-normalized 16 kHz mono signed-PCM file.
 *
 * The recognizer and every stream are closed in `finally`; this matters because the native runtime
 * is intentionally serialized with Qwen through [ResourceArbiter]. The model has no network client
 * and accepts files only from the private temporary directory created by the ViewModel.
 */
class SenseVoiceFileSpeechEngine(
    context: Context,
    private val modelStore: ModelStore = ModelStore(context.applicationContext),
    private val segmenter: Pcm16VoiceSegmenter = Pcm16VoiceSegmenter(),
) {
    private val cancelled = AtomicBoolean(false)

    fun availability(): SenseVoiceFileSttAvailability = when {
        !NativeRuntimeCapabilities.current().supported -> SenseVoiceFileSttAvailability.NATIVE_UNSUPPORTED
        !modelStore.snapshot(ModelCatalog.get(ModelId.SENSEVOICE_STT_KO)).ready ->
            SenseVoiceFileSttAvailability.MODEL_NOT_INSTALLED
        else -> SenseVoiceFileSttAvailability.READY
    }

    suspend fun transcribe(
        pcmFile: File,
        onProgress: (SpeechEvent) -> Unit = {},
    ): SttResult = withContext(Dispatchers.Default) {
        check(availability() == SenseVoiceFileSttAvailability.READY) {
            "SenseVoice 한국어 파일 STT 모델을 먼저 Wi-Fi에서 설치하세요."
        }
        check(pcmFile.isFile && pcmFile.length() >= PCM_BYTES_PER_FRAME) {
            "전사할 16 kHz PCM 파일을 찾을 수 없습니다."
        }
        check(pcmFile.length() % 2L == 0L) { "PCM 샘플 형식이 올바르지 않습니다." }

        cancelled.set(false)
        ResourceArbiter.withLease(NativeWorkload.SENSEVOICE_FILE_STT) {
            val descriptor = ModelCatalog.get(ModelId.SENSEVOICE_STT_KO)
            ModelIntegrityVerifier(modelStore).requireValid(descriptor)
            val modelDir = modelStore.installDir(descriptor.id)
            val model = File(modelDir, MODEL_FILE)
            val tokens = File(modelDir, TOKENS_FILE)
            check(model.isFile && tokens.isFile) { "SenseVoice 설치 파일을 찾을 수 없습니다." }

            onProgress(SpeechEvent.Ready)
            var recognizer: OfflineRecognizer? = null
            try {
                recognizer = OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = getFeatureConfig(SAMPLE_RATE, FEATURE_DIM),
                        modelConfig = OfflineModelConfig(
                            senseVoice = OfflineSenseVoiceModelConfig(
                                model = model.absolutePath,
                                language = "ko",
                                useInverseTextNormalization = true,
                            ),
                            tokens = tokens.absolutePath,
                            numThreads = Runtime.getRuntime().availableProcessors()
                                .coerceIn(MIN_THREADS, MAX_THREADS),
                            provider = "cpu",
                        ),
                    ),
                )
                onProgress(SpeechEvent.Listening)
                decodeSegments(checkNotNull(recognizer), pcmFile, onProgress)
            } finally {
                // `release()` is idempotent in the bundled bridge and runs even after cancellation.
                recognizer?.release()
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
    }

    fun release() = cancel()

    private suspend fun decodeSegments(
        recognizer: OfflineRecognizer,
        pcmFile: File,
        onProgress: (SpeechEvent) -> Unit,
    ): SttResult {
        val segments = mutableListOf<TranscriptSegment>()
        val transcriptParts = mutableListOf<String>()
        var sawAudioSegment = false
        segmenter.forEachSpeechSegment(pcmFile, ::ensureNotCancelled) { audio ->
            sawAudioSegment = true
            ensureNotCancelled()
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(audio.samples, SAMPLE_RATE)
                recognizer.decode(stream)
                ensureNotCancelled()
                val text = recognizer.getResult(stream).text.cleanTranscript()
                if (text.isBlank()) return@forEachSpeechSegment
                val deduplicated = deduplicateBoundary(transcriptParts.lastOrNull(), text)
                if (deduplicated.isBlank()) return@forEachSpeechSegment
                transcriptParts += deduplicated
                segments += TranscriptSegment(
                    startMs = audio.startMs,
                    endMs = audio.endMs,
                    text = deduplicated,
                )
                onProgress(SpeechEvent.Partial(transcriptParts.joinToString(separator = "\n")))
            } finally {
                stream.release()
            }
        }
        check(sawAudioSegment) { "녹음 파일에서 분석 가능한 음성을 찾지 못했습니다." }
        val text = transcriptParts.joinToString(separator = "\n").trim()
        check(text.isNotBlank()) { "녹음 파일에서 인식된 한국어 음성이 없습니다." }
        return SttResult(text = text, segments = segments)
    }

    private suspend fun ensureNotCancelled() {
        currentCoroutineContext().ensureActive()
        if (cancelled.get()) throw CancellationException("파일 전사가 취소되었습니다.")
    }

    private fun String.cleanTranscript(): String =
        replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Avoid duplicate tail text when VAD creates a cut at a short pause. */
    private fun deduplicateBoundary(previous: String?, current: String): String {
        if (previous.isNullOrBlank()) return current
        if (previous == current || previous.endsWith(current)) return ""
        val previousWords = previous.split(Regex("\\s+"))
        val currentWords = current.split(Regex("\\s+"))
        val overlap = (minOf(previousWords.size, currentWords.size) downTo 1).firstOrNull { size ->
            previousWords.takeLast(size) == currentWords.take(size)
        } ?: 0
        return currentWords.drop(overlap).joinToString(" ").trim()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val PCM_BYTES_PER_FRAME = 2
        const val MIN_THREADS = 1
        const val MAX_THREADS = 4
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
    }
}

/** A bounded-memory energy VAD for raw 16 kHz mono PCM. */
class Pcm16VoiceSegmenter(
    private val speechThreshold: Float = 0.012f,
    private val maxSegmentMs: Long = 28_000L,
    private val endSilenceMs: Long = 700L,
    private val preRollMs: Long = 300L,
    private val minSpeechMs: Long = 180L,
) {
    data class AudioSegment(
        val samples: FloatArray,
        val startMs: Long,
        val endMs: Long,
    )

    suspend fun forEachSpeechSegment(
        pcmFile: File,
        cancellationCheck: suspend () -> Unit = {},
        block: suspend (AudioSegment) -> Unit,
    ) {
        require(pcmFile.isFile && pcmFile.length() % 2L == 0L) { "PCM 파일 형식이 올바르지 않습니다." }
        val preRollFrames = (preRollMs / FRAME_MS).toInt().coerceAtLeast(1)
        val endSilenceFrames = (endSilenceMs / FRAME_MS).toInt().coerceAtLeast(1)
        val minSpeechFrames = (minSpeechMs / FRAME_MS).toInt().coerceAtLeast(1)
        val maxFrames = (maxSegmentMs / FRAME_MS).toInt().coerceAtLeast(1)
        val preRoll = ArrayDeque<FloatArray>(preRollFrames)
        var active: FloatCollector? = null
        var segmentStartFrame = 0L
        var speechFrames = 0
        var silentFrames = 0
        var frameIndex = 0L
        var emitted = false

        suspend fun emitActive(endFrameExclusive: Long) {
            val samples = active?.toArray() ?: return
            val valid = speechFrames >= minSpeechFrames && samples.isNotEmpty()
            active = null
            speechFrames = 0
            silentFrames = 0
            if (!valid) return
            block(
                AudioSegment(
                    samples = samples,
                    startMs = segmentStartFrame * FRAME_MS,
                    endMs = endFrameExclusive * FRAME_MS,
                ),
            )
            emitted = true
        }

        BufferedInputStream(FileInputStream(pcmFile)).use { source ->
            val bytes = ByteArray(FRAME_SAMPLES * 2)
            while (true) {
                cancellationCheck()
                val read = source.readAtMost(bytes)
                if (read == 0) break
                val frame = bytes.toFloatSamples(read)
                val voiced = frame.averageAbs() >= speechThreshold
                if (active == null) {
                    if (voiced) {
                        active = FloatCollector((maxFrames + preRollFrames) * FRAME_SAMPLES)
                        segmentStartFrame = (frameIndex - preRoll.size).coerceAtLeast(0L)
                        preRoll.forEach { active?.append(it) }
                        active?.append(frame)
                        speechFrames = 1
                        silentFrames = 0
                    } else {
                        preRoll.addLast(frame)
                        while (preRoll.size > preRollFrames) preRoll.removeFirst()
                    }
                } else {
                    active?.append(frame)
                    if (voiced) {
                        speechFrames += 1
                        silentFrames = 0
                    } else {
                        silentFrames += 1
                    }
                    val activeFrames = active!!.size / FRAME_SAMPLES
                    if (silentFrames >= endSilenceFrames || activeFrames >= maxFrames) {
                        emitActive(frameIndex + 1)
                        preRoll.clear()
                        preRoll.addLast(frame)
                    }
                }
                frameIndex += 1
            }
        }
        if (active != null) emitActive(frameIndex)
        if (!emitted && pcmFile.length() > 0L) {
            // Very quiet recordings should still reach SenseVoice instead of being silently discarded.
            forEachFixedChunk(pcmFile, cancellationCheck, block)
        }
    }

    private suspend fun forEachFixedChunk(
        pcmFile: File,
        cancellationCheck: suspend () -> Unit,
        block: suspend (AudioSegment) -> Unit,
    ) {
        val maxSamples = (maxSegmentMs * SAMPLE_RATE / 1_000L).toInt()
        BufferedInputStream(FileInputStream(pcmFile)).use { source ->
            var consumedSamples = 0L
            while (true) {
                cancellationCheck()
                val bytes = ByteArray(maxSamples * 2)
                val read = source.readAtMost(bytes)
                if (read == 0) return
                val samples = bytes.toFloatSamples(read)
                block(
                    AudioSegment(
                        samples = samples,
                        startMs = consumedSamples * 1_000L / SAMPLE_RATE,
                        endMs = (consumedSamples + samples.size) * 1_000L / SAMPLE_RATE,
                    ),
                )
                consumedSamples += samples.size
            }
        }
    }

    private fun BufferedInputStream.readAtMost(target: ByteArray): Int {
        var total = 0
        while (total < target.size) {
            val read = read(target, total, target.size - total)
            if (read < 0) break
            total += read
        }
        // Drop a trailing incomplete 16-bit sample rather than decoding corrupted data.
        return total - total % 2
    }

    private fun ByteArray.toFloatSamples(length: Int): FloatArray {
        val samples = FloatArray(length / 2)
        var byteIndex = 0
        for (index in samples.indices) {
            val low = this[byteIndex].toInt() and 0xff
            val high = this[byteIndex + 1].toInt()
            samples[index] = ((high shl 8) or low).toShort() / 32_768f
            byteIndex += 2
        }
        return samples
    }

    private fun FloatArray.averageAbs(): Float {
        if (isEmpty()) return 0f
        var total = 0f
        forEach { total += kotlin.math.abs(it) }
        return total / size
    }

    private class FloatCollector(initialCapacity: Int) {
        private var buffer = FloatArray(initialCapacity.coerceAtLeast(FRAME_SAMPLES))
        var size: Int = 0
            private set

        fun append(values: FloatArray) {
            ensureCapacity(size + values.size)
            values.copyInto(buffer, destinationOffset = size)
            size += values.size
        }

        fun toArray(): FloatArray = buffer.copyOf(size)

        private fun ensureCapacity(required: Int) {
            if (required <= buffer.size) return
            buffer = buffer.copyOf(maxOf(required, buffer.size * 2))
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000L
        const val FRAME_MS = 20L
        const val FRAME_SAMPLES = 320
    }
}
