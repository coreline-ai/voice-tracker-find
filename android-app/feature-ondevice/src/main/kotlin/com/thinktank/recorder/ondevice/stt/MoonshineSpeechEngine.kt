package com.thinktank.recorder.ondevice.stt

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.thinktank.recorder.ondevice.api.FileSttEngine
import com.thinktank.recorder.ondevice.api.SttResult
import com.thinktank.recorder.ondevice.api.TranscriptSegment
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.modelpack.ModelIntegrityVerifier
import com.thinktank.recorder.ondevice.modelpack.ModelStore
import com.thinktank.recorder.ondevice.runtime.NativeWorkload
import com.thinktank.recorder.ondevice.runtime.ResourceArbiter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class MoonshineSpeechEngine(
    private val modelStore: ModelStore,
) : FileSttEngine {
    private val cancelled = AtomicBoolean(false)
    private val integrityVerifier = ModelIntegrityVerifier(modelStore)

    override suspend fun transcribe(
        audioFile: File,
        onProgress: (Float) -> Unit,
    ): SttResult = ResourceArbiter.withLease(NativeWorkload.MOONSHINE_STT) {
        withContext(Dispatchers.IO) {
            cancelled.set(false)
            val descriptor = ModelCatalog.get(ModelId.MOONSHINE_KO)
            check(modelStore.snapshot(descriptor).ready) {
                "Moonshine 한국어 모델이 설치되지 않았습니다"
            }
            integrityVerifier.requireValid(descriptor)
            val modelDir = modelStore.installDir(ModelId.MOONSHINE_KO)
            val wavInfo = Pcm16WavReader.inspect(audioFile)
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(
                        encoder = File(modelDir, "encoder_model.ort").absolutePath,
                        mergedDecoder = File(modelDir, "decoder_model_merged.ort").absolutePath,
                    ),
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    numThreads = recommendedThreads(),
                    debug = false,
                    provider = "cpu",
                ),
            )

            var recognizer: OfflineRecognizer? = null
            var stream: com.k2fsa.sherpa.onnx.OfflineStream? = null
            try {
                ensureNotCancelled()
                onProgress(0.02f)
                recognizer = OfflineRecognizer(config = config)
                stream = recognizer.createStream()
                Pcm16WavReader.forEachFloatChunk(audioFile, wavInfo) { samples, consumed ->
                    coroutineContext.ensureActive()
                    ensureNotCancelled()
                    stream.acceptWaveform(samples, wavInfo.sampleRate)
                    val inputProgress = consumed.toDouble() / wavInfo.dataBytes.toDouble()
                    onProgress((0.05 + inputProgress * 0.25).toFloat())
                }
                ensureNotCancelled()
                onProgress(0.32f)
                recognizer.decode(stream)
                ensureNotCancelled()
                onProgress(0.95f)
                val text = recognizer.getResult(stream).text.trim()
                check(text.isNotBlank()) { "음성을 인식하지 못했습니다" }
                onProgress(1f)
                SttResult(
                    text = text,
                    segments = listOf(
                        TranscriptSegment(
                            startMs = 0,
                            endMs = wavInfo.durationMs,
                            text = text,
                        ),
                    ),
                )
            } finally {
                stream?.release()
                recognizer?.release()
            }
        }
    }

    override fun cancel() {
        cancelled.set(true)
    }

    override fun release() {
        cancelled.set(true)
    }

    private fun ensureNotCancelled() {
        check(!cancelled.get()) { "Moonshine 전사가 취소되었습니다" }
    }

    private fun recommendedThreads(): Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
}
