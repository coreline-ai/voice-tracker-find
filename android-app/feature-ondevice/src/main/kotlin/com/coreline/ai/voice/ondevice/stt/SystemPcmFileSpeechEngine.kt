package com.coreline.ai.voice.ondevice.stt

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.coreline.ai.voice.ondevice.api.SpeechEvent
import com.coreline.ai.voice.ondevice.api.SttResult
import com.coreline.ai.voice.ondevice.api.TranscriptSegment
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

enum class SystemFileSttAvailability {
    API_UNSUPPORTED,
    ONDEVICE_STT_UNAVAILABLE,
    DEVICE_VALIDATION_REQUIRED,
    READY,
}

/**
 * File-input path kept separate from the microphone recognizer.
 *
 * Android recognizer providers may ignore EXTRA_AUDIO_SOURCE. The caller must therefore keep this
 * engine behind a physical-device validation policy instead of silently falling back to live STT.
 */
class SystemPcmFileSpeechEngine(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var cancelCurrent: (() -> Unit)? = null
    private var stopCurrent: (() -> Unit)? = null

    fun availability(validatedForThisDevice: Boolean): SystemFileSttAvailability = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> SystemFileSttAvailability.API_UNSUPPORTED
        !SpeechRecognizer.isOnDeviceRecognitionAvailable(applicationContext) ->
            SystemFileSttAvailability.ONDEVICE_STT_UNAVAILABLE
        !validatedForThisDevice -> SystemFileSttAvailability.DEVICE_VALIDATION_REQUIRED
        else -> SystemFileSttAvailability.READY
    }

    suspend fun transcribe(
        pcmFile: File,
        onProgress: (SpeechEvent) -> Unit = {},
    ): SttResult = withContext(Dispatchers.Main.immediate) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            error("녹음 파일 전사는 Android 13 이상에서만 사용할 수 있습니다.")
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(applicationContext)) {
            error("이 기기에서 시스템 온디바이스 STT를 사용할 수 없습니다.")
        }
        check(pcmFile.isFile && pcmFile.length() >= 2L) { "전사할 PCM 파일을 찾을 수 없습니다." }
        releaseOnMain(cancel = true)
        transcribeApi33(pcmFile, onProgress)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun transcribeApi33(
        pcmFile: File,
        onProgress: (SpeechEvent) -> Unit,
    ): SttResult = suspendCancellableCoroutine { continuation ->
        var active: SpeechRecognizer? = null
        var descriptor: ParcelFileDescriptor? = null
        var stopWatchdog: Runnable? = null
        val completedSegments = mutableListOf<String>()
        val gate = TerminalResultGate<SttResult> { result ->
            stopWatchdog?.let(mainHandler::removeCallbacks)
            stopWatchdog = null
            active?.let { releaseSpecific(it, cancel = result.isFailure) }
            runCatching { descriptor?.close() }
            descriptor = null
            cancelCurrent = null
            stopCurrent = null
            continuation.resumeWith(result)
        }
        val listener = object : RecognitionListener {
            fun completeFromSegments() {
                val text = completedSegments.joinToString(separator = "\n").trim()
                if (text.isBlank()) {
                    gate.tryComplete(
                        Result.failure(
                            SpeechRecognitionException(
                                SpeechRecognizer.ERROR_NO_MATCH,
                                "녹음 파일에서 인식된 음성이 없습니다.",
                            ),
                        ),
                    )
                } else {
                    gate.tryComplete(Result.success(text.toResult()))
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {
                if (continuation.isActive) onProgress(SpeechEvent.Ready)
            }

            override fun onBeginningOfSpeech() {
                if (continuation.isActive) onProgress(SpeechEvent.Listening)
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                gate.tryComplete(
                    Result.failure(SpeechRecognitionException(error, errorMessage(error))),
                )
            }

            override fun onResults(results: Bundle?) {
                val finalText = results.bestText()
                if (finalText.isBlank()) {
                    gate.tryComplete(
                        Result.failure(
                            SpeechRecognitionException(
                                SpeechRecognizer.ERROR_NO_MATCH,
                                "녹음 파일에서 인식된 음성이 없습니다.",
                            ),
                        ),
                    )
                } else {
                    gate.tryComplete(Result.success(finalText.toResult()))
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.bestText().takeIf(String::isNotBlank)?.let { partial ->
                    if (continuation.isActive) onProgress(SpeechEvent.Partial(partial))
                }
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                segmentResults.bestText().takeIf(String::isNotBlank)?.let { segment ->
                    if (completedSegments.lastOrNull() != segment) completedSegments += segment
                    if (continuation.isActive) {
                        onProgress(SpeechEvent.Partial(completedSegments.joinToString("\n")))
                    }
                }
            }

            override fun onEndOfSegmentedSession() {
                completeFromSegments()
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        try {
            val created = SpeechRecognizer.createOnDeviceSpeechRecognizer(applicationContext)
            active = created
            recognizer = created
            descriptor = ParcelFileDescriptor.open(pcmFile, ParcelFileDescriptor.MODE_READ_ONLY)
            created.setRecognitionListener(listener)
            val stopRequested = AtomicBoolean(false)
            cancelCurrent = {
                gate.tryComplete(Result.failure(CancellationException("파일 전사가 취소되었습니다.")))
            }
            stopCurrent = {
                if (stopRequested.compareAndSet(false, true)) {
                    runCatching { created.stopListening() }
                    stopWatchdog = Runnable {
                        gate.tryComplete(
                            Result.failure(
                                SpeechRecognitionException(
                                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                                    "파일 전사 종료 결과를 받지 못했습니다.",
                                ),
                            ),
                        )
                    }
                    mainHandler.postDelayed(checkNotNull(stopWatchdog), STOP_WATCHDOG_MS)
                }
            }
            continuation.invokeOnCancellation {
                runOnMain {
                    stopWatchdog?.let(mainHandler::removeCallbacks)
                    stopWatchdog = null
                    releaseSpecific(created, cancel = true)
                    runCatching { descriptor?.close() }
                    descriptor = null
                    cancelCurrent = null
                    stopCurrent = null
                }
            }
            created.startListening(fileIntent(checkNotNull(descriptor)))
            // The recognizer receives its own Binder FD. Closing this copy signals EOF for the
            // segmented file session; retaining it would leave a supported provider waiting for
            // more samples indefinitely.
            runCatching { descriptor?.close() }
            descriptor = null
        } catch (error: Throwable) {
            if (active != null) gate.tryComplete(Result.failure(error))
            else continuation.resumeWith(Result.failure(error))
        }
    }

    fun stop() {
        runOnMain { stopCurrent?.invoke() ?: recognizer?.stopListening() }
    }

    fun cancel() {
        runOnMain {
            cancelCurrent?.invoke()
            releaseOnMain(cancel = true)
        }
    }

    fun release() = cancel()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun fileIntent(descriptor: ParcelFileDescriptor): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptor)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, PCM_SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(
                RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                RecognizerIntent.EXTRA_AUDIO_SOURCE,
            )
        }

    private fun releaseSpecific(active: SpeechRecognizer, cancel: Boolean) {
        if (recognizer !== active) return
        recognizer = null
        if (cancel) runCatching { active.cancel() }
        runCatching { active.destroy() }
    }

    private fun releaseOnMain(cancel: Boolean) {
        val active = recognizer ?: return
        recognizer = null
        stopCurrent = null
        if (cancel) runCatching { active.cancel() }
        runCatching { active.destroy() }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun Bundle?.bestText(): String = this
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.trim()
        .orEmpty()

    private fun String.toResult(): SttResult = SttResult(
        text = this,
        segments = lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { TranscriptSegment(text = it) }
            .toList(),
    )

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "녹음 파일에서 인식된 음성이 없습니다."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "파일 전사가 시간 안에 끝나지 않았습니다."
        SpeechRecognizer.ERROR_AUDIO -> "시스템 STT가 PCM 파일 입력을 사용할 수 없습니다."
        SpeechRecognizer.ERROR_CLIENT -> "시스템 STT 파일 전사를 시작하지 못했습니다."
        else -> "시스템 STT 파일 전사 오류가 발생했습니다 ($code)"
    }

    private companion object {
        const val PCM_SAMPLE_RATE = 16_000
        const val STOP_WATCHDOG_MS = 5_000L
    }
}
