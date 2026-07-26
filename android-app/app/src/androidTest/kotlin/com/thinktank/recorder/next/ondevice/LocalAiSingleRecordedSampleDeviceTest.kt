package com.thinktank.recorder.next.ondevice

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import com.thinktank.recorder.ondevice.audio.AndroidPcmNormalizer
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.modelpack.ModelStore
import com.thinktank.recorder.ondevice.stt.SenseVoiceFileSpeechEngine
import com.thinktank.recorder.ondevice.summary.QwenSummaryEngine
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
 * used after a completed recording is selected: audio file -> PCM -> SenseVoice -> Qwen.
 */
@RunWith(AndroidJUnit4::class)
class LocalAiSingleRecordedSampleDeviceTest {
    @Test
    fun oneOfflineKoreanRecordingRunsSenseVoiceThenQwen() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val store = ModelStore(context)
            val senseVoice = ModelCatalog.get(ModelId.SENSEVOICE_STT_KO)
            val qwen = ModelCatalog.get(ModelId.QWEN_SUMMARY_KO)
            assumeTrue("SenseVoice 모델이 설치되지 않아 단일 샘플 QA를 건너뜁니다.", store.snapshot(senseVoice).ready)
            assumeTrue("Qwen 모델이 설치되지 않아 단일 샘플 QA를 건너뜁니다.", store.snapshot(qwen).ready)

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

                val summary = withTimeout(QWEN_TIMEOUT_MS) {
                    QwenSummaryEngine(context, store).summarize(transcript)
                }
                assertEquals(SummaryEngineType.QWEN_LOCAL, summary.engine)
                assertTrue("Qwen 제목이 비어 있습니다.", summary.title.isNotBlank())
                // A very short STT fixture can legitimately have no meaningful row after the
                // strict 15% cap. The product must prefer an empty body over expanding it.
                assertTrue("Qwen 핵심 요약 항목 수가 허용 범위를 벗어났습니다.", summary.bullets.size <= 2)
                assertTrue("Qwen 핵심 요약 항목이 너무 깁니다.", summary.bullets.all { it.length <= 30 })
                val persistedSummary = summary.bullets.joinToString("\n")
                val normalizedTranscript = transcript.replace(Regex("\\s+"), " ").trim()
                assertTrue("Qwen 요약은 전사보다 짧아야 합니다.", persistedSummary.length < normalizedTranscript.length)
                assertTrue(
                    "Qwen 요약이 전사의 15% 또는 80자 상한을 초과했습니다.",
                    persistedSummary.length <= minOf(80, (normalizedTranscript.length * 0.15).toInt()),
                )
                assertTrue("Qwen 원문 hash가 비어 있습니다.", summary.sourceHash.isNotBlank())
                Log.i(
                    LOG_TAG,
                    "single recorded sample pipeline verified: pcmSamples=${normalized.sampleCount}, " +
                        "transcriptChars=${transcript.length}, titleChars=${summary.title.length}, " +
                        "bulletCount=${summary.bullets.size}",
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
        const val QWEN_TIMEOUT_MS = 130_000L
    }
}
