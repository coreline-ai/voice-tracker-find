package com.thinktank.recorder.ondevice.modelpack

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

enum class ModelVaultConnection {
    NOT_CONNECTED,
    CONNECTED,
    PERMISSION_REQUIRED,
    ERROR,
}

data class ModelVaultState(
    val connection: ModelVaultConnection = ModelVaultConnection.NOT_CONNECTED,
    val displayName: String? = null,
    val error: String? = null,
) {
    val connected: Boolean
        get() = connection == ModelVaultConnection.CONNECTED
}

/**
 * User-owned model artifact storage selected through Storage Access Framework.
 *
 * Runtime model files remain in [ModelStore]. This vault preserves the original verified
 * artifact outside the app sandbox so a fresh install can restore it after the user reconnects
 * the same directory.
 */
class ModelVault(context: Context) {
    private val applicationContext = context.applicationContext
    private val resolver = applicationContext.contentResolver
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun connect(treeUri: Uri): ModelVaultState {
        return runCatching {
            resolver.takePersistableUriPermission(treeUri, READ_WRITE_FLAGS)
            val root = requireNotNull(DocumentFile.fromTreeUri(applicationContext, treeUri)) {
                "선택한 모델 보관함을 열 수 없습니다"
            }
            check(root.isDirectory && root.canRead() && root.canWrite()) {
                "선택한 모델 보관함에 읽기·쓰기 권한이 없습니다"
            }
            preferences.edit()
                .putString(KEY_TREE_URI, treeUri.toString())
                .remove(KEY_LAST_ERROR)
                .remove(KEY_RECONNECT_REQUIRED)
                .apply()
            ModelVaultState(
                connection = ModelVaultConnection.CONNECTED,
                displayName = root.name,
            )
        }.getOrElse { error ->
            recordError(error.message ?: "모델 보관함을 연결하지 못했습니다")
            state()
        }
    }

    fun disconnect() {
        val storedUri = preferences.getString(KEY_TREE_URI, null)
        storedUri?.let(Uri::parse)?.let { uri ->
            runCatching { resolver.releasePersistableUriPermission(uri, READ_WRITE_FLAGS) }
        }
        preferences.edit()
            .remove(KEY_TREE_URI)
            .remove(KEY_LAST_ERROR)
            .putBoolean(KEY_RECONNECT_REQUIRED, storedUri != null)
            .apply()
    }

    fun state(): ModelVaultState {
        val rawUri = preferences.getString(KEY_TREE_URI, null)
            ?: return if (preferences.getBoolean(KEY_RECONNECT_REQUIRED, false)) {
                ModelVaultState(
                    connection = ModelVaultConnection.PERMISSION_REQUIRED,
                    error = "보관 원본을 완전 삭제하거나 복구하려면 같은 모델 보관함을 다시 연결하세요.",
                )
            } else {
                ModelVaultState(ModelVaultConnection.NOT_CONNECTED)
            }
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
            ?: return ModelVaultState(ModelVaultConnection.ERROR, error = "보관함 주소가 손상되었습니다")
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        val hasPermission = permission?.isReadPermission == true && permission.isWritePermission
        if (
            modelVaultConnection(
                hasStoredUri = true,
                hasReadWritePermission = hasPermission,
                rootUsable = true,
            ) == ModelVaultConnection.PERMISSION_REQUIRED
        ) {
            return ModelVaultState(
                connection = ModelVaultConnection.PERMISSION_REQUIRED,
                error = "앱을 다시 설치한 경우 같은 모델 보관함을 다시 연결하세요.",
            )
        }
        val root = runCatching { DocumentFile.fromTreeUri(applicationContext, uri) }.getOrNull()
        val rootUsable = root != null && root.isDirectory && root.canRead() && root.canWrite()
        if (
            modelVaultConnection(
                hasStoredUri = true,
                hasReadWritePermission = true,
                rootUsable = rootUsable,
            ) != ModelVaultConnection.CONNECTED
        ) {
            return ModelVaultState(
                connection = ModelVaultConnection.PERMISSION_REQUIRED,
                error = "모델 보관함 접근 권한을 다시 확인하세요.",
            )
        }
        return ModelVaultState(
            connection = ModelVaultConnection.CONNECTED,
            displayName = root?.name,
            error = preferences.getString(KEY_LAST_ERROR, null),
        )
    }

    fun artifactUri(descriptor: ModelDescriptor): Uri? {
        val root = connectedRoot() ?: return null
        return root.findFile(descriptor.artifactFileName())
            ?.takeIf { it.isFile && it.length() == descriptor.exactArtifactBytes }
            ?.uri
    }

    /**
     * Copies a verified internal artifact to the connected vault. Existing valid artifacts are
     * reused. A same-name corrupt artifact is preserved and reported instead of being overwritten.
     */
    fun preserve(descriptor: ModelDescriptor, source: File): Uri? {
        val root = connectedRoot() ?: return null
        check(source.isFile && source.length() == descriptor.exactArtifactBytes) {
            "보관할 모델 원본의 크기가 올바르지 않습니다"
        }
        check(sha256(source) == descriptor.expectedSha256) {
            "보관할 모델 원본의 SHA-256이 일치하지 않습니다"
        }
        val fileName = descriptor.artifactFileName()
        root.findFile(fileName)?.let { existing ->
            check(
                existing.isFile &&
                    existing.length() == descriptor.exactArtifactBytes &&
                    sha256(existing.uri) == descriptor.expectedSha256
            ) {
                "같은 이름의 손상된 보관 파일이 있습니다. 완전 삭제 후 다시 보관하세요."
            }
            clearError()
            return existing.uri
        }

        val target = checkNotNull(root.createFile(MIME_TYPE, fileName)) {
            "모델 보관 파일을 만들 수 없습니다"
        }
        return try {
            resolver.openFileDescriptor(target.uri, "rwt")?.use { descriptorFile ->
                FileInputStream(source).use { input ->
                    FileOutputStream(descriptorFile.fileDescriptor).use { output ->
                        val guard = ExactArtifactSizeGuard(
                            expectedBytes = descriptor.exactArtifactBytes,
                            initialBytes = 0,
                        )
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            guard.accept(count)
                            output.write(buffer, 0, count)
                        }
                        guard.verifyEof()
                        output.fd.sync()
                    }
                }
            } ?: error("모델 보관 파일을 열 수 없습니다")
            check(
                target.length() == descriptor.exactArtifactBytes &&
                    sha256(target.uri) == descriptor.expectedSha256
            ) {
                "모델 보관 파일 검증에 실패했습니다"
            }
            clearError()
            target.uri
        } catch (error: Throwable) {
            target.delete()
            recordError(error.message ?: "모델 원본을 보관하지 못했습니다")
            throw error
        }
    }

    fun deleteArtifact(descriptor: ModelDescriptor): Boolean {
        val root = connectedRoot() ?: return false
        val target = root.findFile(descriptor.artifactFileName()) ?: return true
        return target.delete().also { deleted ->
            if (deleted) clearError() else recordError("공유 모델 원본을 삭제하지 못했습니다")
        }
    }

    private fun connectedRoot(): DocumentFile? {
        if (!state().connected) return null
        val uri = preferences.getString(KEY_TREE_URI, null)?.let(Uri::parse) ?: return null
        return DocumentFile.fromTreeUri(applicationContext, uri)
    }

    private fun sha256(file: File): String =
        FileInputStream(file).use { digest(it) }

    private fun sha256(uri: Uri): String {
        val input = resolver.openInputStream(uri) ?: error("보관 모델 파일을 읽을 수 없습니다")
        return input.use { digest(BufferedInputStream(it)) }
    }

    private fun digest(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun recordError(message: String) {
        preferences.edit().putString(KEY_LAST_ERROR, message).apply()
    }

    private fun clearError() {
        preferences.edit().remove(KEY_LAST_ERROR).apply()
    }

    private companion object {
        const val PREFERENCES = "thinktank_model_vault"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_RECONNECT_REQUIRED = "reconnect_required"
        const val MIME_TYPE = "application/octet-stream"
        const val READ_WRITE_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

internal fun modelVaultConnection(
    hasStoredUri: Boolean,
    hasReadWritePermission: Boolean,
    rootUsable: Boolean,
): ModelVaultConnection = when {
    !hasStoredUri -> ModelVaultConnection.NOT_CONNECTED
    !hasReadWritePermission || !rootUsable -> ModelVaultConnection.PERMISSION_REQUIRED
    else -> ModelVaultConnection.CONNECTED
}
