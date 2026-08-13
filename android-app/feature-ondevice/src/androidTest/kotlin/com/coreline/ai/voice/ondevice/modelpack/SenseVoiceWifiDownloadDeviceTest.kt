package com.coreline.ai.voice.ondevice.modelpack

import android.content.Context
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coreline.ai.voice.ondevice.audio.AndroidPcmNormalizer
import com.coreline.ai.voice.ondevice.stt.SenseVoiceFileSpeechEngine
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in physical-device evidence test. It downloads the official SenseVoice
 * archive over a Network-bound Wi-Fi connection, verifies its pinned SHA-256,
 * performs the real model installation, verifies the installed marker, and
 * removes the isolated QA copy. It is deliberately excluded from normal QA runs.
 */
@RunWith(AndroidJUnit4::class)
class SenseVoiceWifiDownloadDeviceTest {
    @Test
    fun downloadsInstallsAndRunsPinnedSenseVoiceOverValidatedWifiOnly() {
        runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "명시적 Wi-Fi 모델 다운로드 QA에서만 실행합니다.",
            arguments.getString(ARG_RUN_WIFI_MODEL_DOWNLOAD) == "true",
        )
        assumeTrue(
            "승인된 Samsung SM-S931N에서만 모델 다운로드 QA를 실행합니다.",
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) && Build.MODEL == "SM-S931N",
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wifi = WifiOnlyDownloadPolicy.validatedWifi(context)
        assertNotNull("검증된 Wi-Fi 연결이 필요합니다.", wifi)
        val selectedWifi = requireNotNull(wifi)
        assertTrue(WifiOnlyDownloadPolicy.isStillValidatedWifi(context, selectedWifi))

        val descriptor = ModelCatalog.get(ModelId.SENSEVOICE_STT_KO)
        val store = ModelStore(context)
        val artifact = File(context.cacheDir, "sensevoice-wifi-qa.tar.bz2")
        val ttsAudio = File(context.cacheDir, "sensevoice-fixture.wav")
        val pcm = File(context.cacheDir, "sensevoice-fixture.pcm")
        store.delete(descriptor.id)
        artifact.delete()
        ttsAudio.delete()
        pcm.delete()
        try {
            Log.i(LOG_TAG, "stage=download")
            val result = downloadOverNetwork(context, selectedWifi, artifact)
            assertEquals(EXACT_BYTES, result.bytes)
            assertEquals(EXPECTED_SHA256, result.sha256)
            assertTrue(WifiOnlyDownloadPolicy.isStillValidatedWifi(context, selectedWifi))
            Log.i(LOG_TAG, "stage=install")
            ModelInstaller(store).install(descriptor, artifact)
            val installed = store.snapshot(descriptor)
            assertTrue("설치 marker와 필수 SenseVoice 파일을 검증하지 못했습니다.", installed.ready)
            assertTrue(File(store.installDir(descriptor.id), "model.int8.onnx").isFile)
            assertTrue(File(store.installDir(descriptor.id), "tokens.txt").isFile)
            Log.i(LOG_TAG, "stage=offline_tts")
            synthesizeOfflineKorean(context, ttsAudio)
            val normalized = AndroidPcmNormalizer().normalize(ttsAudio, pcm)
            assertTrue("SenseVoice 입력 PCM이 비어 있습니다.", normalized.sampleCount > 0L)
            Log.i(LOG_TAG, "stage=local_transcribe")
            val engine = SenseVoiceFileSpeechEngine(context, store)
            try {
                val transcript = withTimeout(STT_TIMEOUT_MS) { engine.transcribe(pcm) }.text
                val compact = transcript.replace(Regex("[^가-힣A-Za-z0-9]"), "")
                assertTrue("SenseVoice 결과가 비어 있습니다.", compact.isNotBlank())
                assertTrue("SenseVoice 결과에 '파일' 근거가 없습니다.", compact.contains("파일"))
                assertTrue("SenseVoice 결과에 '전사' 근거가 없습니다.", compact.contains("전사"))
            } finally {
                engine.release()
            }
            Log.i(
                LOG_TAG,
                "Wi-Fi-bound SenseVoice download+install+local-transcribe verified: " +
                    "${result.bytes} bytes in ${result.elapsedMs}ms",
            )
        } finally {
            artifact.delete()
            ttsAudio.delete()
            pcm.delete()
            store.delete(descriptor.id)
        }
        }
    }

    private fun downloadOverNetwork(
        context: Context,
        wifi: Network,
        artifact: File,
    ): DownloadResult {
        var url = URL(DOWNLOAD_URL)
        repeat(MAX_REDIRECTS) {
            assertTrue("HTTPS 모델 URL만 허용합니다.", url.protocol == "https")
            assertTrue(
                "허용되지 않은 모델 배포 host입니다: ${url.host}",
                ModelDownloadWorker.isAllowedModelHost(url.host),
            )
            val connection = (wifi.openConnection(url) as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AI R Voice-WifiModelQA/1")
            }
            try {
                when (connection.responseCode) {
                    in 200..299 -> return copyAndVerify(context, wifi, connection, artifact)
                    in 300..399 -> {
                        val location = requireNotNull(connection.getHeaderField("Location")) {
                            "모델 redirect 위치가 없습니다"
                        }
                        url = URL(url, location)
                    }
                    else -> error("SenseVoice 모델 다운로드 실패: HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("SenseVoice 모델 redirect가 너무 많습니다")
    }

    private fun copyAndVerify(
        context: Context,
        wifi: Network,
        connection: HttpURLConnection,
        artifact: File,
    ): DownloadResult {
        val advertisedBytes = connection.contentLengthLong
        if (advertisedBytes >= 0) {
            assertEquals(EXACT_BYTES, advertisedBytes)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val startedAt = SystemClock.elapsedRealtime()
        var copied = 0L
        BufferedInputStream(connection.inputStream).use { source ->
            FileOutputStream(artifact).use { sink ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                while (true) {
                    assertTrue(
                        "Wi-Fi가 끊겨 다운로드를 계속할 수 없습니다.",
                        WifiOnlyDownloadPolicy.isStillValidatedWifi(context, wifi),
                    )
                    val count = source.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    copied += count
                }
                sink.fd.sync()
            }
        }
        return DownloadResult(
            bytes = copied,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
        )
    }

    private suspend fun synthesizeOfflineKorean(context: Context, output: File) {
        val initialized = CompletableDeferred<Int>()
        val completed = CompletableDeferred<Throwable?>()
        val tts = TextToSpeech(context) { initialized.complete(it) }
        try {
            assertEquals(
                TextToSpeech.SUCCESS,
                withTimeout(TTS_TIMEOUT_MS) { initialized.await() },
            )
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
            assertTrue("오프라인 TTS 파일이 생성되지 않았습니다.", output.length() > 0L)
        } finally {
            tts.shutdown()
        }
    }

    private data class DownloadResult(
        val bytes: Long,
        val sha256: String,
        val elapsedMs: Long,
    )

    private companion object {
        const val ARG_RUN_WIFI_MODEL_DOWNLOAD = "runWifiModelDownload"
        const val LOG_TAG = "SenseVoiceWifiQA"
        const val DOWNLOAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
        const val EXACT_BYTES = 163_002_883L
        const val EXPECTED_SHA256 = "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e"
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000
        const val TEST_PHRASE = "파일 전사 검증 파란 은행나무 칠십삼"
        const val UTTERANCE_ID = "sensevoice-file-stt-device-qa"
        const val TTS_TIMEOUT_MS = 30_000L
        const val STT_TIMEOUT_MS = 90_000L
    }
}
