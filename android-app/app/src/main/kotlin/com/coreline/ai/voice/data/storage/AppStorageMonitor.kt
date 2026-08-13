package com.coreline.ai.voice.data.storage

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class AppStorageSnapshot(
    val recordingBytes: Long,
    val localAiModelBytes: Long,
    val localAiAudioBytes: Long,
    val databaseBytes: Long,
    val availableBytes: Long,
    val totalBytes: Long,
) {
    val appBytes: Long
        get() = recordingBytes + localAiModelBytes + localAiAudioBytes + databaseBytes

    val lowFreeSpace: Boolean
        get() = availableBytes < StoragePolicy.LOW_FREE_SPACE_BYTES
}

data class RecordingSpaceCheck(
    val availableBytes: Long,
    val requiredBytes: Long,
) {
    val canStart: Boolean
        get() = availableBytes >= requiredBytes
}

/**
 * Keeps capacity decisions independent from UI wording and recorder implementation details.
 * The estimate deliberately leaves headroom for MediaRecorder fallback profiles, which can use
 * more space than the preferred 16 kHz AAC profile.
 */
object StoragePolicy {
    const val MEBIBYTE = 1024L * 1024L
    const val LOW_FREE_SPACE_BYTES = 512L * MEBIBYTE
    private const val RECORDING_BASE_HEADROOM_BYTES = 64L * MEBIBYTE
    private const val RECORDING_BYTES_PER_MINUTE = 4L * MEBIBYTE

    fun requiredBytesForRecording(chunkMinutes: Int): Long {
        require(chunkMinutes in 5..120)
        return maxOf(
            LOW_FREE_SPACE_BYTES,
            RECORDING_BASE_HEADROOM_BYTES + chunkMinutes * RECORDING_BYTES_PER_MINUTE,
        )
    }

    fun recordingCheck(availableBytes: Long, chunkMinutes: Int): RecordingSpaceCheck =
        RecordingSpaceCheck(
            availableBytes = availableBytes.coerceAtLeast(0),
            requiredBytes = requiredBytesForRecording(chunkMinutes),
        )
}

@Singleton
class AppStorageMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun snapshot(): AppStorageSnapshot {
        val files = context.filesDir
        val stat = StatFs(files.absolutePath)
        return AppStorageSnapshot(
            recordingBytes = directoryBytes(File(files, "recordings")),
            localAiModelBytes = directoryBytes(File(files, "ondevice/models")),
            localAiAudioBytes = directoryBytes(File(files, "ondevice/recordings")) +
                directoryBytes(File(files, "ondevice/temp")),
            databaseBytes = databaseBytes("airvoice.db") + databaseBytes("ondevice.db"),
            availableBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
        )
    }

    fun recordingCheck(chunkMinutes: Int): RecordingSpaceCheck =
        StoragePolicy.recordingCheck(snapshot().availableBytes, chunkMinutes)

    private fun databaseBytes(name: String): Long {
        val database = context.getDatabasePath(name)
        return listOf(database, File(database.parentFile, "${database.name}-wal"), File(database.parentFile, "${database.name}-shm"))
            .sumOf { file -> file.takeIf(File::isFile)?.length() ?: 0L }
    }

    private fun directoryBytes(directory: File): Long =
        if (!directory.isDirectory) 0L else directory.walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)
}
