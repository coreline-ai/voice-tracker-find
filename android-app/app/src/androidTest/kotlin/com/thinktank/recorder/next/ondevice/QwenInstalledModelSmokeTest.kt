package com.thinktank.recorder.next.ondevice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.modelpack.ModelStore
import com.thinktank.recorder.ondevice.summary.QwenSummaryEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Optional end-to-end llama.cpp QA against a model installed by the product UI.
 *
 * CI without the 537MB model skips this test; device release QA installs the
 * pinned model through the model manager before executing it.
 */
@RunWith(AndroidJUnit4::class)
class QwenInstalledModelSmokeTest {
    @Test
    fun installedQwenModelCreatesGroundedKoreanSummary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ModelStore(context)
        val descriptor = ModelCatalog.get(ModelId.QWEN_SUMMARY_KO)
        assumeTrue("Qwen QA 모델이 설치되지 않아 테스트를 건너뜁니다", store.snapshot(descriptor).ready)
        val transcript = """
            오늘 제품 회의에서 새 녹음 화면의 출시일은 8월 15일로 확정했다.
            민수는 금요일까지 배포 체크리스트를 작성하기로 했다.
            모든 음성 전사와 요약은 네트워크로 보내지 않고 휴대전화 안에서만 처리한다.
        """.trimIndent()

        val startedAt = System.nanoTime()
        val result = QwenSummaryEngine(context, store).summarize(transcript)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        println(
            "Qwen native smoke: ${elapsedMs}ms, title=${result.title}, " +
                "bullets=${result.bullets}, actions=${result.actionItems}",
        )
        assertEquals(SummaryEngineType.QWEN_LOCAL, result.engine)
        assertTrue(result.title.isNotBlank())
        assertTrue(result.bullets.isNotEmpty())
        assertTrue(result.sourceHash.isNotBlank())
    }
}
