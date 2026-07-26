package com.thinktank.recorder.ondevice.modelpack

import android.content.Context
import java.io.File
import org.json.JSONObject

data class InstalledModel(
    val descriptor: ModelDescriptor,
    val ready: Boolean,
    val installedBytes: Long,
    val installedAt: Long?,
)

class ModelStore(context: Context) {
    private val root = File(context.filesDir, "ondevice/models").apply { mkdirs() }
    val downloadRoot: File = File(root, ".downloads").apply { mkdirs() }

    fun installDir(id: ModelId): File = File(root, id.name.lowercase())

    fun stagingDir(id: ModelId): File = File(root, ".staging-${id.name.lowercase()}")

    fun backupDir(id: ModelId): File = File(root, ".backup-${id.name.lowercase()}")

    fun partialFile(id: ModelId): File = File(downloadRoot, "${id.name.lowercase()}.part")

    fun etagFile(id: ModelId): File = File(downloadRoot, "${id.name.lowercase()}.etag")

    fun partialBytes(id: ModelId): Long = partialFile(id).takeIf(File::isFile)?.length() ?: 0L

    fun snapshot(descriptor: ModelDescriptor): InstalledModel {
        val dir = installDir(descriptor.id)
        val marker = File(dir, MARKER)
        val markerJson = runCatching {
            if (marker.isFile) JSONObject(marker.readText()) else null
        }.getOrNull()
        val versionMatches = markerJson?.optString("version") == descriptor.version
        val hashMatches = markerJson?.optString("sourceSha256") == descriptor.expectedSha256
        val manifestFiles = markerJson?.optJSONArray("files")?.let { files ->
            buildMap {
                for (index in 0 until files.length()) {
                    val entry = files.optJSONObject(index) ?: continue
                    val name = entry.optString("name")
                    val size = entry.optLong("size", -1L)
                    if (name.isNotBlank() && size >= 0) put(name, size)
                }
            }
        }.orEmpty()
        val filesReady = descriptor.requiredFiles.all { name ->
            val file = File(dir, name)
            file.isFile && manifestFiles[name] == file.length()
        }
        return InstalledModel(
            descriptor = descriptor,
            ready = versionMatches && hashMatches && filesReady,
            installedBytes = if (dir.isDirectory) dir.walkTopDown()
                .filter(File::isFile)
                .sumOf(File::length) else 0L,
            installedAt = markerJson?.optLong("installedAt")?.takeIf { it > 0 },
        )
    }

    /**
     * Restores the previously active model after a process death during staging -> target swap.
     * Callers must hold [ModelOperationCoordinator]'s lock for this id.
     */
    fun recoverInterruptedInstall(id: ModelId) {
        val target = installDir(id)
        val backup = backupDir(id)
        val staging = stagingDir(id)
        when {
            !target.exists() && backup.isDirectory -> {
                check(backup.renameTo(target)) { "중단된 모델 설치의 backup을 복원하지 못했습니다" }
            }
            target.exists() && backup.exists() -> backup.deleteRecursively()
        }
        // A staging directory is never active. It is safe to discard after preserving target or
        // backup above; a later download/install starts from a verified artifact again.
        if (staging.exists()) staging.deleteRecursively()
    }

    fun delete(id: ModelId) {
        installDir(id).deleteRecursively()
        partialFile(id).delete()
        etagFile(id).delete()
        stagingDir(id).deleteRecursively()
        backupDir(id).deleteRecursively()
        ModelIntegrityVerifier.invalidate(id)
    }

    fun modelRoot(): File = root

    companion object {
        const val MARKER = "installed.json"
    }
}
