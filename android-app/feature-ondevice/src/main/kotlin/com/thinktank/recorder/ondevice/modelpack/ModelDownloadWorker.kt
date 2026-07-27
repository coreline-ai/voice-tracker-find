package com.thinktank.recorder.ondevice.modelpack

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.thinktank.recorder.ondevice.runtime.NativeRuntimeCapabilities
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val store = ModelStore(appContext)
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_MODEL_ID)
            ?.let { runCatching { ModelId.valueOf(it) }.getOrNull() }
            ?: return@withContext Result.failure(errorData("모델 식별자가 없습니다"))
        val descriptor = ModelCatalog.get(id)
        ModelOperationCoordinator.withLock(id) {
            store.recoverInterruptedInstall(id)
            installLocked(id, descriptor)
        }
    }

    private suspend fun installLocked(id: ModelId, descriptor: ModelDescriptor): Result {
        if (!NativeRuntimeCapabilities.current().supported) {
            return Result.failure(
                errorData("로컬 AI 모델은 arm64 64비트 기기에서만 설치할 수 있습니다"),
            )
        }
        if (store.snapshot(descriptor).ready) {
            return Result.success(Data.Builder().putString(KEY_MODEL_ID, id.name).build())
        }

        createNotificationChannel()
        setForeground(foregroundInfo(descriptor, 0, descriptor.approximateDownloadBytes))
        val partial = store.partialFile(id)
        partial.parentFile?.mkdirs()

        return try {
            ensureStorage(descriptor, partial.length())
            val sourceUri = inputData.getString(KEY_SOURCE_URI)
            // A completed, pinned artifact may remain after an app update interrupted only
            // extraction. Verify it locally and continue without opening a socket or requiring
            // Wi-Fi. The later SHA-256 check is the authoritative trust boundary.
            val reusableLocalArtifact = sourceUri.isNullOrBlank() &&
                hasCompleteArtifactFile(partial, descriptor.exactArtifactBytes) &&
                sha256(partial) == descriptor.expectedSha256
            if (reusableLocalArtifact) {
                setProgress(progressData(descriptor, partial.length(), partial.length(), STATUS_VERIFYING))
                updateNotification(descriptor, partial.length(), partial.length(), "다운로드 파일 확인 완료")
            } else if (sourceUri.isNullOrBlank()) {
                check(descriptor.remoteDownloadEnabled) {
                    "이 모델은 공식 파일 가져오기로만 설치할 수 있습니다"
                }
                val wifi = WifiOnlyDownloadPolicy.validatedWifi(applicationContext)
                    ?: return Result.retry()
                download(descriptor, partial, wifi)
            } else {
                importFromUri(descriptor, Uri.parse(sourceUri), partial)
            }
            setProgress(progressData(descriptor, partial.length(), partial.length(), STATUS_VERIFYING))
            updateNotification(descriptor, partial.length(), partial.length(), "파일 검증 중")
            if (sha256(partial) != descriptor.expectedSha256) {
                partial.delete()
                store.etagFile(id).delete()
                throw ArtifactValidationException("모델 SHA-256 검증에 실패했습니다")
            }
            setProgress(progressData(descriptor, partial.length(), partial.length(), STATUS_INSTALLING))
            updateNotification(descriptor, partial.length(), partial.length(), "모델 설치 중")
            val installContext = currentCoroutineContext()
            ModelInstaller(store).install(descriptor, partial) {
                installContext.ensureActive()
            }
            store.etagFile(id).delete()
            notificationManager.notify(
                notificationId(id),
                notification(descriptor, 1, 1, "사용 준비 완료", done = true),
            )
            Result.success(
                Data.Builder()
                    .putString(KEY_MODEL_ID, id.name)
                    .putString(KEY_STATUS, STATUS_READY)
                    .build(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (wifiRequired: WifiRequiredException) {
            // Preserve the partial artifact. A future run again checks and binds Wi-Fi.
            Result.retry()
        } catch (error: Throwable) {
            if (error is ArtifactValidationException) {
                partial.delete()
                store.etagFile(id).delete()
            }
            Log.w(LOG_TAG, "Pinned model installation failed: ${descriptor.id}", error)
            Result.failure(errorData(userFacingError(error)))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val id = inputData.getString(KEY_MODEL_ID)
            ?.let { runCatching { ModelId.valueOf(it) }.getOrNull() }
            ?: ModelId.QWEN_SUMMARY_KO
        val descriptor = ModelCatalog.get(id)
        createNotificationChannel()
        return foregroundInfo(descriptor, 0, descriptor.approximateDownloadBytes)
    }

    private suspend fun download(
        descriptor: ModelDescriptor,
        target: File,
        wifi: android.net.Network,
    ) {
        val client = OkHttpClient.Builder()
            .socketFactory(wifi.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String) = wifi.getAllByName(hostname).toList()
            })
            .followRedirects(true)
            .followSslRedirects(true)
            // Large model CDNs can pause a response for longer than OkHttp's 10-second default.
            // Keep the download resumable instead of failing a partially received 563 MB model.
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(MODEL_READ_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .writeTimeout(MODEL_READ_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .addNetworkInterceptor { chain ->
                val host = chain.request().url.host.lowercase()
                if (!isAllowedModelHost(host)) {
                    throw IOException("허용되지 않은 모델 배포 호스트입니다: $host")
                }
                chain.proceed(chain.request())
            }
            .build()
        var fullRetryUsed = false
        while (true) {
            var existing = target.length()
            if (existing > descriptor.exactArtifactBytes) {
                target.delete()
                store.etagFile(descriptor.id).delete()
                existing = 0
            }
            val request = Request.Builder()
                .url(descriptor.downloadUrl)
                .header("User-Agent", "ThinkTank-OnDevice-ModelInstaller/1")
                .apply {
                    if (existing > 0) {
                        header("Range", "bytes=$existing-")
                        store.etagFile(descriptor.id)
                            .takeIf(File::isFile)
                            ?.readText()
                            ?.takeIf(String::isNotBlank)
                            ?.let { header("If-Range", it) }
                    }
                }
                .get()
                .build()
            var retryFull = false
            client.newCall(request).execute().use { response ->
                if (response.code == 416) {
                    val shaMatches = target.isFile &&
                        target.length() == descriptor.exactArtifactBytes &&
                        sha256(target) == descriptor.expectedSha256
                    when (
                        range416Decision(
                            partialBytes = target.length(),
                            exactBytes = descriptor.exactArtifactBytes,
                            shaMatches = shaMatches,
                            fullRetryUsed = fullRetryUsed,
                        )
                    ) {
                        Range416Decision.ACCEPT_COMPLETE -> return
                        Range416Decision.FAIL ->
                            throw ArtifactValidationException("손상된 이어받기 파일을 복구하지 못했습니다")
                        Range416Decision.RETRY_FULL -> Unit
                    }
                    target.delete()
                    store.etagFile(descriptor.id).delete()
                    fullRetryUsed = true
                    retryFull = true
                } else {
                    check(response.isSuccessful) { "모델 다운로드 실패: HTTP ${response.code}" }
                    val append = existing > 0 && response.code == 206
                    if (!append) {
                        target.delete()
                        store.etagFile(descriptor.id).delete()
                        existing = 0
                    }
                    response.header("ETag")?.let { store.etagFile(descriptor.id).writeText(it) }
                    val body = response.body ?: error("모델 응답 본문이 없습니다")
                    val responseBytes = body.contentLength().takeIf { it >= 0 }
                    if (
                        responseBytes != null &&
                        responseBytes + existing > descriptor.exactArtifactBytes
                    ) {
                        throw ArtifactValidationException("모델 응답 크기가 고정 크기를 초과했습니다")
                    }
                    body.byteStream().use { source ->
                        FileOutputStream(target, append).use { sink ->
                            copyWithProgress(
                                descriptor = descriptor,
                                source = BufferedInputStream(source),
                                sink = sink,
                                initial = existing,
                                total = descriptor.exactArtifactBytes,
                                wifi = wifi,
                            )
                        }
                    }
                    return
                }
            }
            if (!retryFull) return
        }
    }

    private suspend fun importFromUri(
        descriptor: ModelDescriptor,
        uri: Uri,
        target: File,
    ) {
        target.delete()
        val source = applicationContext.contentResolver.openInputStream(uri)
            ?: error("선택한 모델 파일을 열 수 없습니다")
        source.use {
            FileOutputStream(target).use { sink ->
                copyWithProgress(
                    descriptor = descriptor,
                    source = BufferedInputStream(it),
                    sink = sink,
                    initial = 0,
                    total = descriptor.exactArtifactBytes,
                )
            }
        }
    }

    private suspend fun copyWithProgress(
        descriptor: ModelDescriptor,
        source: BufferedInputStream,
        sink: FileOutputStream,
        initial: Long,
        total: Long,
        wifi: android.net.Network? = null,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
        val sizeGuard = ExactArtifactSizeGuard(descriptor.exactArtifactBytes, initial)
        var copied = sizeGuard.copiedBytes
        var lastReported = initial
        val startedBytes = initial
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (wifi != null && !WifiOnlyDownloadPolicy.isStillValidatedWifi(applicationContext, wifi)) {
                throw WifiRequiredException()
            }
            val count = source.read(buffer)
            if (count < 0) break
            sizeGuard.accept(count)
            sink.write(buffer, 0, count)
            copied = sizeGuard.copiedBytes
            if (copied - lastReported >= PROGRESS_STEP_BYTES) {
                val metrics = transferMetrics(startedBytes, copied, total, startedAt)
                setProgress(
                    progressData(
                        descriptor,
                        copied,
                        total,
                        STATUS_DOWNLOADING,
                        metrics.bytesPerSecond,
                        metrics.etaSeconds,
                    ),
                )
                updateNotification(descriptor, copied, total, "다운로드 중")
                lastReported = copied
            }
        }
        sizeGuard.verifyEof()
        sink.fd.sync()
        val metrics = transferMetrics(startedBytes, copied, total, startedAt)
        setProgress(
            progressData(
                descriptor,
                copied,
                total,
                STATUS_DOWNLOADING,
                metrics.bytesPerSecond,
                metrics.etaSeconds,
            ),
        )
    }

    private fun ensureStorage(descriptor: ModelDescriptor, alreadyDownloaded: Long) {
        val available = StatFs(applicationContext.filesDir.path).availableBytes
        val required = (
            descriptor.approximateInstallBytes +
                descriptor.approximateDownloadBytes -
                alreadyDownloaded +
                STORAGE_HEADROOM_BYTES
            ).coerceAtLeast(STORAGE_HEADROOM_BYTES)
        check(available >= required) {
            "저장공간이 부족합니다. 최소 ${formatBytes(required)}가 필요합니다"
        }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun progressData(
        descriptor: ModelDescriptor,
        downloaded: Long,
        total: Long,
        status: String,
        bytesPerSecond: Long = 0,
        etaSeconds: Long = -1,
    ): Data = Data.Builder()
        .putString(KEY_MODEL_ID, descriptor.id.name)
        .putString(KEY_STATUS, status)
        .putLong(KEY_DOWNLOADED_BYTES, downloaded)
        .putLong(KEY_TOTAL_BYTES, total)
        .putLong(KEY_BYTES_PER_SECOND, bytesPerSecond)
        .putLong(KEY_ETA_SECONDS, etaSeconds)
        .build()

    private fun transferMetrics(
        startedBytes: Long,
        downloaded: Long,
        total: Long,
        startedAt: Long,
    ): TransferMetrics {
        val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
        val transferred = (downloaded - startedBytes).coerceAtLeast(0)
        val bytesPerSecond = (transferred * 1_000 / elapsedMs).coerceAtLeast(0)
        val etaSeconds = if (bytesPerSecond > 0 && total > downloaded) {
            (total - downloaded + bytesPerSecond - 1) / bytesPerSecond
        } else {
            0
        }
        return TransferMetrics(bytesPerSecond, etaSeconds)
    }

    private fun errorData(message: String): Data =
        Data.Builder().putString(KEY_ERROR, message).build()

    private fun userFacingError(error: Throwable): String {
        val causes = generateSequence(error as Throwable?) { it.cause }.toList()
        return when {
            causes.any { it is SSLHandshakeException } ->
                "모델 배포 인증서를 검증하지 못했습니다. 기기 날짜와 시스템 업데이트를 확인하거나 모델 파일 가져오기를 사용하세요."
            causes.any { it is UnknownHostException } ->
                "모델 서버에 연결할 수 없습니다. 네트워크 연결을 확인한 뒤 다시 시도하세요."
            else -> error.message ?: "모델을 설치하지 못했습니다"
        }
    }

    private fun foregroundInfo(
        descriptor: ModelDescriptor,
        downloaded: Long,
        total: Long,
    ): ForegroundInfo {
        val info = ForegroundInfo(
            notificationId(descriptor.id),
            notification(descriptor, downloaded, total, "다운로드 준비 중"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        return info
    }

    private fun updateNotification(
        descriptor: ModelDescriptor,
        downloaded: Long,
        total: Long,
        status: String,
    ) {
        notificationManager.notify(
            notificationId(descriptor.id),
            notification(descriptor, downloaded, total, status),
        )
    }

    private fun notification(
        descriptor: ModelDescriptor,
        downloaded: Long,
        total: Long,
        status: String,
        done: Boolean = false,
    ): android.app.Notification {
        val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                applicationContext,
                0,
                it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(descriptor.displayName)
            .setContentText(status)
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setAutoCancel(done)
            .setProgress(100, percent, total <= 0)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "로컬 AI 모델",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "온디바이스 STT와 요약 모델 설치 상태"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun notificationId(id: ModelId): Int = 7_700 + id.ordinal

    private fun formatBytes(bytes: Long): String =
        if (bytes >= 1024 * 1024 * 1024) {
            "%.1fGB".format(bytes / (1024.0 * 1024 * 1024))
        } else {
            "%.0fMB".format(bytes / (1024.0 * 1024))
        }

    companion object {
        const val TAG_ALL_MODELS = "thinktank-ondevice-models"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_SOURCE_URI = "source_uri"
        const val KEY_STATUS = "status"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_BYTES_PER_SECOND = "bytes_per_second"
        const val KEY_ETA_SECONDS = "eta_seconds"
        const val KEY_ERROR = "error"

        const val STATUS_DOWNLOADING = "DOWNLOADING"
        const val STATUS_VERIFYING = "VERIFYING"
        const val STATUS_INSTALLING = "INSTALLING"
        const val STATUS_READY = "READY"

        private const val LOG_TAG = "OnDeviceModel"
        private const val CHANNEL_ID = "ondevice-models"
        private const val PROGRESS_STEP_BYTES = 1024L * 1024
        private const val STORAGE_HEADROOM_BYTES = 128L * 1024 * 1024
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val MODEL_READ_TIMEOUT_MINUTES = 2L
        private val ALLOWED_MODEL_HOSTS = setOf(
            "github.com",
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com",
            "huggingface.co",
            "cdn-lfs.huggingface.co",
            "cas-bridge.xethub.hf.co",
        )

        internal fun isAllowedModelHost(host: String): Boolean =
            host.lowercase() in ALLOWED_MODEL_HOSTS ||
                host.lowercase().endsWith(".cdn.hf.co")

        fun workName(id: ModelId): String = "thinktank-model-${id.name.lowercase()}"
        fun tag(id: ModelId): String = "thinktank-model-tag-${id.name.lowercase()}"
    }

    private data class TransferMetrics(
        val bytesPerSecond: Long,
        val etaSeconds: Long,
    )

    private class WifiRequiredException : IOException(
        "Wi-Fi 연결이 끊겨 모델 다운로드를 일시정지했습니다",
    )
}
