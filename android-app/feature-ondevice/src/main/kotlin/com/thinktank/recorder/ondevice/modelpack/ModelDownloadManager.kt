package com.thinktank.recorder.ondevice.modelpack

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
import com.thinktank.recorder.ondevice.runtime.NativeRuntimeCapabilities

class ModelDownloadManager(context: Context) {
    private val applicationContext = context.applicationContext
    private val workManager = WorkManager.getInstance(applicationContext)
    private val store = ModelStore(applicationContext)

    val workInfos: Flow<List<WorkInfo>> =
        workManager.getWorkInfosByTagFlow(ModelDownloadWorker.TAG_ALL_MODELS)

    fun download(id: ModelId) {
        check(NativeRuntimeCapabilities.current().supported) {
            "로컬 AI 모델은 arm64 64비트 기기에서만 설치할 수 있습니다"
        }
        check(ModelCatalog.get(id).remoteDownloadEnabled) {
            "이 모델은 공식 배포 페이지에서 받은 파일을 '파일 가져오기'로 설치해야 합니다"
        }
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
        enqueue(id, sourceUri = uri.toString())
    }

    suspend fun pause(id: ModelId) {
        workManager.cancelUniqueWork(ModelDownloadWorker.workName(id)).await()
    }

    suspend fun delete(id: ModelId) {
        pause(id)
        ModelOperationCoordinator.withLock(id) {
            store.delete(id)
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
            if (
                !store.snapshot(descriptor).ready &&
                hasCompleteArtifactFile(store.partialFile(descriptor.id), descriptor.exactArtifactBytes)
            ) {
                enqueue(
                    id = descriptor.id,
                    sourceUri = null,
                    requiresUnmeteredNetwork = false,
                    policy = ExistingWorkPolicy.KEEP,
                )
            }
        }
    }

    fun installedModels(): Map<ModelId, InstalledModel> =
        ModelCatalog.models.associate { it.id to store.snapshot(it) }

    private fun enqueue(
        id: ModelId,
        sourceUri: String?,
        requiresUnmeteredNetwork: Boolean = sourceUri == null,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val input = Data.Builder()
            .putString(ModelDownloadWorker.KEY_MODEL_ID, id.name)
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
}
