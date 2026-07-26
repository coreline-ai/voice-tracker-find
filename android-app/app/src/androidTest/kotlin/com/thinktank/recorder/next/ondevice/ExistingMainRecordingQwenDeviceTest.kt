package com.thinktank.recorder.next.ondevice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import com.thinktank.recorder.ondevice.data.OnDeviceDatabase
import com.thinktank.recorder.ondevice.data.OnDeviceSessionEntity
import com.thinktank.recorder.ondevice.modelpack.ModelStore
import com.thinktank.recorder.ondevice.summary.QwenSummaryEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in, read-only retry of Qwen against the latest completed main-recording transcript.
 * No user transcript, title, summary, or raw model output is written to the test result.
 */
@RunWith(AndroidJUnit4::class)
class ExistingMainRecordingQwenDeviceTest {
    @Test
    fun qwenCompactsLatestMainRecordingWithoutExpandingIt() = runBlocking {
        assumeTrue(
            "명시적 기존 녹음 Qwen QA에서만 실행합니다.",
            InstrumentationRegistry.getArguments().getString(ARG_ASSERT_EXISTING_QWEN) == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = OnDeviceDatabase.get(context).sessionDao().observeAll().first().first()
        assertEquals(OnDeviceSessionEntity.SOURCE_TYPE_MAIN_RECORDER_CHUNK, source.sourceType)
        assertTrue("전사문이 너무 짧아 Qwen QA를 실행할 수 없습니다.", source.transcript.length >= 120)

        val result = QwenSummaryEngine(context, ModelStore(context)).summarize(source.transcript)
        val persisted = result.bullets.joinToString("\n")
        val normalizedSourceLength = source.transcript.replace(Regex("\\s+"), " ").trim().length

        assertEquals(SummaryEngineType.QWEN_LOCAL, result.engine)
        assertTrue(result.bullets.size in 1..MAX_BULLETS)
        assertTrue(result.bullets.all { it.length <= MAX_BULLET_CHARS })
        assertTrue(persisted.length < normalizedSourceLength)
        assertTrue(
            persisted.length <= minOf(
                MAX_SUMMARY_CHARS,
                (normalizedSourceLength * MAX_SOURCE_RATIO).toInt(),
            ).coerceAtLeast(MIN_SUMMARY_BUDGET),
        )
    }

    private companion object {
        const val ARG_ASSERT_EXISTING_QWEN = "assertExistingQwen"
        const val MAX_BULLETS = 2
        const val MAX_BULLET_CHARS = 44
        const val MAX_SUMMARY_CHARS = 60
        const val MAX_SOURCE_RATIO = 0.12
        const val MIN_SUMMARY_BUDGET = 24
    }
}
