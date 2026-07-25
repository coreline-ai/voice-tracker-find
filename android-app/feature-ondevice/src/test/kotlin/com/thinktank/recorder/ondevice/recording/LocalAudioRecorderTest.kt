package com.thinktank.recorder.ondevice.recording

import java.io.File
import android.media.AudioRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalAudioRecorderTest {
    @Test
    fun invalidMinimumBufferRestoresIdleAndDeletesPartialFile() {
        val output = File.createTempFile("invalid-buffer", ".wav").apply { writeText("partial") }
        val recorder = LocalAudioRecorder(
            dispatcher = Dispatchers.Unconfined,
            minimumBufferSize = MinimumBufferSizeProvider { -1 },
            recorderFactory = AudioRecordFactory { error("factory must not run") },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { recorder.start(output) }
        }
        assertEquals(LocalRecorderState.IDLE, recorder.state)
        assertFalse(output.exists())
        recorder.release()
    }

    @Test
    fun startFailureReleasesRecorderOnceAndRestoresIdle() {
        val output = File.createTempFile("start-failure", ".wav")
        val fake = FakeAudioRecord(startFailure = IllegalStateException("start failed"))
        val recorder = LocalAudioRecorder(
            dispatcher = Dispatchers.Unconfined,
            minimumBufferSize = MinimumBufferSizeProvider { 8_192 },
            recorderFactory = AudioRecordFactory { fake },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { recorder.start(output) }
        }
        assertEquals(1, fake.releaseCount)
        assertEquals(LocalRecorderState.IDLE, recorder.state)
        assertFalse(output.exists())
        recorder.release()
        assertEquals(1, fake.releaseCount)
    }

    @Test
    fun readFailureIsReportedAndCancelRemovesPartialWav() = runBlocking {
        val output = File.createTempFile("read-failure", ".wav").apply { delete() }
        val fake = object : AudioRecordHandle {
            override val initialized = true
            override var recording = false
            var releaseCount = 0
            override fun start() {
                recording = true
            }
            override fun read(buffer: ByteArray): Int = AudioRecord.ERROR_DEAD_OBJECT
            override fun stop() {
                recording = false
            }
            override fun release() {
                releaseCount += 1
            }
        }
        var reported: Throwable? = null
        val recorder = LocalAudioRecorder(
            dispatcher = Dispatchers.Unconfined,
            minimumBufferSize = MinimumBufferSizeProvider { 8_192 },
            recorderFactory = AudioRecordFactory { fake },
        )

        recorder.start(output) { reported = it }
        assertEquals("마이크 연결이 끊어졌습니다", reported?.message)
        recorder.cancelAndDelete()
        assertEquals(LocalRecorderState.IDLE, recorder.state)
        assertFalse(output.exists())
        assertEquals(1, fake.releaseCount)
        recorder.release()
    }

    @Test
    fun stopAndCancelRaceReleasesFrameworkRecorderExactlyOnce() = runBlocking {
        val output = File.createTempFile("stop-cancel-race", ".wav").apply { delete() }
        val fake = object : AudioRecordHandle {
            override val initialized = true
            @Volatile
            override var recording = false
            @Volatile
            var releaseCount = 0
            override fun start() {
                recording = true
            }
            override fun read(buffer: ByteArray): Int {
                if (!recording) return 0
                buffer[0] = 1
                buffer[1] = 0
                Thread.sleep(2)
                return 2
            }
            override fun stop() {
                recording = false
            }
            override fun release() {
                releaseCount += 1
            }
        }
        val recorder = LocalAudioRecorder(
            dispatcher = Dispatchers.IO,
            minimumBufferSize = MinimumBufferSizeProvider { 8_192 },
            recorderFactory = AudioRecordFactory { fake },
        )
        recorder.start(output)
        delay(20)

        val stop = async(Dispatchers.Default) { runCatching { recorder.stop() } }
        val cancel = async(Dispatchers.Default) { runCatching { recorder.cancelAndDelete() } }
        stop.await()
        cancel.await()

        assertEquals(1, fake.releaseCount)
        assertEquals(LocalRecorderState.IDLE, recorder.state)
        recorder.release()
    }

    private class FakeAudioRecord(
        private val startFailure: Throwable? = null,
    ) : AudioRecordHandle {
        override val initialized = true
        override var recording = false
        var releaseCount = 0

        override fun start() {
            startFailure?.let { throw it }
            recording = true
        }

        override fun read(buffer: ByteArray): Int = 0

        override fun stop() {
            recording = false
        }

        override fun release() {
            releaseCount += 1
        }
    }
}
