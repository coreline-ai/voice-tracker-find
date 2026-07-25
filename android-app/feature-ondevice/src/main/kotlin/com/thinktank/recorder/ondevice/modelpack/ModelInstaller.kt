package com.thinktank.recorder.ondevice.modelpack

import java.io.BufferedInputStream
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
        val root = store.modelRoot()
        val staging = File(root, ".staging-${descriptor.id.name.lowercase()}")
        val backup = File(root, ".backup-${descriptor.id.name.lowercase()}")
        val target = store.installDir(descriptor.id)
        staging.deleteRecursively()
        backup.deleteRecursively()
        check(staging.mkdirs()) { "모델 임시 설치 폴더를 만들 수 없습니다" }

        try {
            when (descriptor.packaging) {
                ModelPackaging.TAR_BZ2 ->
                    extractMoonshine(descriptor, artifact, staging, cancellationCheck)
                ModelPackaging.SINGLE_GGUF ->
                    copyQwen(artifact, staging, cancellationCheck)
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
                if (backup.exists()) backup.renameTo(target)
                error("검증된 모델을 활성화하지 못했습니다")
            }
            backup.deleteRecursively()
            artifact.delete()
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun extractMoonshine(
        descriptor: ModelDescriptor,
        artifact: File,
        staging: File,
        cancellationCheck: () -> Unit,
    ) {
        val allowed = descriptor.requiredFiles
        var extractedBytes = 0L
        TarArchiveInputStream(
            BZip2CompressorInputStream(
                BufferedInputStream(FileInputStream(artifact)),
                true,
            ),
        ).use { tar ->
            while (true) {
                cancellationCheck()
                val entry = tar.nextTarEntry ?: break
                if (!entry.isFile) continue
                val normalized = entry.name.replace('\\', '/')
                check(!normalized.startsWith("/") && ".." !in normalized.split('/')) {
                    "안전하지 않은 모델 경로입니다"
                }
                val leaf = normalized.substringAfterLast('/')
                if (leaf !in allowed) continue
                check(entry.size in 0..MAX_MOONSHINE_ENTRY_BYTES) {
                    "모델 파일 크기가 허용 범위를 벗어났습니다: $leaf"
                }
                val output = File(staging, leaf)
                check(!output.exists()) { "중복 모델 파일입니다: $leaf" }
                FileOutputStream(output).use { sink ->
                    val copied = copyChecked(tar, sink, cancellationCheck)
                    check(copied == entry.size) { "모델 파일이 완전하지 않습니다: $leaf" }
                    extractedBytes += copied
                    check(extractedBytes <= MAX_MOONSHINE_TOTAL_BYTES) {
                        "압축 해제 크기가 허용 범위를 초과했습니다"
                    }
                }
            }
        }
    }

    private fun copyQwen(
        artifact: File,
        staging: File,
        cancellationCheck: () -> Unit,
    ) {
        check(artifact.length() <= MAX_QWEN_BYTES) { "Qwen 모델 크기가 허용 범위를 초과했습니다" }
        val destination = File(staging, "model.gguf")
        FileInputStream(artifact).use { source ->
            FileOutputStream(destination).use { sink ->
                copyChecked(source, sink, cancellationCheck)
                sink.fd.sync()
            }
        }
        check(destination.length() == artifact.length()) { "Qwen 모델 복사가 완전하지 않습니다" }
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
        const val MAX_MOONSHINE_ENTRY_BYTES = 80L * 1024 * 1024
        const val MAX_MOONSHINE_TOTAL_BYTES = 120L * 1024 * 1024
        const val MAX_QWEN_BYTES = 650L * 1024 * 1024
    }
}
