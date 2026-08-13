package com.coreline.ai.voice.ondevice.modelpack

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import androidx.work.WorkInfo
import androidx.work.await
import com.coreline.ai.voice.ondevice.runtime.NativeRuntimeCapabilities

enum class ModelDeleteScope {
    INSTALLED_ONLY,
    COMPLETE,
}

class ModelDownloadManager(context: Context) {
    private val applicationContext = context.applicationContext
    private val workManager = WorkManager.getInstance(applicationContext)
    private val store = ModelStore(applicationContext)
    private val vault = ModelVault(applicationContext)
    private val preferences = applicationContext.getSharedPreferences(
        "airvoice_model_restore_policy",
        Context.MODE_PRIVATE,
    )

    val workInfos: Flow<List<WorkInfo>> =
        workManager.getWorkInfosByTagFlow(ModelDownloadWorker.TAG_ALL_MODELS)

    fun download(id: ModelId) {
        check(NativeRuntimeCapabilities.current().supported) {
            "로컬 AI 모델은 arm64 64비트 기기에서만 설치할 수 있습니다"
        }
        check(ModelCatalog.get(id).remoteDownloadEnabled) {
            "이 모델은 공식 배포 페이지에서 받은 파일을 '파일 가져오기'로 설치해야 합니다"
        }
        setRestoreSuppressed(id, false)
        enqueue(id, sourceUri = null)
    }

    fun import(id: ModelId, uri: Uri) {
        check(NativeRuntimeCapabilities.current().supported) {
            "로컬 AI 모델은 arm64 64비트 기기에서만 설치할 수 있습니다"
        }
        runCatching {
            applicationContext.contentResolver.takePersistableUriPermission(
                uri,
                IntentFlags.READ,
            )
        }
        setRestoreSuppressed(id, false)
        enqueue(id, sourceUri = uri.toString())
    }

    fun restore(id: ModelId) {
        val descriptor = ModelCatalog.get(id)
        val hasInternal = hasCompleteArtifactFile(
            store.artifactFile(descriptor),
            descriptor.exactArtifactBytes,
        )
        val vaultArtifact = vault.artifactUri(descriptor)
        check(hasInternal || vaultArtifact != null) { "복구할 모델 보관 원본이 없습니다" }
        setRestoreSuppressed(id, false)
        enqueue(
            id = id,
            sourceUri = vaultArtifact?.toString(),
            localOnly = true,
            requiresUnmeteredNetwork = false,
        )
    }

    fun connectVault(uri: Uri): ModelVaultState {
        val connected = vault.connect(uri)
        if (!connected.connected) return connected

        ModelCatalog.models.forEach { descriptor ->
            store.artifactFile(descriptor)
                .takeIf {
                    hasCompleteArtifactFile(it, descriptor.exactArtifactBytes)
                }
                ?.let { artifact ->
                    runCatching { vault.preserve(descriptor, artifact) }
                }
        }
        return vault.state()
    }

    fun disconnectVault() = vault.disconnect()

    fun vaultState(): ModelVaultState = vault.state()

    suspend fun pause(id: ModelId) {
        workManager.cancelUniqueWork(ModelDownloadWorker.workName(id)).await()
    }

    suspend fun delete(id: ModelId, scope: ModelDeleteScope) {
        pause(id)
        ModelOperationCoordinator.withLock(id) {
            when (scope) {
                ModelDeleteScope.INSTALLED_ONLY -> {
                    store.deleteInstalled(id)
                    setRestoreSuppressed(id, true)
                }
                ModelDeleteScope.COMPLETE -> {
                    val descriptor = ModelCatalog.get(id)
                    when (vault.state().connection) {
                        ModelVaultConnection.CONNECTED -> check(vault.deleteArtifact(descriptor)) {
                            "공유 모델 원본을 삭제하지 못했습니다"
                        }
                        ModelVaultConnection.PERMISSION_REQUIRED,
                        ModelVaultConnection.ERROR,
                        -> error("완전 삭제 전에 모델 보관함을 다시 연결하세요")
                        ModelVaultConnection.NOT_CONNECTED -> Unit
                    }
                    store.delete(id)
                    setRestoreSuppressed(id, false)
                }
            }
        }
    }

    suspend fun recoverInterruptedInstalls() {
        ModelCatalog.models.forEach { descriptor ->
            ModelOperationCoordinator.withLock(descriptor.id) {
                store.recoverInterruptedInstall(descriptor.id)
            }
        }
    }

    /**
     * App replacement stops WorkManager work. When the pinned archive was already downloaded,
     * continue only the local SHA-256 verification and installation on the next launch. This path
     * has no network constraint and never initiates a network transfer.
     */
    fun resumeCompletedDownloadsLocally() {
        ModelCatalog.models.forEach { descriptor ->
            if (isRestoreSuppressed(descriptor.id)) return@forEach
            val vaultArtifact = vault.artifactUri(descriptor)
            if (
                !store.snapshot(descriptor).ready &&
                (
                    hasCompleteArtifactFile(
                        store.artifactFile(descriptor),
                        descriptor.exactArtifactBytes,
                    ) ||
                        hasCompleteArtifactFile(
                            store.partialFile(descriptor.id),
                            descriptor.exactArtifactBytes,
                        ) ||
                        vaultArtifact != null
                    )
            ) {
                enqueue(
                    id = descriptor.id,
                    sourceUri = vaultArtifact?.toString(),
                    localOnly = true,
                    requiresUnmeteredNetwork = false,
                    policy = ExistingWorkPolicy.KEEP,
                )
            }
        }
    }

    fun installedModels(): Map<ModelId, InstalledModel> =
        ModelCatalog.models.associate { it.id to store.snapshot(it) }

    fun isRestoreSuppressed(id: ModelId): Boolean =
        preferences.getBoolean(restoreSuppressedKey(id), false)

    private fun enqueue(
        id: ModelId,
        sourceUri: String?,
        localOnly: Boolean = false,
        requiresUnmeteredNetwork: Boolean = sourceUri == null && !localOnly,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val input = Data.Builder()
            .putString(ModelDownloadWorker.KEY_MODEL_ID, id.name)
            .putBoolean(ModelDownloadWorker.KEY_LOCAL_ONLY, localOnly)
            .apply { if (sourceUri != null) putString(ModelDownloadWorker.KEY_SOURCE_URI, sourceUri) }
            .build()
        val constraints = Constraints.Builder()
            .apply {
                if (requiresUnmeteredNetwork) {
                    // Scheduler gate only. The worker binds actual sockets and DNS
                    // lookup to a validated Wi-Fi network before transferring bytes.
                    setRequiredNetworkType(NetworkType.UNMETERED)
                }
            }
            .build()
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(input)
            .setConstraints(constraints)
            .addTag(ModelDownloadWorker.TAG_ALL_MODELS)
            .addTag(ModelDownloadWorker.tag(id))
            .build()
        workManager.enqueueUniqueWork(
            ModelDownloadWorker.workName(id),
            policy,
            request,
        )
    }

    private object IntentFlags {
        const val READ = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
    }

    private fun setRestoreSuppressed(id: ModelId, suppressed: Boolean) {
        preferences.edit().apply {
            if (suppressed) putBoolean(restoreSuppressedKey(id), true)
            else remove(restoreSuppressedKey(id))
        }.apply()
    }

    private fun restoreSuppressedKey(id: ModelId): String =
        "restore_suppressed_${id.name.lowercase()}"
}
