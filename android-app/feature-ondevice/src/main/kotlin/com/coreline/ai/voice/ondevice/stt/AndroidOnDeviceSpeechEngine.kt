package com.coreline.ai.voice.ondevice.stt

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.coreline.ai.voice.ondevice.api.LiveSttEngine
import com.coreline.ai.voice.ondevice.api.SpeechEvent
import com.coreline.ai.voice.ondevice.api.SttCaptureProfile
import com.coreline.ai.voice.ondevice.api.SttResult
import com.coreline.ai.voice.ondevice.api.TranscriptSegment
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class SpeechRecognitionException(
    val recognitionCode: Int,
    message: String,
) : IllegalStateException(message)

internal class TerminalResultGate<T>(
    private val complete: (Result<T>) -> Unit,
) {
    private val completed = AtomicBoolean(false)

    fun tryComplete(result: Result<T>): Boolean {
        if (!completed.compareAndSet(false, true)) return false
        complete(result)
        return true
    }
}

class AndroidOnDeviceSpeechEngine(
    context: Context,
) : LiveSttEngine {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var cancelCurrent: (() -> Unit)? = null
    private var stopCurrent: (() -> Unit)? = null

    override fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(applicationContext)

    override suspend fun recognize(
        profile: SttCaptureProfile,
        onProgress: (SpeechEvent) -> Unit,
    ): SttResult =
        withContext(Dispatchers.Main.immediate) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                error("이 기기에 사용 가능한 온디바이스 음성 인식기가 없습니다")
            }
            check(SpeechRecognizer.isOnDeviceRecognitionAvailable(applicationContext)) {
                "이 기기에 사용 가능한 온디바이스 음성 인식기가 없습니다"
            }
            releaseOnMain(cancel = true)
            recognizeApi31(profile, onProgress)
        }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun recognizeApi31(
        profile: SttCaptureProfile,
        onProgress: (SpeechEvent) -> Unit,
    ): SttResult =
        suspendCancellableCoroutine { continuation ->
            var active: SpeechRecognizer? = null
            var stopWatchdog: Runnable? = null
            val gate = TerminalResultGate<SttResult> { result ->
                stopWatchdog?.let(mainHandler::removeCallbacks)
                active?.let { releaseSpecific(it, cancel = result.isFailure) }
                cancelCurrent = null
                stopCurrent = null
                continuation.resumeWith(result)
            }
            val listener = object : RecognitionListener {
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
                        Result.failure(
                            SpeechRecognitionException(error, errorMessage(error)),
                        ),
                    )
                }

                override fun onResults(results: Bundle?) {
                    val text = results.bestText()
                    if (text.isBlank()) {
                        gate.tryComplete(
                            Result.failure(
                                SpeechRecognitionException(
                                    SpeechRecognizer.ERROR_NO_MATCH,
                                    "인식된 음성이 없습니다",
                                ),
                            ),
                        )
                    } else {
                        gate.tryComplete(
                            Result.success(
                                SttResult(
                                    text = text,
                                    segments = listOf(TranscriptSegment(text = text)),
                                ),
                            ),
                        )
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults.bestText().takeIf(String::isNotBlank)?.let { text ->
                        if (continuation.isActive) onProgress(SpeechEvent.Partial(text))
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }

            try {
                val created = SpeechRecognizer.createOnDeviceSpeechRecognizer(applicationContext)
                active = created
                recognizer = created
                created.setRecognitionListener(listener)
                val stopRequested = AtomicBoolean(false)
                stopWatchdog = Runnable {
                    gate.tryComplete(
                        Result.failure(
                            SpeechRecognitionException(
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                                "음성 인식 종료 결과를 받지 못했습니다",
                            ),
                        ),
                    )
                }
                cancelCurrent = {
                    gate.tryComplete(
                        Result.failure(CancellationException("온디바이스 음성 인식이 취소되었습니다")),
                    )
                }
                stopCurrent = {
                    if (stopRequested.compareAndSet(false, true)) {
                        runCatching { created.stopListening() }
                        mainHandler.postDelayed(
                            checkNotNull(stopWatchdog),
                            STOP_WATCHDOG_MS,
                        )
                    }
                }
                continuation.invokeOnCancellation {
                    runOnMain {
                        mainHandler.removeCallbacks(stopWatchdog)
                        releaseSpecific(created, cancel = true)
                        cancelCurrent = null
                        stopCurrent = null
                    }
                }
                created.startListening(recognitionIntent(profile))
            } catch (error: Throwable) {
                if (active != null) {
                    gate.tryComplete(Result.failure(error))
                } else {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }

    override fun stop() {
        runOnMain { stopCurrent?.invoke() ?: recognizer?.stopListening() }
    }

    override fun cancel() {
        runOnMain {
            cancelCurrent?.invoke()
            releaseOnMain(cancel = true)
        }
    }

    override fun release() {
        cancel()
    }

    private fun recognitionIntent(profile: SttCaptureProfile) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // The recognizer provider makes the final endpointing decision. This preference is
            // used by the ViewModel's automatic re-arm loop to offer consistent user choices.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                profile.silenceMillis,
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

    private fun Bundle?.bestText(): String =
        this
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "마이크 입력을 읽지 못했습니다"
        SpeechRecognizer.ERROR_CLIENT -> "음성 인식 요청을 시작하지 못했습니다"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요합니다"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> "온디바이스 인식기가 네트워크 오류를 반환했습니다. 온라인 인식으로 전환하지 않습니다"
        SpeechRecognizer.ERROR_NO_MATCH -> "인식된 음성이 없습니다"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "음성 인식기가 사용 중입니다"
        SpeechRecognizer.ERROR_SERVER -> "온디바이스 음성 인식 서비스 오류입니다"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성이 감지되지 않았습니다"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "요청이 너무 많습니다. 잠시 후 다시 시도하세요"
        else -> "음성 인식 오류가 발생했습니다 ($code)"
    }

    private companion object {
        const val STOP_WATCHDOG_MS = 5_000L
    }
}
