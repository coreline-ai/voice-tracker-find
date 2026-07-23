package com.thinktank.recorder.next.recording

import android.content.Context
import android.media.MediaMetadataRetriever
import com.thinktank.recorder.next.data.local.ChunkEntity
import com.thinktank.recorder.next.data.local.ChunkState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FinalizedFile(
    val file: File,
    val size: Long,
    val durationMs: Long?,
    val sha256: String,
)

@Singleton
class RecordingFileManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    val directory: File = File(context.filesDir, "recordings").apply { mkdirs() }

    fun createPartFile(
        extension: String = "m4a",
        uuid: String = UUID.randomUUID().toString(),
    ): Pair<String, File> {
        require(extension.matches(Regex("""[a-z0-9]{2,5}""")))
        val time = FORMAT.format(Instant.now())
        return uuid to File(directory, "rec_${time}_${uuid}.$extension.part")
    }

    suspend fun finalize(part: File): FinalizedFile = withContext(Dispatchers.IO) {
        require(part.name.endsWith(".part"))
        require(part.isFile && part.length() > 0L)
        val target = File(part.parentFile, part.name.removeSuffix(".part"))
        check(!target.exists())
        check(part.renameTo(target)) { "녹음 파일 rename 실패" }
        val duration = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(target.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
            } finally {
                retriever.release()
            }
        }.getOrNull()
        FinalizedFile(
            file = target,
            size = target.length(),
            durationMs = duration,
            sha256 = sha256(target),
        )
    }

    suspend fun quarantine(part: File): File = withContext(Dispatchers.IO) {
        val quarantine = File(directory, "quarantine").apply { mkdirs() }
        val target = File(quarantine, part.name)
        if (part.exists()) part.renameTo(target)
        target
    }

    suspend fun reconcile(unfinished: List<ChunkEntity>): List<ChunkEntity> =
        withContext(Dispatchers.IO) {
            unfinished.map { chunk ->
                val file = File(chunk.path)
                when {
                    !file.exists() -> chunk.copy(
                        state = ChunkState.FAILED,
                        lastError = "FILE_MISSING",
                    )
                    file.name.endsWith(".part") -> {
                        quarantine(file)
                        chunk.copy(
                            state = ChunkState.QUARANTINED,
                            lastError = "INTERRUPTED_RECORDING",
                        )
                    }
                    else -> chunk
                }
            }
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)
    }
}
