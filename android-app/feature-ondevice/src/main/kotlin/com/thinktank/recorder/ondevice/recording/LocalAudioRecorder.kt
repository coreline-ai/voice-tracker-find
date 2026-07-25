package com.thinktank.recorder.ondevice.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LocalRecorderState {
    IDLE,
    STARTING,
    RECORDING,
    STOPPING,
    CANCELLING,
    RELEASED,
}

internal interface AudioRecordHandle {
    val initialized: Boolean
    val recording: Boolean
    fun start()
    fun read(buffer: ByteArray): Int
    fun stop()
    fun release()
}

internal fun interface AudioRecordFactory {
    fun create(bufferSize: Int): AudioRecordHandle
}

internal fun interface MinimumBufferSizeProvider {
    fun get(): Int
}

/**
 * Screen-scoped 16 kHz mono PCM recorder used only by Moonshine.
 *
 * Start/stop/cancel are serialized and RECORDING is published only after the
 * framework recorder has started successfully.
 */
class LocalAudioRecorder internal constructor(
    dispatcher: CoroutineDispatcher,
    private val minimumBufferSize: MinimumBufferSizeProvider,
    private val recorderFactory: AudioRecordFactory,
) {
    constructor(dispatcher: CoroutineDispatcher = Dispatchers.IO) : this(
        dispatcher = dispatcher,
        minimumBufferSize = MinimumBufferSizeProvider {
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        },
        recorderFactory = AudioRecordFactory(::createFrameworkRecorder),
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val operationMutex = Mutex()
    private val recording = AtomicBoolean(false)
    private val recorder = AtomicReference<AudioRecordHandle?>(null)
    private val failure = AtomicReference<Throwable?>(null)
    @Volatile
    private var writeJob: Job? = null
    @Volatile
    private var outputFile: File? = null
    @Volatile
    var state: LocalRecorderState = LocalRecorderState.IDLE
        private set

    val isRecording: Boolean
        get() = state == LocalRecorderState.RECORDING && recording.get()

    @SuppressLint("MissingPermission")
    suspend fun start(
        file: File,
        onFailure: (Throwable) -> Unit = {},
    ) = operationMutex.withLock {
        check(state == LocalRecorderState.IDLE) { "이미 로컬 녹음이 실행 중입니다" }
        state = LocalRecorderState.STARTING
        var created: AudioRecordHandle? = null
        try {
            file.parentFile?.mkdirs()
            if (file.exists()) check(file.delete()) { "기존 임시 녹음을 지울 수 없습니다" }
            val minBuffer = minimumBufferSize.get()
            check(minBuffer > 0) { "이 기기에서 16kHz PCM 녹음을 준비할 수 없습니다" }
            val bufferSize = maxOf(minBuffer * 2, 16_384)
            created = recorderFactory.create(bufferSize)
            check(created.initialized) { "마이크 녹음기를 초기화하지 못했습니다" }
            created.start()

            outputFile = file
            recorder.set(created)
            failure.set(null)
            recording.set(true)
            state = LocalRecorderState.RECORDING
            writeJob = scope.launch {
                writePcm(file, created, bufferSize, onFailure)
            }
        } catch (error: Throwable) {
            recording.set(false)
            runCatching { created?.stop() }
            runCatching { created?.release() }
            recorder.compareAndSet(created, null)
            writeJob?.cancel()
            writeJob = null
            outputFile = null
            failure.set(null)
            file.delete()
            state = LocalRecorderState.IDLE
            throw error
        }
    }

    suspend fun stop(): File = operationMutex.withLock {
        check(state == LocalRecorderState.RECORDING) { "진행 중인 로컬 녹음이 없습니다" }
        val file = requireNotNull(outputFile)
        state = LocalRecorderState.STOPPING
        stopAndReleaseRecorder()
        writeJob?.join()
        val writeFailure = failure.get()
        if (writeFailure != null) {
            file.delete()
            clearReferences()
            throw IllegalStateException("로컬 녹음에 실패했습니다", writeFailure)
        }
        if (file.length() <= WAV_HEADER_BYTES) {
            file.delete()
            clearReferences()
            error("녹음된 음성이 없습니다")
        }
        clearReferences()
        file
    }

    suspend fun cancelAndDelete() = operationMutex.withLock {
        if (state == LocalRecorderState.RELEASED) return@withLock
        if (state == LocalRecorderState.IDLE) return@withLock
        state = LocalRecorderState.CANCELLING
        val file = outputFile
        stopAndReleaseRecorder()
        writeJob?.join()
        file?.delete()
        clearReferences()
    }

    /**
     * Non-blocking final safety net for ViewModel teardown.
     *
     * Normal terminal paths use suspend stop/cancel and join the writer. This
     * method atomically detaches the framework recorder, cancels the writer,
     * and never blocks the main thread.
     */
    fun release() {
        if (state == LocalRecorderState.RELEASED) return
        state = LocalRecorderState.RELEASED
        recording.set(false)
        writeJob?.cancel()
        recorder.getAndSet(null)?.let { active ->
            runCatching { if (active.recording) active.stop() }
            runCatching { active.release() }
        }
        outputFile?.delete()
        outputFile = null
        failure.set(null)
        writeJob = null
        scope.cancel()
    }

    private fun writePcm(
        file: File,
        active: AudioRecordHandle,
        bufferSize: Int,
        onFailure: (Throwable) -> Unit,
    ) {
        var pcmBytes = 0L
        try {
            FileOutputStream(file).use { output ->
                output.write(ByteArray(WAV_HEADER_BYTES))
                val buffer = ByteArray(bufferSize)
                while (scope.isActive && recording.get()) {
                    val read = active.read(buffer)
                    if (!recording.get() && read <= 0) break
                    when {
                        read > 0 -> {
                            output.write(buffer, 0, read)
                            pcmBytes += read
                        }
                        read == AudioRecord.ERROR_DEAD_OBJECT ->
                            error("마이크 연결이 끊어졌습니다")
                        read < 0 -> error("마이크 입력을 읽지 못했습니다: $read")
                    }
                }
                output.flush()
            }
        } catch (error: Throwable) {
            failure.compareAndSet(null, error)
            recording.set(false)
            runCatching { onFailure(error) }
        } finally {
            runCatching { writeWavHeader(file, pcmBytes) }
                .exceptionOrNull()
                ?.let { failure.compareAndSet(null, it) }
        }
    }

    private fun stopAndReleaseRecorder() {
        recording.set(false)
        recorder.getAndSet(null)?.let { active ->
            runCatching { if (active.recording) active.stop() }
            runCatching { active.release() }
        }
    }

    private fun clearReferences() {
        recording.set(false)
        writeJob = null
        outputFile = null
        failure.set(null)
        if (state != LocalRecorderState.RELEASED) state = LocalRecorderState.IDLE
    }

    private fun writeWavHeader(file: File, pcmBytes: Long) {
        if (!file.isFile) return
        RandomAccessFile(file, "rw").use { wav ->
            wav.seek(0)
            wav.writeAscii("RIFF")
            wav.writeLittleEndianInt((pcmBytes + 36).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            wav.writeAscii("WAVE")
            wav.writeAscii("fmt ")
            wav.writeLittleEndianInt(16)
            wav.writeLittleEndianShort(1)
            wav.writeLittleEndianShort(CHANNELS)
            wav.writeLittleEndianInt(SAMPLE_RATE)
            wav.writeLittleEndianInt(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE)
            wav.writeLittleEndianShort(CHANNELS * BYTES_PER_SAMPLE)
            wav.writeLittleEndianShort(BITS_PER_SAMPLE)
            wav.writeAscii("data")
            wav.writeLittleEndianInt(pcmBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    private fun RandomAccessFile.writeAscii(value: String) =
        write(value.toByteArray(Charsets.US_ASCII))

    private fun RandomAccessFile.writeLittleEndianInt(value: Int) {
        write(
            byteArrayOf(
                value.toByte(),
                (value ushr 8).toByte(),
                (value ushr 16).toByte(),
                (value ushr 24).toByte(),
            ),
        )
    }

    private fun RandomAccessFile.writeLittleEndianShort(value: Int) {
        write(byteArrayOf(value.toByte(), (value ushr 8).toByte()))
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val BYTES_PER_SAMPLE = 2
        const val WAV_HEADER_BYTES = 44

        @SuppressLint("MissingPermission")
        private fun createFrameworkRecorder(bufferSize: Int): AudioRecordHandle {
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            return object : AudioRecordHandle {
                override val initialized: Boolean
                    get() = audioRecord.state == AudioRecord.STATE_INITIALIZED
                override val recording: Boolean
                    get() = audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING

                override fun start() = audioRecord.startRecording()

                override fun read(buffer: ByteArray): Int =
                    audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)

                override fun stop() = audioRecord.stop()

                override fun release() = audioRecord.release()
            }
        }
    }
}
