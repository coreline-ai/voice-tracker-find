package com.thinktank.recorder.next.ondevice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.modelpack.ModelStore
import com.thinktank.recorder.ondevice.summary.LocalLlmSummaryEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Samsung device smoke test for the officially imported Gemma 3 LiteRT-LM artifact.
 *
 * The test only runs when the verified model has been installed through the product UI.
 */
@RunWith(AndroidJUnit4::class)
class GemmaInstalledModelSmokeTest {
    @Test
    fun installedGemmaModelCreatesCompactKoreanSummary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ModelStore(context)
        val descriptor = ModelCatalog.get(ModelId.GEMMA_SUMMARY_KO)
        assumeTrue("Gemma QA 모델이 설치되지 않아 테스트를 건너뜁니다", store.snapshot(descriptor).ready)
        val transcript = """
            오늘 제품 회의에서 새 녹음 화면의 출시일은 8월 15일로 확정했다.
            민수는 금요일까지 배포 체크리스트를 작성하기로 했다.
            모든 음성 전사와 요약은 네트워크로 보내지 않고 휴대전화 안에서만 처리한다.
        """.trimIndent()

        val startedAt = System.nanoTime()
        val result = LocalLlmSummaryEngine(context, store, ModelId.GEMMA_SUMMARY_KO)
            .summarize(transcript)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        println(
            "DEVICE_QA GEMMA-01 elapsedMs=$elapsedMs titleChars=${result.title.length} " +
                "bulletCount=${result.bullets.size} actionCount=${result.actionItems.size}",
        )
        assertEquals(SummaryEngineType.GEMMA_LOCAL, result.engine)
        assertTrue(result.title.isNotBlank())
        assertTrue(result.bullets.size in 1..2)
        assertTrue(result.bullets.all { it.length <= 44 })
        assertTrue(result.sourceHash.isNotBlank())
        assertEquals(ModelId.GEMMA_SUMMARY_KO.name, result.actualModelId)
    }
}
