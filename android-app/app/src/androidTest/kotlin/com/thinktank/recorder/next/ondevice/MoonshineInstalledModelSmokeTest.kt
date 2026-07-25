package com.thinktank.recorder.next.ondevice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinktank.recorder.ondevice.modelpack.ModelStore
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.stt.MoonshineSpeechEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Optional native-runtime QA using the official model archive's Korean WAV.
 *
 * The fixture is intentionally not distributed with the app. Before running
 * this test, place it at:
 * files/ondevice/qa/moonshine-ko-0.wav
 *
 * The installed model is also expected to have been obtained through the
 * product's model manager so this checks the same private storage path used in
 * production.
 */
@RunWith(AndroidJUnit4::class)
class MoonshineInstalledModelSmokeTest {
    @Test
    fun installedMoonshineModelTranscribesOfficialKoreanFixture() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.filesDir, "ondevice/qa/moonshine-ko-0.wav")
        assumeTrue("외부 Moonshine QA fixture가 설치되지 않아 테스트를 건너뜁니다", fixture.isFile)
        val store = ModelStore(context)
        assumeTrue(
            "무결성 manifest를 포함한 Moonshine 모델이 설치되지 않아 테스트를 건너뜁니다",
            store.snapshot(ModelCatalog.get(ModelId.MOONSHINE_KO)).ready,
        )

        val engine = MoonshineSpeechEngine(store)
        val startedAt = System.nanoTime()
        val result = try {
            engine.transcribe(fixture)
        } finally {
            engine.release()
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        println("Moonshine native smoke: ${elapsedMs}ms, transcript=${result.text}")
        assertTrue(result.text.contains("조국"))
        assertTrue(result.text.contains("무엇"))
        assertTrue(result.text.length >= 20)
    }
}
