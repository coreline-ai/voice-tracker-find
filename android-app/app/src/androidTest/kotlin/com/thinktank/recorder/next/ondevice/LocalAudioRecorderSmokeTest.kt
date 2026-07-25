package com.thinktank.recorder.next.ondevice

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thinktank.recorder.ondevice.recording.LocalAudioRecorder
import com.thinktank.recorder.ondevice.stt.Pcm16WavReader
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAudioRecorderSmokeTest {
    @Test
    fun recordsAValid16KhzMonoWav() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
        val output = File(context.cacheDir, "local-audio-recorder-smoke.wav")
        output.delete()
        val recorder = LocalAudioRecorder()

        val recorded = try {
            recorder.start(output)
            delay(1_000)
            recorder.stop()
        } finally {
            recorder.release()
        }

        val info = Pcm16WavReader.inspect(recorded)
        assertEquals(16_000, info.sampleRate)
        assertEquals(1, info.channels)
        assertTrue(info.durationMs >= 500)
        assertTrue(recorded.length() > LocalAudioRecorder.WAV_HEADER_BYTES)
        recorded.delete()
        Unit
    }
}
