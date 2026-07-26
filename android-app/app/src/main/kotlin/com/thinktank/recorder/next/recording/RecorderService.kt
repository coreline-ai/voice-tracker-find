package com.thinktank.recorder.next.recording

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.thinktank.recorder.next.MainActivity
import com.thinktank.recorder.next.R
import com.thinktank.recorder.next.ThinkTankApplication
import com.thinktank.recorder.next.data.local.ChunkEntity
import com.thinktank.recorder.next.data.local.ChunkState
import com.thinktank.recorder.next.data.local.RecordingDao
import com.thinktank.recorder.next.data.local.RecordingSessionEntity
import com.thinktank.recorder.next.data.local.RecordingState
import com.thinktank.recorder.next.data.settings.AppPreferences
import com.thinktank.recorder.next.data.storage.AppStorageMonitor
import com.thinktank.recorder.ondevice.runtime.MicrophoneArbiter
import com.thinktank.recorder.ondevice.runtime.MicrophoneOwner
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private interface CaptureEngine {
    fun maxAmplitude(): Int
    fun stop()
    fun release()
}

private class MediaRecorderCapture(
    private val recorder: MediaRecorder,
) : CaptureEngine {
    override fun maxAmplitude(): Int = recorder.maxAmplitude
    override fun stop() = recorder.stop()
    override fun release() = recorder.release()
}

@SuppressLint("MissingPermission")
private class PcmWavCapture(
    private val file: File,
    audioSource: Int,
) : CaptureEngine {
    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val amplitude = AtomicInteger(0)
    private val workerError = AtomicReference<Throwable?>(null)
    private val format = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()
    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    ).also { check(it > 0) { "AudioRecord buffer를 만들 수 없습니다: $it" } }
        .coerceAtLeast(4_096)
    private val recorder: AudioRecord
    private val output: FileOutputStream
    private val worker: Thread

    init {
        var createdRecorder: AudioRecord? = null
        var createdOutput: FileOutputStream? = null
        try {
            val audioRecord = AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize * 2)
                .build()
            createdRecorder = audioRecord
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord 초기화에 실패했습니다"
            }
            val stream = FileOutputStream(file)
            createdOutput = stream
            recorder = audioRecord
            output = stream
            stream.write(ByteArray(WAV_HEADER_SIZE))
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord 시작에 실패했습니다"
            }
            running.set(true)
            worker = Thread(
                {
                    try {
                        val buffer = ByteArray(bufferSize)
                        while (running.get()) {
                            val count = recorder.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING,
                            )
                            when {
                                count > 0 -> {
                                    try {
                                        output.write(buffer, 0, count)
                                    } catch (error: Throwable) {
                                        // A write failure corrupts the current WAV even when a
                                        // concurrent stop has already flipped [running]. Keep it
                                        // as the terminal cause instead of producing a READY file.
                                        workerError.compareAndSet(null, error)
                                        break
                                    }
                                    amplitude.set(peakAmplitude(buffer, count))
                                }
                                running.get() -> error("AudioRecord read 실패: $count")
                            }
                        }
                    } catch (error: Throwable) {
                        if (running.get()) workerError.compareAndSet(null, error)
                    } finally {
                        running.set(false)
                    }
                },
                "thinktank-pcm-writer",
            ).apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            running.set(false)
            createdRecorder?.let { active ->
                runCatching { active.stop() }
                runCatching { active.release() }
            }
            createdOutput?.let { runCatching { it.close() } }
            file.delete()
            throw error
        }
    }

    override fun maxAmplitude(): Int {
        workerError.get()?.let { throw it }
        return amplitude.get()
    }

    @Synchronized
    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        var failure: Throwable? = null
        fun attempt(block: () -> Unit) {
            runCatching(block).exceptionOrNull()?.let { error ->
                if (failure == null) failure = error
            }
        }

        attempt { recorder.stop() }
        attempt { worker.join(5_000) }
        if (worker.isAlive && failure == null) {
            failure = IllegalStateException("PCM writer 종료 시간이 초과했습니다")
        }
        attempt { output.flush() }
        attempt { output.fd.sync() }
        attempt { output.close() }
        workerError.get()?.let { error -> if (failure == null) failure = error }
        if (failure == null) attempt { writeWavHeader(file) }
        failure?.let { throw it }
    }

    override fun release() {
        var failure: Throwable? = null
        if (!stopped.get()) {
            runCatching { stop() }.exceptionOrNull()?.let { failure = it }
        }
        runCatching { recorder.release() }.exceptionOrNull()?.let { error ->
            if (failure == null) failure = error
        }
        failure?.let { throw it }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val WAV_HEADER_SIZE = 44

        fun peakAmplitude(bytes: ByteArray, count: Int): Int {
            var peak = 0
            var index = 0
            while (index + 1 < count) {
                val sample = (
                    (bytes[index].toInt() and 0xFF) or
                        (bytes[index + 1].toInt() shl 8)
                    ).toShort().toInt()
                peak = maxOf(peak, kotlin.math.abs(sample))
                index += 2
            }
            return peak
        }

        fun writeWavHeader(file: File) {
            val dataSize = (file.length() - WAV_HEADER_SIZE).coerceAtLeast(0)
            check(dataSize <= 0xFFFF_FFFFL) { "WAV 파일이 4GB를 초과했습니다" }
            val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
            val header = ByteBuffer.allocate(WAV_HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray(Charsets.US_ASCII))
                .putInt((dataSize + 36).toInt())
                .put("WAVE".toByteArray(Charsets.US_ASCII))
                .put("fmt ".toByteArray(Charsets.US_ASCII))
                .putInt(16)
                .putShort(1.toShort())
                .putShort(CHANNELS.toShort())
                .putInt(SAMPLE_RATE)
                .putInt(byteRate)
                .putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort())
                .putShort(BITS_PER_SAMPLE.toShort())
                .put("data".toByteArray(Charsets.US_ASCII))
                .putInt(dataSize.toInt())
                .array()
            RandomAccessFile(file, "rw").use {
                it.seek(0)
                it.write(header)
                it.fd.sync()
            }
        }
    }
}

@AndroidEntryPoint
class RecorderService : Service() {
    @Inject lateinit var dao: RecordingDao
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var files: RecordingFileManager
    @Inject lateinit var runtime: RecordingRuntime
    @Inject lateinit var storage: AppStorageMonitor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var commandActor: RecorderCommandActor
    private val latestStartIntentId = AtomicInteger(0)
    private var recordingJob: Job? = null
    private var activeStartId: Int? = null
    private var reconciliationCompleted = false
    private var reconciliationFailure: Throwable? = null
    private var captureEngine: CaptureEngine? = null
    private var currentPart: File? = null
    private var currentChunk: ChunkEntity? = null
    private var sessionId: String? = null
    private var microphoneHeld = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        commandActor = RecorderCommandActor(scope, ::handleCommand)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> commandActor.send(RecorderServiceCommand.Stop(startId))
            ACTION_START -> {
                runtime.clearCommandError()
                latestStartIntentId.updateAndGet { previous -> maxOf(previous, startId) }
                // Android requires foreground entry immediately after a microphone-service START.
                // All mutable recorder state is still owned by the FIFO actor below.
                startForeground(NOTIFICATION_ID, notification("녹음을 준비하고 있습니다"))
                commandActor.send(RecorderServiceCommand.Start(startId))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        commandActor.close()
        recordingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun handleCommand(command: RecorderServiceCommand) {
        when (command) {
            is RecorderServiceCommand.Start -> handleStart(command.startId)
            is RecorderServiceCommand.Stop -> handleStop(command.startId)
            is RecorderServiceCommand.RunnerCompleted -> handleRunnerCompleted(command.runner)
        }
    }

    private suspend fun handleStart(startId: Int) {
        activeStartId = startId
        if (!reconciliationCompleted) {
            reconciliationFailure = runCatching {
                files.reconcile(dao.unfinishedChunks()).forEach { dao.updateChunk(it) }
            }.exceptionOrNull()
            reconciliationCompleted = true
        }
        reconciliationFailure?.let { error ->
            val message = "기존 녹음 파일 복구에 실패했습니다: ${error.message.orEmpty()}"
            runtime.reportCommandError(message)
            updateNotification("녹음 파일 복구에 실패했습니다")
            finishService(startId)
            return
        }
        if (recordingJob?.isActive == true) return

        lateinit var runner: Job
        runner = scope.launch {
            runRecordingSession()
        }
        recordingJob = runner
        runner.invokeOnCompletion {
            runCatching { commandActor.send(RecorderServiceCommand.RunnerCompleted(runner)) }
        }
    }

    private suspend fun handleStop(startId: Int) {
        val runner = recordingJob
        recordingJob = null
        activeStartId = null
        runner?.cancelAndJoin()
        finishService(startId)
    }

    private fun handleRunnerCompleted(runner: Job) {
        if (recordingJob !== runner) return
        recordingJob = null
        val startId = activeStartId ?: return
        activeStartId = null
        finishService(startId)
    }

    private fun finishService(startId: Int) {
        // A newer START may already have reached Android before this FIFO actor handles an
        // earlier STOP/completion. Do not remove its foreground notification or stop service.
        if (latestStartIntentId.get() > startId) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runRecordingSession() {
        val id = UUID.randomUUID().toString()
        sessionId = id
        dao.upsertSession(
            RecordingSessionEntity(
                id = id,
                state = RecordingState.PREPARING,
                startedAt = System.currentTimeMillis(),
            ),
        )
        var failure: String? = null
        try {
            while (coroutineContext.isActive) {
                val settings = preferences.current()
                val isInsideWindow = RecordingWindow.contains(
                    now = LocalTime.now(),
                    startMinutes = settings.scheduleStartMinutes,
                    endMinutes = settings.scheduleEndMinutes,
                    enabled = settings.scheduleEnabled,
                )
                if (!isInsideWindow) {
                    check(stopChunk()) { "녹음 파일 마감에 실패했습니다" }
                    dao.setSessionState(id, RecordingState.WAITING)
                    updateNotification("예약 시간까지 대기 중")
                    delay(WINDOW_CHECK_MS)
                    continue
                }

                if (captureEngine == null) startChunk(id, settings.chunkMinutes)
                val chunkStarted = SystemClock.elapsedRealtime()
                var nextWindowCheckAt = chunkStarted + WINDOW_CHECK_MS
                while (coroutineContext.isActive && captureEngine != null) {
                    runtime.updateAmplitude(captureEngine?.maxAmplitude() ?: 0)
                    val nowElapsed = SystemClock.elapsedRealtime()
                    val elapsed = nowElapsed - chunkStarted
                    if (elapsed >= TimeUnit.MINUTES.toMillis(settings.chunkMinutes.toLong())) {
                        check(stopChunk()) { "녹음 청크 마감에 실패했습니다" }
                        break
                    }
                    if (nowElapsed >= nextWindowCheckAt) {
                        nextWindowCheckAt = nowElapsed + WINDOW_CHECK_MS
                        if (!isRecordingWindowOpen()) {
                            check(stopChunk()) { "시간 창 종료 후 녹음 파일 마감에 실패했습니다" }
                            dao.setSessionState(id, RecordingState.WAITING)
                            updateNotification("예약 시간까지 대기 중")
                            break
                        }
                    }
                    if (elapsed % 1_000L < AMPLITUDE_POLL_MS) {
                        updateNotification("녹음 중 · ${formatElapsed(elapsed)}")
                    }
                    delay(AMPLITUDE_POLL_MS)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val code = error::class.simpleName ?: "RECORDER_ERROR"
            failure = "$code: ${error.message.orEmpty()}"
            updateNotification("녹음을 계속할 수 없습니다")
        } finally {
            withContext(NonCancellable) {
                val finalized = runCatching { stopChunk() }.getOrDefault(false)
                val outcome = terminalRecordingOutcome(failure, finalized)
                dao.setSessionState(
                    id,
                    outcome.state,
                    stoppedAt = System.currentTimeMillis(),
                    error = outcome.error,
                )
                runtime.reset()
                releaseWakeLock()
                releaseMicrophone()
                captureEngine = null
                currentPart = null
                currentChunk = null
                sessionId = null
            }
        }
    }

    private suspend fun isRecordingWindowOpen(): Boolean {
        val settings = preferences.current()
        return RecordingWindow.contains(
            now = LocalTime.now(),
            startMinutes = settings.scheduleStartMinutes,
            endMinutes = settings.scheduleEndMinutes,
            enabled = settings.scheduleEnabled,
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun startChunk(session: String, chunkMinutes: Int) {
        dao.setSessionState(session, RecordingState.PREPARING)
        val storageCheck = storage.recordingCheck(chunkMinutes)
        check(storageCheck.canStart) {
            "저장공간이 부족합니다. 녹음 시작에는 최소 ${formatBytes(storageCheck.requiredBytes)}의 " +
                "여유 공간이 필요합니다."
        }
        val id = UUID.randomUUID().toString()
        var part = files.createPartFile(extension = "m4a", uuid = id).second
        var chunk = ChunkEntity(
            id = id,
            sessionId = session,
            uploadId = UUID.randomUUID().toString(),
            path = part.absolutePath,
            state = ChunkState.RECORDING,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertChunk(chunk)
        currentPart = part
        currentChunk = chunk
        try {
            if (!MicrophoneArbiter.tryAcquire(MicrophoneOwner.MAIN_RECORDER)) {
                val error = "로컬 AI가 마이크를 사용 중입니다. 로컬 작업을 마친 뒤 다시 시작하세요."
                runtime.reportCommandError(error)
                updateNotification("로컬 AI가 마이크를 사용 중입니다")
                throw IllegalStateException(error)
            }
            microphoneHeld = true
            val engine = runCatching {
                createStartedMediaRecorder(
                    part = part,
                    constrained = true,
                    audioSource = MediaRecorder.AudioSource.MIC,
                )
            }.recoverCatching {
                createStartedMediaRecorder(
                    part = part,
                    constrained = false,
                    audioSource = MediaRecorder.AudioSource.MIC,
                )
            }.recoverCatching {
                createStartedMediaRecorder(
                    part = part,
                    constrained = false,
                    audioSource = MediaRecorder.AudioSource.DEFAULT,
                )
            }.getOrElse {
                part.delete()
                part = files.createPartFile(extension = "wav", uuid = id).second
                chunk = chunk.copy(path = part.absolutePath)
                dao.updateChunk(chunk)
                currentPart = part
                currentChunk = chunk
                createStartedPcmRecorder(part)
            }
            captureEngine = engine
            acquireWakeLock(chunkMinutes)
            dao.setSessionState(session, RecordingState.RECORDING, id)
            updateNotification("녹음 중 · 00:00")
        } catch (error: Throwable) {
            captureEngine?.let { engine ->
                runCatching { engine.stop() }
                runCatching { engine.release() }
            }
            captureEngine = null
            val quarantined = files.quarantine(part)
            dao.updateChunk(
                chunk.copy(
                    path = quarantined.absolutePath,
                    state = ChunkState.FAILED,
                    lastError = error::class.simpleName ?: "PREPARE_FAILED",
                ),
            )
            currentPart = null
            currentChunk = null
            releaseMicrophone()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun createStartedMediaRecorder(
        part: File,
        constrained: Boolean,
        audioSource: Int,
    ): CaptureEngine {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            MediaRecorder()
        }
        try {
            recorder.setAudioSource(audioSource)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            if (constrained) {
                recorder.setAudioSamplingRate(16_000)
                recorder.setAudioEncodingBitRate(32_000)
            }
            recorder.setOutputFile(part.absolutePath)
            recorder.prepare()
            recorder.start()
            return MediaRecorderCapture(recorder)
        } catch (error: Throwable) {
            runCatching { recorder.release() }
            part.delete()
            throw error
        }
    }

    @SuppressLint("MissingPermission")
    private fun createStartedPcmRecorder(part: File): CaptureEngine =
        runCatching {
            PcmWavCapture(part, MediaRecorder.AudioSource.MIC)
        }.recoverCatching {
            PcmWavCapture(part, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        }.getOrThrow()

    private suspend fun stopChunk(): Boolean {
        val engine = captureEngine ?: return true
        val part = currentPart
        val chunk = currentChunk
        captureEngine = null
        if (chunk != null) {
            dao.updateChunk(chunk.copy(state = ChunkState.FINALIZING))
            sessionId?.let { dao.setSessionState(it, RecordingState.FINALIZING, chunk.id) }
        }
        try {
            engine.stop()
            engine.release()
            if (part != null && chunk != null) {
                val completed = files.finalize(part)
                dao.updateChunk(
                    chunk.copy(
                        path = completed.file.absolutePath,
                        state = ChunkState.READY,
                        finalizedAt = System.currentTimeMillis(),
                        sizeBytes = completed.size,
                        durationMs = completed.durationMs,
                        sha256 = completed.sha256,
                    ),
                )
            }
            return true
        } catch (error: Throwable) {
            runCatching { engine.release() }
            val quarantined = part?.let { files.quarantine(it) }
            if (chunk != null) {
                dao.updateChunk(
                    chunk.copy(
                        path = quarantined?.absolutePath ?: chunk.path,
                        state = ChunkState.QUARANTINED,
                        lastError = error::class.simpleName ?: "FINALIZE_FAILED",
                    ),
                )
            }
            return false
        } finally {
            currentPart = null
            currentChunk = null
            runtime.reset()
            releaseWakeLock()
            releaseMicrophone()
        }
    }

    private fun releaseMicrophone() {
        if (!microphoneHeld) return
        microphoneHeld = false
        MicrophoneArbiter.release(MicrophoneOwner.MAIN_RECORDER)
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock(chunkMinutes: Int) {
        releaseWakeLock()
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:recording",
        ).apply {
            setReferenceCounted(false)
            acquire(TimeUnit.MINUTES.toMillis(chunkMinutes.toLong() + 2))
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun updateNotification(status: String) {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(status))
    }

    private fun notification(status: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, ThinkTankApplication.RECORDING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(
                R.drawable.ic_notification_mic,
                getString(R.string.recording_notification_stop),
                stop,
            )
            .build()
    }

    private fun formatElapsed(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
        else -> "$bytes B"
    }

    companion object {
        const val ACTION_START = "com.thinktank.recorder.next.action.START"
        const val ACTION_STOP = "com.thinktank.recorder.next.action.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val WINDOW_CHECK_MS = 30_000L
        private const val AMPLITUDE_POLL_MS = 250L
    }
}
