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
        val manifestNames = markerJson?.optJSONArray("files")?.let { files ->
            buildSet {
                for (index in 0 until files.length()) {
                    files.optJSONObject(index)?.optString("name")?.let(::add)
                }
            }
        }.orEmpty()
        val filesReady = descriptor.requiredFiles.all {
            File(dir, it).isFile && it in manifestNames
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

    fun delete(id: ModelId) {
        installDir(id).deleteRecursively()
        partialFile(id).delete()
        etagFile(id).delete()
        File(root, ".staging-${id.name.lowercase()}").deleteRecursively()
        File(root, ".backup-${id.name.lowercase()}").deleteRecursively()
    }

    fun modelRoot(): File = root

    companion object {
        const val MARKER = "installed.json"
    }
}
