package com.thinktank.recorder.next.ondevice

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinktank.recorder.ondevice.audio.AndroidPcmNormalizer
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.modelpack.ModelStore
import com.thinktank.recorder.ondevice.stt.SenseVoiceFileSpeechEngine
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical-device full engine test for one controlled Korean recording sample.
 *
 * The source is created by the device's offline Korean TTS voice instead of recording a person,
 * so no user voice or transcript leaves the private test cache. It verifies the same core path
 * used after a completed recording is selected: audio file -> PCM -> SenseVoice.
 */
@RunWith(AndroidJUnit4::class)
class LocalAiSingleRecordedSampleDeviceTest {
    @Test
    fun oneOfflineKoreanRecordingRunsSenseVoice() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val store = ModelStore(context)
            val senseVoice = ModelCatalog.get(ModelId.SENSEVOICE_STT_KO)
            assumeTrue("SenseVoice 모델이 설치되지 않아 단일 샘플 QA를 건너뜁니다.", store.snapshot(senseVoice).ready)

            val recording = File(context.cacheDir, "local-ai-single-recording.wav")
            val pcm = File(context.cacheDir, "local-ai-single-recording.pcm")
            val speech = SenseVoiceFileSpeechEngine(context, store)
            recording.delete()
            pcm.delete()
            try {
                synthesizeOfflineKoreanRecording(context = context, output = recording)
                val normalized = AndroidPcmNormalizer().normalize(recording, pcm)
                assertTrue("PCM 변환 결과가 비어 있습니다.", normalized.sampleCount > 0L)

                val transcript = withTimeout(STT_TIMEOUT_MS) { speech.transcribe(pcm).text }
                val compact = transcript.replace(Regex("[^가-힣A-Za-z0-9]"), "")
                assertTrue("SenseVoice 전사 결과가 비어 있습니다.", compact.isNotBlank())
                assertTrue("SenseVoice 전사에 fixture 근거가 없습니다.", compact.contains("파일"))
                assertTrue("SenseVoice 전사에 fixture 근거가 없습니다.", compact.contains("전사"))

                Log.i(
                    LOG_TAG,
                    "single recorded sample pipeline verified: pcmSamples=${normalized.sampleCount}, " +
                        "transcriptChars=${transcript.length}",
                )
            } finally {
                speech.release()
                recording.delete()
                pcm.delete()
            }
        }
    }

    private suspend fun synthesizeOfflineKoreanRecording(
        context: android.content.Context,
        output: File,
    ) {
        val initialized = CompletableDeferred<Int>()
        val completed = CompletableDeferred<Throwable?>()
        val tts = TextToSpeech(context) { initialized.complete(it) }
        try {
            assertEquals(TextToSpeech.SUCCESS, withTimeout(TTS_TIMEOUT_MS) { initialized.await() })
            val languageResult = tts.setLanguage(Locale.KOREAN)
            assertTrue("오프라인 한국어 TTS를 사용할 수 없습니다.", languageResult >= TextToSpeech.LANG_AVAILABLE)
            val offlineVoice = requireNotNull(
                tts.voices?.firstOrNull {
                    it.locale.language == Locale.KOREAN.language && !it.isNetworkConnectionRequired
                },
            ) { "오프라인 한국어 TTS voice가 없습니다." }
            tts.voice = offlineVoice
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit

                override fun onDone(utteranceId: String) {
                    if (utteranceId == UTTERANCE_ID) completed.complete(null)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    if (utteranceId == UTTERANCE_ID) {
                        completed.complete(IllegalStateException("오프라인 한국어 TTS 생성에 실패했습니다."))
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
                tts.synthesizeToFile(TEST_PHRASE, Bundle(), output, UTTERANCE_ID),
            )
            check(withTimeout(TTS_TIMEOUT_MS) { completed.await() } == null)
            assertTrue("테스트 녹음 파일이 생성되지 않았습니다.", output.length() > 0L)
        } finally {
            tts.shutdown()
        }
    }

    private companion object {
        const val LOG_TAG = "LocalAiSampleQA"
        const val TEST_PHRASE = "파일 전사 검증 파란 은행나무 칠십삼"
        const val UTTERANCE_ID = "local-ai-single-recording-device-qa"
        const val TTS_TIMEOUT_MS = 30_000L
        const val STT_TIMEOUT_MS = 90_000L
    }
}
