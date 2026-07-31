package com.thinktank.recorder.ondevice.modelpack

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONArray
import org.json.JSONObject

class ModelInstaller(
    private val store: ModelStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun install(
        descriptor: ModelDescriptor,
        artifact: File,
        cancellationCheck: () -> Unit = {},
    ) {
        cancellationCheck()
        require(artifact.isFile) { "모델 파일을 찾을 수 없습니다" }
        val staging = store.stagingDir(descriptor.id)
        val backup = store.backupDir(descriptor.id)
        val target = store.installDir(descriptor.id)
        store.recoverInterruptedInstall(descriptor.id)
        staging.deleteRecursively()
        check(staging.mkdirs()) { "모델 임시 설치 폴더를 만들 수 없습니다" }

        try {
            when (descriptor.artifactFormat) {
                ModelArtifactFormat.SINGLE_FILE -> copySingleArtifact(artifact, staging, descriptor, cancellationCheck)
                ModelArtifactFormat.TAR_BZ2 -> extractTarBz2(artifact, staging, descriptor, cancellationCheck)
            }
            cancellationCheck()
            descriptor.requiredFiles.forEach { filename ->
                check(File(staging, filename).isFile) { "모델 필수 파일이 없습니다: $filename" }
            }
            val files = JSONArray()
            descriptor.requiredFiles.sorted().forEach { filename ->
                cancellationCheck()
                val installed = File(staging, filename)
                files.put(
                    JSONObject()
                        .put("name", filename)
                        .put("size", installed.length())
                        .put("sha256", sha256(installed, cancellationCheck)),
                )
            }
            File(staging, ModelStore.MARKER).writeText(
                JSONObject()
                    .put("modelId", descriptor.id.name)
                    .put("version", descriptor.version)
                    .put("sourceSha256", descriptor.expectedSha256)
                    .put("installedAt", clock())
                    .put("files", files)
                    .toString(),
            )

            if (target.exists()) check(target.renameTo(backup)) { "기존 모델을 보존하지 못했습니다" }
            if (!staging.renameTo(target)) {
                if (backup.exists()) {
                    check(backup.renameTo(target)) { "기존 모델 복원에 실패했습니다" }
                }
                error("검증된 모델을 활성화하지 못했습니다")
            }
            backup.deleteRecursively()
            ModelIntegrityVerifier.invalidate(descriptor.id)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun copySingleArtifact(
        artifact: File,
        staging: File,
        descriptor: ModelDescriptor,
        cancellationCheck: () -> Unit,
    ) {
        check(descriptor.requiredFiles.size == 1) { "단일 모델 파일 구성이 올바르지 않습니다" }
        check(artifact.length() <= descriptor.approximateInstallBytes + INSTALL_SIZE_TOLERANCE_BYTES) {
            "모델 크기가 허용 범위를 초과했습니다"
        }
        val destination = File(staging, descriptor.requiredFiles.single())
        FileInputStream(artifact).use { source ->
            FileOutputStream(destination).use { sink ->
                copyChecked(source, sink, cancellationCheck)
                sink.fd.sync()
            }
        }
        check(destination.length() == artifact.length()) { "모델 파일 복사가 완전하지 않습니다" }
    }

    private fun extractTarBz2(
        artifact: File,
        staging: File,
        descriptor: ModelDescriptor,
        cancellationCheck: () -> Unit,
    ) {
        check(descriptor.archiveRoot.isNotBlank()) { "모델 archive root가 없습니다" }
        var extractedBytes = 0L
        FileInputStream(artifact).use { fileInput ->
            BZip2CompressorInputStream(fileInput, true).use { bzip2 ->
                TarArchiveInputStream(bzip2).use { tar ->
                    while (true) {
                        cancellationCheck()
                        val entry = tar.nextTarEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        check(!name.startsWith('/') && !name.split('/').any { it == ".." }) {
                            "안전하지 않은 모델 archive 경로입니다"
                        }
                        check(!entry.isSymbolicLink && !entry.isLink) {
                            "링크가 포함된 모델 archive는 설치할 수 없습니다"
                        }
                        if (entry.isDirectory || !name.startsWith("${descriptor.archiveRoot}/")) continue
                        val relative = name.removePrefix("${descriptor.archiveRoot}/")
                        if (relative !in descriptor.requiredFiles) continue
                        val destination = File(staging, relative)
                        check(destination.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            "모델 archive가 설치 경로 밖으로 나가려고 합니다"
                        }
                        destination.parentFile?.mkdirs()
                        FileOutputStream(destination).use { sink ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                            while (true) {
                                cancellationCheck()
                                val count = tar.read(buffer)
                                if (count < 0) break
                                extractedBytes += count
                                check(extractedBytes <= descriptor.approximateInstallBytes + INSTALL_SIZE_TOLERANCE_BYTES) {
                                    "압축 해제된 모델 크기가 허용 범위를 초과했습니다"
                                }
                                sink.write(buffer, 0, count)
                            }
                            sink.fd.sync()
                        }
                    }
                }
            }
        }
    }

    private fun copyChecked(
        source: java.io.InputStream,
        destination: java.io.OutputStream,
        cancellationCheck: () -> Unit,
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
        var copied = 0L
        while (true) {
            cancellationCheck()
            val count = source.read(buffer)
            if (count < 0) break
            destination.write(buffer, 0, count)
            copied += count
        }
        return copied
    }

    private fun sha256(file: File, cancellationCheck: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                cancellationCheck()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val INSTALL_SIZE_TOLERANCE_BYTES = 16L * 1024 * 1024
    }
}
