package com.thinktank.recorder.next.ondevice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinktank.recorder.ondevice.api.SttEngineType
import com.thinktank.recorder.ondevice.data.OnDeviceDatabase
import com.thinktank.recorder.ondevice.data.OnDeviceSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, read-only physical-device assertion for a user-selected completed main recording.
 * It only checks engine names, source duration, and aggregate character/item counts; it never
 * reads a transcript into test output and never writes to the target application's DB.
 */
@RunWith(AndroidJUnit4::class)
class ExistingMainRecordingResultDeviceTest {
    @Test
    fun latestMainRecordingResultHasFullTranscript() = runBlocking {
        assumeTrue(
            "명시적 기존 녹음 UI QA에서만 실행합니다.",
            InstrumentationRegistry.getArguments().getString(ARG_ASSERT_EXISTING_RESULT) == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val latest = OnDeviceDatabase.get(context).sessionDao().observeAll().first().first()

        assertEquals(OnDeviceSessionEntity.SOURCE_TYPE_MAIN_RECORDER_CHUNK, latest.sourceType)
        assertTrue("기존 1분 원본이 아닙니다.", (latest.sourceDurationMs ?: 0L) >= 60_000L)
        assertEquals(SttEngineType.SENSEVOICE_LOCAL_FILE.name, latest.sttEngine)
        assertTrue("저장된 전사문이 비어 있습니다.", latest.transcript.isNotBlank())
        assertTrue("전사문 길이가 비정상적으로 짧습니다.", latest.transcript.length >= MIN_TRANSCRIPT_CHARS)
        assertEquals(latest.sttInputDurationMs, latest.sttProcessedThroughMs)
    }

    private companion object {
        const val ARG_ASSERT_EXISTING_RESULT = "assertExistingResult"
        const val MIN_TRANSCRIPT_CHARS = 120
    }
}
