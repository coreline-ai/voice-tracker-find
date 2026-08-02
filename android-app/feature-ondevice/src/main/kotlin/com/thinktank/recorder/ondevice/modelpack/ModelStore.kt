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

data class LegacyModelStorage(
    val entryCount: Int = 0,
    val bytes: Long = 0L,
) {
    val present: Boolean
        get() = entryCount > 0 && bytes > 0L
}

class ModelStore(context: Context) {
    private val root = File(context.filesDir, "ondevice/models").apply { mkdirs() }
    val downloadRoot: File = File(root, ".downloads").apply { mkdirs() }
    private val artifactRoot: File = File(root, ".artifacts").apply { mkdirs() }

    fun installDir(id: ModelId): File = File(root, id.name.lowercase())

    fun stagingDir(id: ModelId): File = File(root, ".staging-${id.name.lowercase()}")

    fun backupDir(id: ModelId): File = File(root, ".backup-${id.name.lowercase()}")

    fun partialFile(id: ModelId): File = File(downloadRoot, "${id.name.lowercase()}.part")

    fun etagFile(id: ModelId): File = File(downloadRoot, "${id.name.lowercase()}.etag")

    fun partialBytes(id: ModelId): Long = partialFile(id).takeIf(File::isFile)?.length() ?: 0L

    fun artifactDir(id: ModelId): File = File(artifactRoot, id.name.lowercase())

    fun artifactFile(descriptor: ModelDescriptor): File =
        File(artifactDir(descriptor.id), descriptor.artifactFileName())

    fun artifactBytes(descriptor: ModelDescriptor): Long =
        artifactFile(descriptor).takeIf(File::isFile)?.length() ?: 0L

    /**
     * Promotes a SHA-256 verified partial file into the persistent internal artifact store.
     * Callers must hold [ModelOperationCoordinator]'s lock for this model.
     */
    fun promoteVerifiedPartial(descriptor: ModelDescriptor): File {
        val partial = partialFile(descriptor.id)
        check(partial.isFile && partial.length() == descriptor.exactArtifactBytes) {
            "검증된 모델 원본 파일이 없습니다"
        }
        val targetDir = artifactDir(descriptor.id).apply { mkdirs() }
        val target = artifactFile(descriptor)
        val incoming = File(targetDir, ".${target.name}.incoming")
        val backup = File(targetDir, ".${target.name}.backup")
        incoming.delete()
        backup.delete()
        check(partial.renameTo(incoming)) { "모델 원본을 보관소로 이동하지 못했습니다" }
        try {
            if (target.exists()) {
                check(target.renameTo(backup)) { "기존 모델 원본을 보존하지 못했습니다" }
            }
            if (!incoming.renameTo(target)) {
                if (backup.exists()) check(backup.renameTo(target)) {
                    "기존 모델 원본 복원에 실패했습니다"
                }
                error("검증된 모델 원본을 활성화하지 못했습니다")
            }
            backup.delete()
            return target
        } catch (error: Throwable) {
            if (!partial.exists() && incoming.exists()) incoming.renameTo(partial)
            throw error
        }
    }

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

    fun deleteInstalled(id: ModelId) {
        installDir(id).deleteRecursively()
        stagingDir(id).deleteRecursively()
        backupDir(id).deleteRecursively()
        ModelIntegrityVerifier.invalidate(id)
    }

    fun delete(id: ModelId) {
        deleteInstalled(id)
        partialFile(id).delete()
        etagFile(id).delete()
        artifactDir(id).deleteRecursively()
    }

    /**
     * Reports only the fixed Qwen/EXAONE paths used by previous app versions. Unknown files and
     * the current Gemma/SenseVoice paths are intentionally excluded.
     */
    fun legacyStorage(): LegacyModelStorage {
        val existing = legacyPaths().filter(File::exists)
        return LegacyModelStorage(
            entryCount = existing.size,
            bytes = existing.sumOf(::pathBytes),
        )
    }

    /** Deletes only user-confirmed Qwen/EXAONE internal files and returns the released bytes. */
    fun deleteLegacyStorage(): Long {
        val paths = legacyPaths().filter(File::exists)
        val bytes = paths.sumOf(::pathBytes)
        paths.forEach { path ->
            check(path.deleteRecursively()) { "이전 모델 파일을 삭제하지 못했습니다: ${path.name}" }
        }
        return bytes
    }

    private fun legacyPaths(): List<File> = LEGACY_MODEL_DIRECTORY_NAMES.flatMap { name ->
        listOf(
            File(root, name),
            File(root, ".staging-$name"),
            File(root, ".backup-$name"),
            File(artifactRoot, name),
            File(downloadRoot, "$name.part"),
            File(downloadRoot, "$name.etag"),
        )
    }

    private fun pathBytes(path: File): Long = when {
        path.isFile -> path.length()
        path.isDirectory -> path.walkTopDown().filter(File::isFile).sumOf(File::length)
        else -> 0L
    }

    fun modelRoot(): File = root

    companion object {
        const val MARKER = "installed.json"
        private val LEGACY_MODEL_DIRECTORY_NAMES = listOf(
            "qwen_summary_ko",
            "exaone_summary_ko",
        )
    }
}

internal fun ModelDescriptor.artifactFileName(): String =
    "${id.name.lowercase()}-${expectedSha256}.${artifactFileExtension()}"

internal fun ModelDescriptor.artifactFileExtension(): String = when {
    artifactFormat == ModelArtifactFormat.TAR_BZ2 -> "tar.bz2"
    requiredFiles.singleOrNull()?.substringAfterLast('.', "")?.isNotBlank() == true ->
        requiredFiles.single().substringAfterLast('.')
    else -> "bin"
}
