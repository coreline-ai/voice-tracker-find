package com.thinktank.recorder.next.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.thinktank.recorder.next.data.settings.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val preferences: AppPreferences,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueueManual() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(TAG_SYNC)
            .build()
        workManager.enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request)
    }

    suspend fun reconcileAutoSync() {
        val settings = preferences.current()
        if (!settings.autoSync) {
            workManager.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        val network = if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            30,
            TimeUnit.MINUTES,
            15,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(network)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .addTag(TAG_SYNC)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val MANUAL_WORK = "thinktank-manual-sync"
        const val PERIODIC_WORK = "thinktank-auto-sync"
        const val TAG_SYNC = "thinktank-sync"
    }
}

