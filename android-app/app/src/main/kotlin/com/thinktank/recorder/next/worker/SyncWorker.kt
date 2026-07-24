package com.thinktank.recorder.next.worker

import android.Manifest
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.thinktank.recorder.next.R
import com.thinktank.recorder.next.MainActivity
import com.thinktank.recorder.next.ThinkTankApplication
import com.thinktank.recorder.next.data.repository.SyncRepository
import com.thinktank.recorder.next.data.repository.SyncRunResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: SyncRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val owner = id.toString()
        return when (val outcome = repository.run(owner)) {
            is SyncRunResult.Success -> {
                if (outcome.uploaded > 0 || outcome.notes > 0) notify(outcome)
                Result.success(
                    Data.Builder()
                        .putInt(KEY_UPLOADED, outcome.uploaded)
                        .putInt(KEY_NOTES, outcome.notes)
                        .build(),
                )
            }
            is SyncRunResult.Retry -> {
                setProgress(
                    Data.Builder()
                        .putString(KEY_ERROR, outcome.reason)
                        .build(),
                )
                Result.retry()
            }
            is SyncRunResult.Failure -> Result.failure(
                Data.Builder().putString(KEY_ERROR, outcome.reason).build(),
            )
        }
    }

    private fun notify(outcome: SyncRunResult.Success) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val text = "녹음 ${outcome.uploaded}개 전송 · 노트 ${outcome.notes}개 확인"
        val openNotes = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java)
                .setData(android.net.Uri.parse("thinktank://notes"))
                .putExtra(MainActivity.EXTRA_DESTINATION, "notes"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            UUID.nameUUIDFromBytes(text.toByteArray()).hashCode(),
            NotificationCompat.Builder(
                applicationContext,
                ThinkTankApplication.SYNC_CHANNEL,
            )
                .setSmallIcon(R.drawable.ic_notification_mic)
                .setContentTitle("ThinkTank 동기화 완료")
                .setContentText(text)
                .setContentIntent(openNotes)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val KEY_UPLOADED = "uploaded"
        const val KEY_NOTES = "notes"
        const val KEY_ERROR = "error"
    }
}
