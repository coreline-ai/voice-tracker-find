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

    fun download(id: ModelId, wifiOnly: Boolean = true) {
        check(NativeRuntimeCapabilities.current().supported) {
            "Moonshine/Qwen 모델은 arm64 64비트 기기에서만 설치할 수 있습니다"
        }
        enqueue(id, sourceUri = null, wifiOnly = wifiOnly)
    }

    fun import(id: ModelId, uri: Uri) {
        check(NativeRuntimeCapabilities.current().supported) {
            "Moonshine/Qwen 모델은 arm64 64비트 기기에서만 설치할 수 있습니다"
        }
        runCatching {
            applicationContext.contentResolver.takePersistableUriPermission(
                uri,
                IntentFlags.READ,
            )
        }
        enqueue(id, sourceUri = uri.toString(), wifiOnly = false)
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

    fun installedModels(): Map<ModelId, InstalledModel> =
        ModelCatalog.models.associate { it.id to store.snapshot(it) }

    private fun enqueue(id: ModelId, sourceUri: String?, wifiOnly: Boolean) {
        val input = Data.Builder()
            .putString(ModelDownloadWorker.KEY_MODEL_ID, id.name)
            .apply { if (sourceUri != null) putString(ModelDownloadWorker.KEY_SOURCE_URI, sourceUri) }
            .build()
        val constraints = Constraints.Builder()
            .apply {
                if (sourceUri == null) {
                    setRequiredNetworkType(
                        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                    )
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
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private object IntentFlags {
        const val READ = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
}
