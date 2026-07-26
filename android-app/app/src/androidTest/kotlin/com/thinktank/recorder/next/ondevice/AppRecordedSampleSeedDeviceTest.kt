package com.thinktank.recorder.next.ondevice

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinktank.recorder.next.data.local.ChunkEntity
import com.thinktank.recorder.next.data.local.ChunkState
import com.thinktank.recorder.next.data.local.RecordingSessionEntity
import com.thinktank.recorder.next.data.local.RecordingState
import com.thinktank.recorder.next.data.local.ThinkTankDatabase
import com.thinktank.recorder.next.recording.RecordingFileManager
import com.thinktank.recorder.ondevice.stt.Pcm16WavReader
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
 * Explicit opt-in fixture creator for manual app UI QA. It writes a controlled offline Korean
 * recording through the same finalized main-recorder archive contract, so the 4th-tab picker can
 * select it. It intentionally leaves this one QA fixture in the isolated QA package.
 */
@RunWith(AndroidJUnit4::class)
class AppRecordedSampleSeedDeviceTest {
    @Test
    fun seedsOneFinalizedKoreanRecordingForActualAppFlow() {
        runBlocking {
            assumeTrue(
                "명시적 실제 앱 샘플 QA에서만 fixture를 만듭니다.",
                InstrumentationRegistry.getArguments().getString(ARG_SEED_APP_SAMPLE) == "true",
            )
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val fileManager = RecordingFileManager(context)
            val (chunkId, part) = fileManager.createPartFile(extension = "wav", uuid = CHUNK_ID)
            val final = File(fileManager.directory, part.name.removeSuffix(".part"))
            part.delete()
            final.delete()
            synthesizeOfflineKoreanRecording(context, part)
            val finalized = fileManager.finalize(part)
            val durationMs = finalized.durationMs ?: Pcm16WavReader.inspect(finalized.file).durationMs
            assertTrue("QA 녹음 fixture의 길이가 올바르지 않습니다.", durationMs > 0L)

            // Use the same file name and schema as the target app. The fixture is inserted into
            // the isolated device-QA application database, not a test-only shadow database.
            val database = Room.databaseBuilder(
                context,
                ThinkTankDatabase::class.java,
                "thinktank-recorder.db",
            ).build()
            val now = System.currentTimeMillis()
            try {
                val dao = database.recordingDao()
                dao.upsertSession(
                    RecordingSessionEntity(
                        id = SESSION_ID,
                        state = RecordingState.STOPPED,
                        startedAt = now,
                        stoppedAt = now,
                    ),
                )
                dao.insertChunk(
                    ChunkEntity(
                        id = chunkId,
                        sessionId = SESSION_ID,
                        uploadId = UPLOAD_ID,
                        path = finalized.file.absolutePath,
                        state = ChunkState.READY,
                        createdAt = now,
                        finalizedAt = now,
                        sizeBytes = finalized.size,
                        durationMs = durationMs,
                        sha256 = finalized.sha256,
                    ),
                )
            } finally {
                database.close()
            }
        }
    }

    private suspend fun synthesizeOfflineKoreanRecording(context: android.content.Context, output: File) {
        val initialized = CompletableDeferred<Int>()
        val completed = CompletableDeferred<Throwable?>()
        val tts = TextToSpeech(context) { initialized.complete(it) }
        try {
            assertEquals(TextToSpeech.SUCCESS, withTimeout(TTS_TIMEOUT_MS) { initialized.await() })
            assertTrue(
                "오프라인 한국어 TTS를 사용할 수 없습니다.",
                tts.setLanguage(Locale.KOREAN) >= TextToSpeech.LANG_AVAILABLE,
            )
            tts.voice = requireNotNull(
                tts.voices?.firstOrNull {
                    it.locale.language == Locale.KOREAN.language && !it.isNetworkConnectionRequired
                },
            ) { "오프라인 한국어 TTS voice가 없습니다." }
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
        } finally {
            tts.shutdown()
        }
    }

    private companion object {
        const val ARG_SEED_APP_SAMPLE = "seedAppSample"
        const val SESSION_ID = "qa-local-ai-sample-session-20260726"
        const val CHUNK_ID = "qa-local-ai-sample-chunk-20260726"
        const val UPLOAD_ID = "qa-local-ai-sample-upload-20260726"
        const val TEST_PHRASE = "파일 전사 검증 파란 은행나무 칠십삼"
        const val UTTERANCE_ID = "qa-local-ai-sample-utterance"
        const val TTS_TIMEOUT_MS = 30_000L
    }
}
