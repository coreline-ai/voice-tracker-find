package com.thinktank.recorder.ondevice.modelpack

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

class ModelIntegrityVerifier(
    private val store: ModelStore,
) {
    fun requireValid(descriptor: ModelDescriptor) {
        val dir = store.installDir(descriptor.id)
        val marker = File(dir, ModelStore.MARKER)
        check(marker.isFile) { "모델 설치 정보가 없습니다" }
        val markerJson = runCatching { JSONObject(marker.readText()) }
            .getOrElse { error("모델 설치 정보를 읽을 수 없습니다") }
        check(markerJson.optString("version") == descriptor.version) { "모델 버전이 일치하지 않습니다" }
        check(markerJson.optString("sourceSha256") == descriptor.expectedSha256) {
            "모델 원본 검증 정보가 일치하지 않습니다"
        }
        val cacheKey = "${descriptor.id}:${marker.lastModified()}:${marker.length()}"
        if (verified[cacheKey] == true) return

        val manifest = markerJson.optJSONArray("files") ?: error("모델 파일 검증 정보가 없습니다")
        val entries = buildMap {
            for (index in 0 until manifest.length()) {
                val entry = manifest.optJSONObject(index) ?: continue
                put(entry.optString("name"), entry)
            }
        }
        descriptor.requiredFiles.forEach { name ->
            val entry = entries[name] ?: error("모델 파일 검증 정보가 없습니다: $name")
            val file = File(dir, name)
            check(file.isFile) { "모델 필수 파일이 없습니다: $name" }
            check(file.length() == entry.optLong("size", -1)) { "모델 파일 크기가 변경되었습니다: $name" }
            check(sha256(file) == entry.optString("sha256")) { "모델 파일이 손상되었습니다: $name" }
        }
        verified.keys.removeAll { it.startsWith("${descriptor.id}:") }
        verified[cacheKey] = true
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val verified = ConcurrentHashMap<String, Boolean>()
    }
}
