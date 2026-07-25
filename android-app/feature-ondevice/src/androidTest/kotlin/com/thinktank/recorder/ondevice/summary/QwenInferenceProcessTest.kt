package com.thinktank.recorder.ondevice.summary

import android.app.ActivityManager
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.modelpack.ModelStore
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QwenInferenceProcessTest {
    @Test
    fun recoverableFailureRunsOutsideMainProcessAndTerminatesWorkerProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ModelStore(context)
        val descriptor = ModelCatalog.get(ModelId.QWEN_SUMMARY_KO)
        assumeFalse(
            "실제 Qwen 모델이 설치된 장치에서는 기존 native smoke가 별도 프로세스를 검증합니다",
            store.snapshot(descriptor).ready,
        )
        val client = QwenInferenceClient(context)
        val model = File(store.installDir(descriptor.id), "model.gguf")

        runCatching { client.summarize(model.absolutePath, "별도 프로세스 실패 경로 확인") }
        val remotePid = client.lastServicePid.get()
        assertTrue(remotePid > 0)
        assertNotEquals(Process.myPid(), remotePid)

        val activityManager = context.getSystemService(ActivityManager::class.java)
        repeat(30) {
            val alive = activityManager.runningAppProcesses.orEmpty().any { it.pid == remotePid }
            if (!alive) return@runBlocking
            delay(100)
        }
        val stillAlive = activityManager.runningAppProcesses.orEmpty().any { it.pid == remotePid }
        assertTrue("Qwen worker process가 종료되지 않았습니다", !stillAlive)
    }
}
