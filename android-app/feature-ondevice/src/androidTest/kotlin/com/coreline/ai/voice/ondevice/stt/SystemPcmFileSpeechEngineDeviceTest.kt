package com.coreline.ai.voice.ondevice.stt

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coreline.ai.voice.ondevice.audio.AndroidPcmNormalizer
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Samsung release gate for the API 33 file-descriptor STT path.
 *
 * It synthesizes a fixed Korean phrase with an installed *offline* voice, never plays it through
 * the speaker, then checks that the recognizer returns two source-only words. A provider that
 * rejects injected PCM is recorded as an explicit release-gate skip rather than being mistaken
 * for a product regression. All generated audio stays in the test cache directory and is deleted
 * in finally.
 */
@RunWith(AndroidJUnit4::class)
class SystemPcmFileSpeechEngineDeviceTest {
    @Test
    fun samsungOnDeviceRecognizerConsumesOfflineKoreanPcmFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "file-stt-device-qa").apply {
            deleteRecursively()
            mkdirs()
        }
        val ttsAudio = File(root, "fixture.wav")
        val pcm = File(root, "fixture.pcm")
        val engine = SystemPcmFileSpeechEngine(context)
        try {
            assumeTrue(
                "이 기기에는 파일 전사에 필요한 온디바이스 STT가 없습니다",
                engine.availability(validatedForThisDevice = true) == SystemFileSttAvailability.READY,
            )
            synthesizeOfflineKorean(context, ttsAudio)
            val normalized = AndroidPcmNormalizer().normalize(ttsAudio, pcm)
            assertTrue("PCM 정규화 결과가 비어 있습니다", normalized.sampleCount > 0L)

            val result = try {
                withTimeout(STT_TIMEOUT_MS) { engine.transcribe(pcm) }
            } catch (error: SpeechRecognitionException) {
                if (
                    error.recognitionCode == android.speech.SpeechRecognizer.ERROR_CLIENT ||
                    error.recognitionCode == android.speech.SpeechRecognizer.ERROR_NO_MATCH ||
                    error.recognitionCode == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    throw AssumptionViolatedException(
                        "이 Samsung 시스템 STT provider는 injected PCM 파일 전사를 지원하지 않습니다 " +
                            "(code=${error.recognitionCode}). 기능은 비활성 상태를 유지합니다.",
                        error,
                    )
                }
                throw error
            }
            val compact = result.text.replace(Regex("[^가-힣A-Za-z0-9]"), "")
            assertTrue("파일 STT 결과에 '파일' 근거가 없습니다: ${result.text}", compact.contains("파일"))
            assertTrue("파일 STT 결과에 '전사' 근거가 없습니다: ${result.text}", compact.contains("전사"))
        } finally {
            engine.release()
            root.deleteRecursively()
        }
    }

    private suspend fun synthesizeOfflineKorean(context: Context, output: File) {
        val initialized = CompletableDeferred<Int>()
        val completed = CompletableDeferred<Throwable?>()
        val tts = TextToSpeech(context) { initialized.complete(it) }
        try {
            assumeTrue(
                "시스템 TTS를 시작하지 못했습니다",
                withTimeout(TTS_TIMEOUT_MS) { initialized.await() } == TextToSpeech.SUCCESS,
            )
            val languageResult = tts.setLanguage(Locale.KOREAN)
            assumeTrue(
                "오프라인 한국어 TTS를 사용할 수 없습니다",
                languageResult >= TextToSpeech.LANG_AVAILABLE,
            )
            val offlineVoice = tts.voices
                ?.firstOrNull { it.locale.language == Locale.KOREAN.language && !it.isNetworkConnectionRequired }
            assumeTrue("오프라인 한국어 TTS voice가 없습니다", offlineVoice != null)
            tts.voice = checkNotNull(offlineVoice)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit

                override fun onDone(utteranceId: String) {
                    if (utteranceId == UTTERANCE_ID) completed.complete(null)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    if (utteranceId == UTTERANCE_ID) {
                        completed.complete(IllegalStateException("오프라인 한국어 TTS 생성에 실패했습니다"))
                    }
                }

                override fun onError(utteranceId: String, errorCode: Int) {
                    if (utteranceId == UTTERANCE_ID) {
                        completed.complete(IllegalStateException("오프라인 한국어 TTS 오류: $errorCode"))
                    }
                }
            })
            assertEquals(
                TextToSpeech.SUCCESS,
                tts.synthesizeToFile(
                    TEST_PHRASE,
                    Bundle(),
                    output,
                    UTTERANCE_ID,
                ),
            )
            check(withTimeout(TTS_TIMEOUT_MS) { completed.await() } == null)
            assertTrue("오프라인 TTS 파일이 생성되지 않았습니다", output.length() > 0L)
        } finally {
            tts.shutdown()
        }
    }

    private companion object {
        const val TEST_PHRASE = "파일 전사 검증 파란 은행나무 칠십삼"
        const val UTTERANCE_ID = "file-stt-device-qa"
        const val TTS_TIMEOUT_MS = 30_000L
        const val STT_TIMEOUT_MS = 60_000L
    }
}
