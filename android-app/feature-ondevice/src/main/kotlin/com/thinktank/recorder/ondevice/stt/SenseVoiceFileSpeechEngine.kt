package com.thinktank.recorder.ondevice.stt

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.thinktank.recorder.ondevice.api.SpeechEvent
import com.thinktank.recorder.ondevice.api.SttDiagnostics
import com.thinktank.recorder.ondevice.api.SttQualityStatus
import com.thinktank.recorder.ondevice.api.SttResult
import com.thinktank.recorder.ondevice.api.SttSegmentDiagnostic
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
 * is intentionally serialized with model maintenance through [ResourceArbiter]. The model has no
 * network client and accepts files only from the private temporary directory created by the ViewModel.
 */
class SenseVoiceFileSpeechEngine(
    context: Context,
    private val modelStore: ModelStore = ModelStore(context.applicationContext),
    private val segmenter: Pcm16VoiceSegmenter = Pcm16VoiceSegmenter(),
) {
    private val cancelled = AtomicBoolean(false)
    private val qualityEvaluator = FileSttQualityEvaluator()

    fun availability(): SenseVoiceFileSttAvailability = when {
        !NativeRuntimeCapabilities.current().supported -> SenseVoiceFileSttAvailability.NATIVE_UNSUPPORTED
        !modelStore.snapshot(ModelCatalog.get(ModelId.SENSEVOICE_STT_KO)).ready ->
            SenseVoiceFileSttAvailability.MODEL_NOT_INSTALLED
        else -> SenseVoiceFileSttAvailability.READY
    }

    suspend fun transcribe(
        pcmFile: File,
        resumeState: SttResumeState = SttResumeState(),
        allowFullRetry: Boolean = true,
        onSegmentCompleted: suspend (SttSegmentCheckpoint) -> Unit = {},
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
                val first = decodeSegments(
                    recognizer = checkNotNull(recognizer),
                    pcmFile = pcmFile,
                    fixedChunkPass = false,
                    onProgress = onProgress,
                    resumeState = resumeState,
                    onSegmentCompleted = onSegmentCompleted,
                )
                val firstDiagnostics = qualityEvaluator.evaluate(first, retryCount = 0)
                if (firstDiagnostics.passed) {
                    first.toResult(firstDiagnostics)
                } else {
                    ensureNotCancelled()
                    val retryRanges = SttRetryPlanner.plan(
                        diagnostics = first.segmentDiagnostics,
                        inputDurationMs = first.inputDurationMs,
                    )
                    val targeted = if (retryRanges.isEmpty()) {
                        null
                    } else {
                        onProgress(SpeechEvent.Retrying)
                        val retried = decodeSegments(
                            recognizer = checkNotNull(recognizer),
                            pcmFile = pcmFile,
                            fixedChunkPass = true,
                            fixedRanges = retryRanges,
                            onProgress = onProgress,
                            resumeState = SttResumeState(),
                            onSegmentCompleted = onSegmentCompleted,
                        )
                        mergeTargetedRetry(first, retried)
                    }
                    val targetedDiagnostics = targeted?.let {
                        qualityEvaluator.evaluate(it, retryCount = 1)
                    }
                    when {
                        targeted != null && targetedDiagnostics?.passed == true ->
                            targeted.toResult(targetedDiagnostics)
                        !allowFullRetry ->
                            (targeted ?: first).toResult(
                                (targetedDiagnostics ?: firstDiagnostics).copy(
                                    retryCount = if (targeted == null) 0 else 1,
                                    qualityStatus = SttQualityStatus.INSUFFICIENT,
                                ),
                            )
                        else -> {
                            onProgress(SpeechEvent.Retrying)
                            val retry = decodeSegments(
                                recognizer = checkNotNull(recognizer),
                                pcmFile = pcmFile,
                                fixedChunkPass = true,
                                onProgress = onProgress,
                                resumeState = SttResumeState(),
                                onSegmentCompleted = onSegmentCompleted,
                            )
                            val retryDiagnostics = qualityEvaluator.evaluate(retry, retryCount = 1)
                            if (
                                retryDiagnostics.passed ||
                                retry.meaningfulChars >= (targeted ?: first).meaningfulChars
                            ) {
                                retry.toResult(retryDiagnostics)
                            } else {
                                (targeted ?: first).toResult(
                                    (targetedDiagnostics ?: firstDiagnostics).copy(
                                        retryCount = 1,
                                        qualityStatus = SttQualityStatus.INSUFFICIENT,
                                    ),
                                )
                            }
                        }
                    }
                }
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
        fixedChunkPass: Boolean,
        fixedRanges: List<SttRetryRange> = emptyList(),
        onProgress: (SpeechEvent) -> Unit,
        resumeState: SttResumeState,
        onSegmentCompleted: suspend (SttSegmentCheckpoint) -> Unit,
    ): FileSttPass {
        val segments = resumeState.segments.toMutableList()
        val segmentDiagnostics = resumeState.diagnostics.toMutableList()
        val transcriptParts = resumeState.segments.map(TranscriptSegment::text).toMutableList()
        var sourceSegmentCount = 0
        var recognizedSegmentCount = resumeState.diagnostics.count { it.meaningfulChars > 0 }
        var processedThroughMs = resumeState.processedThroughMs
        val decode: suspend (Pcm16VoiceSegmenter.AudioSegment) -> Unit = decode@ { audio ->
            val ordinal = sourceSegmentCount
            sourceSegmentCount += 1
            processedThroughMs = maxOf(processedThroughMs, audio.endMs)
            ensureNotCancelled()
            if (!fixedChunkPass && audio.endMs <= resumeState.processedThroughMs) {
                return@decode
            }
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(audio.samples, SAMPLE_RATE)
                recognizer.decode(stream)
                ensureNotCancelled()
                val text = recognizer.getResult(stream).text.cleanTranscript()
                val rawMeaningfulChars = text.meaningfulCharacterCount()
                val deduplicated = if (text.isBlank()) {
                    ""
                } else {
                    deduplicateTranscriptBoundary(transcriptParts.lastOrNull(), text)
                }
                segmentDiagnostics += SttSegmentDiagnostic(
                    startMs = audio.startMs,
                    endMs = audio.endMs,
                    meaningfulChars = rawMeaningfulChars,
                )
                if (rawMeaningfulChars > 0) {
                    recognizedSegmentCount += 1
                }
                if (deduplicated.isNotBlank()) {
                    transcriptParts += deduplicated
                    segments += TranscriptSegment(
                        startMs = audio.startMs,
                        endMs = audio.endMs,
                        text = deduplicated,
                    )
                    onProgress(SpeechEvent.Partial(transcriptParts.joinToString(separator = "\n")))
                }
                onSegmentCompleted(
                    SttSegmentCheckpoint(
                        passType = when {
                            fixedRanges.isNotEmpty() -> "RANGE_RETRY"
                            fixedChunkPass -> "FIXED_RETRY"
                            else -> "PRIMARY"
                        },
                        ordinal = ordinal,
                        startMs = audio.startMs,
                        endMs = audio.endMs,
                        text = deduplicated,
                    ),
                )
            } finally {
                stream.release()
            }
        }
        if (fixedChunkPass && fixedRanges.isNotEmpty()) {
            segmenter.forEachFixedRange(
                pcmFile = pcmFile,
                ranges = fixedRanges,
                cancellationCheck = ::ensureNotCancelled,
                block = decode,
            )
        } else if (fixedChunkPass) {
            segmenter.forEachFixedSegment(pcmFile, ::ensureNotCancelled, decode)
        } else {
            segmenter.forEachSpeechSegment(pcmFile, ::ensureNotCancelled, decode)
        }
        if (!fixedChunkPass) {
            // The VAD iterator scans the complete PCM even when the last speech ends before EOF.
            // Coverage measures bytes scanned, not the timestamp of the final voiced segment.
            processedThroughMs = pcmFile.length() / PCM_BYTES_PER_FRAME * 1_000L / SAMPLE_RATE
        }
        val text = transcriptParts.joinToString(separator = "\n").trim()
        return FileSttPass(
            text = text,
            segments = segments,
            inputDurationMs = pcmFile.length() / PCM_BYTES_PER_FRAME * 1_000L / SAMPLE_RATE,
            processedThroughMs = processedThroughMs,
            sourceSegmentCount = sourceSegmentCount,
            recognizedSegmentCount = recognizedSegmentCount,
            fixedChunkPass = fixedChunkPass,
            segmentDiagnostics = segmentDiagnostics,
        )
    }

    private suspend fun ensureNotCancelled() {
        currentCoroutineContext().ensureActive()
        if (cancelled.get()) throw CancellationException("파일 전사가 취소되었습니다.")
    }

    private fun String.cleanTranscript(): String =
        replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val PCM_BYTES_PER_FRAME = 2L
        const val MIN_THREADS = 1
        const val MAX_THREADS = 4
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
    }
}

data class SttResumeState(
    val processedThroughMs: Long = 0L,
    val segments: List<TranscriptSegment> = emptyList(),
    val diagnostics: List<SttSegmentDiagnostic> = emptyList(),
)

data class SttSegmentCheckpoint(
    val passType: String,
    val ordinal: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class SttRetryRange(
    val startMs: Long,
    val endMs: Long,
)

internal object SttRetryPlanner {
    fun plan(
        diagnostics: List<SttSegmentDiagnostic>,
        inputDurationMs: Long,
    ): List<SttRetryRange> {
        val failed = diagnostics
            .filter { it.meaningfulChars == 0 && it.endMs > it.startMs }
            .sortedBy(SttSegmentDiagnostic::startMs)
        if (failed.isEmpty()) return emptyList()
        val merged = mutableListOf<SttRetryRange>()
        failed.forEach { segment ->
            val candidate = SttRetryRange(
                startMs = segment.startMs.coerceAtLeast(0L),
                endMs = segment.endMs.coerceAtMost(inputDurationMs),
            )
            val previous = merged.lastOrNull()
            if (previous != null && candidate.startMs <= previous.endMs) {
                merged[merged.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, candidate.endMs))
            } else if (candidate.endMs > candidate.startMs) {
                merged += candidate
            }
        }
        return merged
    }
}

internal data class FileSttPass(
    val text: String,
    val segments: List<TranscriptSegment>,
    val inputDurationMs: Long,
    val processedThroughMs: Long,
    val sourceSegmentCount: Int,
    val recognizedSegmentCount: Int,
    val fixedChunkPass: Boolean,
    val segmentDiagnostics: List<SttSegmentDiagnostic> = emptyList(),
) {
    val meaningfulChars: Int
        get() = text.meaningfulCharacterCount()

    fun toResult(diagnostics: SttDiagnostics): SttResult =
        SttResult(
            text = text,
            segments = segments,
            diagnostics = diagnostics,
        )
}

internal class FileSttQualityEvaluator {
    fun evaluate(pass: FileSttPass, retryCount: Int): SttDiagnostics {
        val meaningfulChars = pass.meaningfulChars
        val durationSeconds = (pass.inputDurationMs / 1_000f).coerceAtLeast(1f)
        val charsPerSecond = meaningfulChars / durationSeconds
        val reachedInputEnd =
            pass.processedThroughMs + COVERAGE_TOLERANCE_MS >= pass.inputDurationMs
        val recognizedAllVadSegments =
            pass.fixedChunkPass ||
                (
                    pass.sourceSegmentCount > 0 &&
                        pass.recognizedSegmentCount == pass.sourceSegmentCount
                    )
        val denseEnough =
            pass.inputDurationMs < DENSITY_CHECK_MIN_DURATION_MS ||
                charsPerSecond >= MIN_MEANINGFUL_CHARS_PER_SECOND
        val passed =
            meaningfulChars >= MIN_MEANINGFUL_CHARS &&
                reachedInputEnd &&
                recognizedAllVadSegments &&
                denseEnough
        return SttDiagnostics(
            inputDurationMs = pass.inputDurationMs,
            processedThroughMs = pass.processedThroughMs,
            segmentCount = pass.sourceSegmentCount,
            recognizedSegmentCount = pass.recognizedSegmentCount,
            retryCount = retryCount,
            meaningfulChars = meaningfulChars,
            charsPerSecond = charsPerSecond,
            qualityStatus = when {
                !passed -> SttQualityStatus.INSUFFICIENT
                retryCount > 0 -> SttQualityStatus.RETRIED_COMPLETE
                else -> SttQualityStatus.COMPLETE
            },
            segments = pass.segmentDiagnostics,
        )
    }

    private companion object {
        const val COVERAGE_TOLERANCE_MS = 40L
        const val DENSITY_CHECK_MIN_DURATION_MS = 10_000L
        const val MIN_MEANINGFUL_CHARS = 2
        const val MIN_MEANINGFUL_CHARS_PER_SECOND = 0.5f
    }
}

internal fun mergeTargetedRetry(
    primary: FileSttPass,
    retry: FileSttPass,
): FileSttPass {
    val canonicalSegments = mutableListOf<TranscriptSegment>()
    (primary.segments + retry.segments)
        .sortedWith(compareBy<TranscriptSegment> { it.startMs }.thenBy { it.endMs })
        .forEach { segment ->
            val text = deduplicateTranscriptBoundary(canonicalSegments.lastOrNull()?.text, segment.text)
            if (text.isNotBlank()) canonicalSegments += segment.copy(text = text)
        }
    val diagnostics = primary.segmentDiagnostics.map { original ->
        if (original.meaningfulChars > 0) {
            original
        } else {
            val meaningful = retry.segmentDiagnostics
                .filter { it.startMs < original.endMs && it.endMs > original.startMs }
                .sumOf(SttSegmentDiagnostic::meaningfulChars)
            original.copy(meaningfulChars = meaningful)
        }
    }
    return FileSttPass(
        text = canonicalSegments.joinToString("\n", transform = TranscriptSegment::text),
        segments = canonicalSegments,
        inputDurationMs = primary.inputDurationMs,
        processedThroughMs = primary.inputDurationMs,
        sourceSegmentCount = diagnostics.size,
        recognizedSegmentCount = diagnostics.count { it.meaningfulChars > 0 },
        fixedChunkPass = false,
        segmentDiagnostics = diagnostics,
    )
}

private fun String.meaningfulCharacterCount(): Int =
    count(Char::isLetterOrDigit)

/** Avoid duplicate tail text while preserving words that begin after an overlapped segment cut. */
internal fun deduplicateTranscriptBoundary(previous: String?, current: String): String {
    if (previous.isNullOrBlank()) return current
    if (previous == current || previous.endsWith(current)) return ""
    val previousWords = previous.split(Regex("\\s+"))
    val currentWords = current.split(Regex("\\s+"))
    val overlap = (minOf(previousWords.size, currentWords.size) downTo 1).firstOrNull { size ->
        previousWords.takeLast(size) == currentWords.take(size)
    } ?: 0
    return currentWords.drop(overlap).joinToString(" ").trim()
}

/** A bounded-memory energy VAD for raw 16 kHz mono PCM. */
class Pcm16VoiceSegmenter(
    private val speechThreshold: Float = 0.012f,
    private val maxSegmentMs: Long = 28_000L,
    private val endSilenceMs: Long = 700L,
    private val preRollMs: Long = 300L,
    private val minSpeechMs: Long = 180L,
    private val forcedOverlapMs: Long = 1_000L,
    private val trailingPaddingMs: Long = 800L,
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
        require(forcedOverlapMs in 0 until maxSegmentMs) {
            "강제 분할 중첩은 최대 구간 길이보다 짧아야 합니다."
        }
        val preRollFrames = (preRollMs / FRAME_MS).toInt().coerceAtLeast(1)
        val endSilenceFrames = (endSilenceMs / FRAME_MS).toInt().coerceAtLeast(1)
        val minSpeechFrames = (minSpeechMs / FRAME_MS).toInt().coerceAtLeast(1)
        val maxFrames = (maxSegmentMs / FRAME_MS).toInt().coerceAtLeast(1)
        val forcedOverlapFrames = (forcedOverlapMs / FRAME_MS).toInt()
            .coerceIn(0, (maxFrames - 1).coerceAtLeast(0))
        val inputDurationMs = pcmFile.length() / 2L * 1_000L / SAMPLE_RATE
        val preRoll = ArrayDeque<FloatArray>(preRollFrames)
        var active: FloatCollector? = null
        var activeVoiced = ArrayDeque<Boolean>()
        var segmentStartFrame = 0L
        var speechFrames = 0
        var silentFrames = 0
        var frameIndex = 0L
        var emitted = false

        suspend fun emitActive(endFrameExclusive: Long, keepForcedOverlap: Boolean = false) {
            val samples = active?.toArray() ?: return
            val voicedFlags = activeVoiced.toList()
            val valid = speechFrames >= minSpeechFrames && samples.isNotEmpty()
            if (!valid) {
                active = null
                activeVoiced.clear()
                speechFrames = 0
                silentFrames = 0
                return
            }
            block(
                AudioSegment(
                    samples = samples.withTrailingSilence(),
                    startMs = segmentStartFrame * FRAME_MS,
                    endMs = minOf(endFrameExclusive * FRAME_MS, inputDurationMs),
                ),
            )
            emitted = true
            if (keepForcedOverlap && forcedOverlapFrames > 0) {
                val overlapFrames = minOf(forcedOverlapFrames, voicedFlags.size)
                val overlapSamples = minOf(samples.size, overlapFrames * FRAME_SAMPLES)
                active = FloatCollector((maxFrames + preRollFrames) * FRAME_SAMPLES).apply {
                    append(samples.copyOfRange(samples.size - overlapSamples, samples.size))
                }
                activeVoiced = ArrayDeque<Boolean>().apply {
                    voicedFlags.takeLast(overlapFrames).forEach { addLast(it) }
                }
                segmentStartFrame = endFrameExclusive - overlapFrames
                speechFrames = activeVoiced.count { it }
                silentFrames = activeVoiced.toList().asReversed().takeWhile { !it }.size
            } else {
                active = null
                activeVoiced.clear()
                speechFrames = 0
                silentFrames = 0
            }
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
                        preRoll.forEach {
                            active?.append(it)
                            activeVoiced.addLast(false)
                        }
                        active?.append(frame)
                        activeVoiced.addLast(true)
                        speechFrames = 1
                        silentFrames = 0
                    } else {
                        preRoll.addLast(frame)
                        while (preRoll.size > preRollFrames) preRoll.removeFirst()
                    }
                } else {
                    active?.append(frame)
                    activeVoiced.addLast(voiced)
                    if (voiced) {
                        speechFrames += 1
                        silentFrames = 0
                    } else {
                        silentFrames += 1
                    }
                    val activeFrames = active!!.size / FRAME_SAMPLES
                    if (silentFrames >= endSilenceFrames) {
                        emitActive(frameIndex + 1)
                        preRoll.clear()
                        preRoll.addLast(frame)
                    } else if (activeFrames >= maxFrames) {
                        emitActive(frameIndex + 1, keepForcedOverlap = true)
                        preRoll.clear()
                    }
                }
                frameIndex += 1
            }
        }
        if (active != null) emitActive(frameIndex)
        if (!emitted && pcmFile.length() > 0L) {
            // Very quiet recordings should still reach SenseVoice instead of being silently discarded.
            forEachFixedSegment(pcmFile, cancellationCheck, block)
        }
    }

    suspend fun forEachFixedSegment(
        pcmFile: File,
        cancellationCheck: suspend () -> Unit,
        block: suspend (AudioSegment) -> Unit,
    ) {
        val inputDurationMs = pcmFile.length() / 2L * 1_000L / SAMPLE_RATE
        forEachFixedRange(
            pcmFile = pcmFile,
            ranges = listOf(SttRetryRange(0L, inputDurationMs)),
            cancellationCheck = cancellationCheck,
            block = block,
        )
    }

    suspend fun forEachFixedRange(
        pcmFile: File,
        ranges: List<SttRetryRange>,
        cancellationCheck: suspend () -> Unit,
        block: suspend (AudioSegment) -> Unit,
    ) {
        val maxSamples = (maxSegmentMs * SAMPLE_RATE / 1_000L).toInt()
        val overlapSamples = (forcedOverlapMs * SAMPLE_RATE / 1_000L).toInt()
            .coerceIn(0, (maxSamples - 1).coerceAtLeast(0))
        val totalSamples = pcmFile.length() / 2L
        java.io.RandomAccessFile(pcmFile, "r").use { source ->
            ranges.sortedBy(SttRetryRange::startMs).forEach { range ->
                var startSample = (range.startMs * SAMPLE_RATE / 1_000L)
                    .coerceIn(0L, totalSamples)
                val rangeEndSample = (range.endMs * SAMPLE_RATE / 1_000L)
                    .coerceIn(startSample, totalSamples)
                while (startSample < rangeEndSample) {
                    cancellationCheck()
                    val samplesToRead =
                        minOf(maxSamples.toLong(), rangeEndSample - startSample).toInt()
                    val bytes = ByteArray(samplesToRead * 2)
                    source.seek(startSample * 2L)
                    var read = 0
                    while (read < bytes.size) {
                        val count = source.read(bytes, read, bytes.size - read)
                        if (count < 0) break
                        read += count
                    }
                    if (read == 0) break
                    val samples = bytes.toFloatSamples(read)
                    val endSample = startSample + samples.size
                    block(
                        AudioSegment(
                            samples = samples.withTrailingSilence(),
                            startMs = startSample * 1_000L / SAMPLE_RATE,
                            endMs = endSample * 1_000L / SAMPLE_RATE,
                        ),
                    )
                    if (endSample >= rangeEndSample) break
                    startSample = endSample - overlapSamples
                }
            }
        }
    }

    private fun FloatArray.withTrailingSilence(): FloatArray {
        val paddingSamples = (trailingPaddingMs * SAMPLE_RATE / 1_000L).toInt()
        return if (paddingSamples <= 0) this else copyOf(size + paddingSamples)
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
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20L
        const val FRAME_SAMPLES = 320
    }
}
