package com.thinktank.recorder.ondevice.processing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.thinktank.recorder.ondevice.api.OnDeviceFailureStage
import com.thinktank.recorder.ondevice.api.OnDeviceSessionState
import com.thinktank.recorder.ondevice.data.LongAudioProcessingRepository
import com.thinktank.recorder.ondevice.data.OnDeviceDatabase
import com.thinktank.recorder.ondevice.data.OnDeviceProcessingJobEntity
import com.thinktank.recorder.ondevice.data.OnDeviceRepository
import com.thinktank.recorder.ondevice.recording.LocalAudioFileManager
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LongAudioProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var database: OnDeviceDatabase
    private lateinit var jobs: LongAudioProcessingRepository
    private lateinit var sessions: OnDeviceRepository
    private lateinit var audioFiles: LocalAudioFileManager
    private var execution: Job? = null
    private var runner: LongAudioProcessingRunner? = null
    private var activeJobId: String? = null

    override fun onCreate() {
        super.onCreate()
        database = OnDeviceDatabase.get(this)
        jobs = LongAudioProcessingRepository(database)
        audioFiles = LocalAudioFileManager(this)
        sessions = OnDeviceRepository(database.sessionDao(), audioFiles)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobId = intent?.getStringExtra(EXTRA_JOB_ID)
        if (jobId.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startInForeground(jobId, "장시간 로컬 분석을 준비하고 있습니다.", 0)
        when (intent.action ?: ACTION_START) {
            ACTION_PAUSE -> requestPause(jobId)
            ACTION_CANCEL -> requestCancel(jobId, startId)
            ACTION_START -> startJob(jobId, startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runner?.cancelNative()
        execution?.cancel()
        if (processActiveJobId == activeJobId) processActiveJobId = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        activeJobId?.let { jobId ->
            scope.launch {
                val token = jobs.getJob(jobId)?.serviceToken ?: return@launch
                finishInterrupted(
                    jobId = jobId,
                    serviceToken = token,
                    state = LongAudioJobState.INTERRUPTED,
                    message = "Android 장시간 미디어 처리 제한으로 중단되었습니다. 다시 시작할 수 있습니다.",
                )
                runner?.cancelNative()
                execution?.cancel()
                ServiceCompat.stopForeground(
                    this@LongAudioProcessingService,
                    ServiceCompat.STOP_FOREGROUND_REMOVE,
                )
                stopSelf(startId)
            }
        }
    }

    private fun startJob(jobId: String, startId: Int) {
        if (execution?.isActive == true) {
            if (activeJobId != jobId) {
                updateNotification(jobId, "다른 장시간 로컬 분석이 실행 중입니다.", 0)
            }
            return
        }
        val serviceToken = UUID.randomUUID().toString()
        activeJobId = jobId
        processActiveJobId = jobId
        val processingRunner = LongAudioProcessingRunner(this) { job ->
            updateNotification(
                jobId = job.id,
                text = job.displayLabel,
                progress = (job.progress * PROGRESS_MAX).toInt(),
                paused = job.jobState == LongAudioJobState.PAUSED,
            )
        }
        runner = processingRunner
        execution = scope.launch {
            var detachedNotification: Notification? = null
            try {
                processingRunner.run(jobId, serviceToken)
                audioFiles.deleteProcessingFiles(jobId)
            } catch (stopped: LongAudioCancellationException) {
                val state = if (stopped.cancel) {
                    LongAudioJobState.CANCELLED
                } else {
                    LongAudioJobState.PAUSED
                }
                finishInterrupted(jobId, serviceToken, state, stopped.message.orEmpty())
                if (stopped.cancel) {
                    audioFiles.deleteProcessingFiles(jobId)
                } else {
                    detachedNotification = notification(
                        jobId = jobId,
                        text = "장시간 처리가 일시 중지되었습니다.",
                        progress = jobs.getJob(jobId)?.let { (it.progress * PROGRESS_MAX).toInt() }
                            ?: 0,
                        paused = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                finishInterrupted(
                    jobId,
                    serviceToken,
                    LongAudioJobState.INTERRUPTED,
                    "장시간 로컬 분석이 중단되었습니다. 마지막 완료 지점부터 다시 시작할 수 있습니다.",
                )
            } catch (error: Throwable) {
                val message = error.message ?: "장시간 로컬 분석에 실패했습니다."
                val thermalPause = message.contains("과열")
                val terminalState = if (thermalPause) {
                    LongAudioJobState.PAUSED
                } else {
                    LongAudioJobState.FAILED_RECOVERABLE
                }
                val jobFinished = jobs.finish(
                    jobId = jobId,
                    serviceToken = serviceToken,
                    state = terminalState,
                    stage = jobs.getJob(jobId)?.jobStage ?: LongAudioStage.PREPARING_SOURCE,
                    failureCode = when {
                        thermalPause -> "THERMAL_PAUSED"
                        error is LongAudioProcessingException -> error.code
                        else -> "LONG_PROCESSING_FAILED"
                    },
                    error = message,
                )
                jobs.getJob(jobId)?.takeIf { jobFinished }?.let { failed ->
                    sessions.finishLongProcessingSession(
                        id = failed.sessionId,
                        state = OnDeviceSessionState.FAILED_RECOVERABLE,
                        failureStage = (error as? LongAudioProcessingException)?.stage
                            ?: OnDeviceFailureStage.SUMMARIZE,
                        error = message,
                        clearJob = false,
                    )
                }
                detachedNotification = notification(
                    jobId = jobId,
                    text = if (thermalPause) {
                        "기기 과열로 일시 중지됨 · 식힌 뒤 재개하세요."
                    } else {
                        "오류 · 원인을 확인하고 마지막 지점부터 재개하세요."
                    },
                    progress = jobs.getJob(jobId)?.let { (it.progress * PROGRESS_MAX).toInt() }
                        ?: 0,
                    paused = true,
                )
            } finally {
                runner = null
                if (processActiveJobId == jobId) processActiveJobId = null
                activeJobId = null
                ServiceCompat.stopForeground(
                    this@LongAudioProcessingService,
                    ServiceCompat.STOP_FOREGROUND_REMOVE,
                )
                detachedNotification?.let {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, it)
                }
                stopSelf(startId)
            }
        }
    }

    private fun requestPause(jobId: String) {
        scope.launch {
            if (jobs.requestPause(jobId)) {
                runner?.cancelNative()
                execution?.cancel(LongAudioCancellationException(cancel = false))
            }
        }
    }

    private fun requestCancel(jobId: String, startId: Int) {
        scope.launch {
            if (jobs.requestCancel(jobId)) {
                if (execution?.isActive == true && activeJobId == jobId) {
                    runner?.cancelNative()
                    execution?.cancel(LongAudioCancellationException(cancel = true))
                } else {
                    val token = UUID.randomUUID().toString()
                    jobs.claim(jobId, token)?.let { job ->
                        jobs.finish(
                            jobId = job.id,
                            serviceToken = token,
                            state = LongAudioJobState.CANCELLED,
                            stage = job.jobStage,
                        )
                        sessions.finishLongProcessingSession(
                            id = job.sessionId,
                            state = OnDeviceSessionState.CANCELLED,
                            failureStage = null,
                            error = null,
                            clearJob = true,
                        )
                        audioFiles.deleteProcessingFiles(job.id)
                    }
                    ServiceCompat.stopForeground(
                        this@LongAudioProcessingService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE,
                    )
                    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                    stopSelf(startId)
                }
            }
        }
    }

    private suspend fun finishInterrupted(
        jobId: String,
        serviceToken: String,
        state: LongAudioJobState,
        message: String,
    ) {
        val job = jobs.getJob(jobId) ?: return
        val jobFinished = jobs.finish(
            jobId = jobId,
            serviceToken = serviceToken,
            state = state,
            stage = job.jobStage,
            failureCode = when (state) {
                LongAudioJobState.PAUSED -> null
                LongAudioJobState.CANCELLED -> null
                else -> "PROCESS_INTERRUPTED"
            },
            error = message.takeIf(String::isNotBlank),
        )
        if (!jobFinished) return
        sessions.finishLongProcessingSession(
            id = job.sessionId,
            state = when (state) {
                LongAudioJobState.CANCELLED -> OnDeviceSessionState.CANCELLED
                else -> OnDeviceSessionState.FAILED_RECOVERABLE
            },
            failureStage = when (job.jobStage) {
                LongAudioStage.NORMALIZING -> OnDeviceFailureStage.NORMALIZE
                LongAudioStage.TRANSCRIBING -> OnDeviceFailureStage.TRANSCRIBE
                else -> OnDeviceFailureStage.SUMMARIZE
            },
            error = message.takeIf(String::isNotBlank),
            clearJob = state == LongAudioJobState.CANCELLED,
        )
    }

    private fun startInForeground(jobId: String, text: String, progress: Int) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(jobId, text, progress),
            foregroundType(),
        )
    }

    private fun updateNotification(
        jobId: String,
        text: String,
        progress: Int,
        paused: Boolean = false,
    ) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(jobId, text, progress, paused),
        )
    }

    private fun notification(
        jobId: String,
        text: String,
        progress: Int,
        paused: Boolean = false,
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("장시간 녹음 로컬 분석")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(!paused)
            .setSilent(true)
        if (progress in 1 until PROGRESS_MAX) {
            builder.setProgress(PROGRESS_MAX, progress, false)
        } else if (!paused) {
            builder.setProgress(PROGRESS_MAX, 0, true)
        }
        if (!paused) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "일시 중지",
                serviceIntent(ACTION_PAUSE, jobId, REQUEST_PAUSE),
            )
            builder.addAction(
                android.R.drawable.ic_delete,
                "취소",
                serviceIntent(ACTION_CANCEL, jobId, REQUEST_CANCEL),
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_play,
                "마지막 지점부터 재개",
                serviceIntent(ACTION_START, jobId, REQUEST_RESUME),
            )
            builder.addAction(
                android.R.drawable.ic_delete,
                "취소",
                serviceIntent(ACTION_CANCEL, jobId, REQUEST_CANCEL),
            )
        }
        return builder.build()
    }

    private fun serviceIntent(action: String, jobId: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, LongAudioProcessingService::class.java)
                .setAction(action)
                .putExtra(EXTRA_JOB_ID, jobId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "장시간 녹음 로컬 분석",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "장시간 녹음의 PCM 변환, STT와 계층형 Gemma 요약 진행 상태"
                setShowBadge(false)
            },
        )
    }

    private fun foregroundType(): Int = when {
        Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    companion object {
        private const val ACTION_START =
            "com.thinktank.recorder.ondevice.processing.START"
        private const val ACTION_PAUSE =
            "com.thinktank.recorder.ondevice.processing.PAUSE"
        private const val ACTION_CANCEL =
            "com.thinktank.recorder.ondevice.processing.CANCEL"
        private const val EXTRA_JOB_ID = "job_id"
        private const val CHANNEL_ID = "long_audio_processing"
        private const val NOTIFICATION_ID = 46_120
        private const val PROGRESS_MAX = 1_000
        private const val REQUEST_PAUSE = 46_121
        private const val REQUEST_CANCEL = 46_122
        private const val REQUEST_RESUME = 46_123
        @Volatile
        private var processActiveJobId: String? = null

        fun hasActiveExecution(): Boolean = processActiveJobId != null

        fun start(context: Context, jobId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LongAudioProcessingService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_JOB_ID, jobId),
            )
        }

        fun pause(context: Context, jobId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LongAudioProcessingService::class.java)
                    .setAction(ACTION_PAUSE)
                    .putExtra(EXTRA_JOB_ID, jobId),
            )
        }

        fun cancel(context: Context, jobId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LongAudioProcessingService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_JOB_ID, jobId),
            )
        }
    }
}
