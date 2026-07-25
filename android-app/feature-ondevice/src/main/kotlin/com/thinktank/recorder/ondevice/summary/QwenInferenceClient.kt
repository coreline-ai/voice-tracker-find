package com.thinktank.recorder.ondevice.summary

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.thinktank.recorder.ondevice.api.LocalSummary
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine

class QwenInferenceClient(context: Context) {
    private val applicationContext = context.applicationContext
    internal val lastServicePid = AtomicInteger(-1)

    suspend fun summarize(modelPath: String, transcript: String): LocalSummary =
        suspendCancellableCoroutine { continuation ->
            val requestId = UUID.randomUUID().toString()
            val completed = AtomicBoolean(false)
            val bound = AtomicBoolean(false)
            val remote = AtomicReference<IQwenInferenceService?>(null)
            val binder = AtomicReference<IBinder?>(null)
            val deathRecipient = AtomicReference<IBinder.DeathRecipient?>(null)
            lateinit var connection: ServiceConnection

            fun cleanup() {
                binder.getAndSet(null)?.let { active ->
                    deathRecipient.getAndSet(null)?.let { recipient ->
                        runCatching { active.unlinkToDeath(recipient, 0) }
                    }
                }
                if (bound.compareAndSet(true, false)) {
                    runCatching { applicationContext.unbindService(connection) }
                }
            }

            fun finish(result: Result<LocalSummary>) {
                if (!completed.compareAndSet(false, true)) return
                cleanup()
                continuation.resumeWith(result)
            }

            val callback = object : IQwenInferenceCallback.Stub() {
                override fun onSuccess(request: String, resultJson: String, servicePid: Int) {
                    if (request != requestId) return
                    lastServicePid.set(servicePid)
                    finish(runCatching { QwenSummaryCodec.decode(resultJson) })
                }

                override fun onError(request: String, message: String, servicePid: Int) {
                    if (request != requestId) return
                    lastServicePid.set(servicePid)
                    finish(Result.failure(IllegalStateException(message)))
                }
            }
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (service == null) {
                        finish(Result.failure(IllegalStateException("Qwen 서비스 Binder가 없습니다")))
                        return
                    }
                    val inference = IQwenInferenceService.Stub.asInterface(service)
                    remote.set(inference)
                    binder.set(service)
                    runCatching {
                        val recipient = IBinder.DeathRecipient {
                            finish(
                                Result.failure(
                                    IllegalStateException("Qwen 별도 프로세스가 종료되었습니다"),
                                ),
                            )
                        }
                        deathRecipient.set(recipient)
                        service.linkToDeath(
                            recipient,
                            0,
                        )
                        inference.summarize(requestId, modelPath, transcript, callback)
                    }.onFailure { finish(Result.failure(it)) }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    finish(Result.failure(IllegalStateException("Qwen 서비스 연결이 끊어졌습니다")))
                }

                override fun onBindingDied(name: ComponentName?) {
                    finish(Result.failure(IllegalStateException("Qwen 서비스 Binder가 종료되었습니다")))
                }

                override fun onNullBinding(name: ComponentName?) {
                    finish(Result.failure(IllegalStateException("Qwen 서비스를 시작하지 못했습니다")))
                }
            }

            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    runCatching { remote.get()?.cancel(requestId) }
                    cleanup()
                }
            }
            bound.set(true)
            val didBind = runCatching {
                applicationContext.bindService(
                    Intent(applicationContext, QwenInferenceService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrElse {
                finish(Result.failure(it))
                false
            }
            if (didBind) {
                if (!continuation.isActive && completed.compareAndSet(false, true)) {
                    runCatching { remote.get()?.cancel(requestId) }
                    cleanup()
                }
            } else if (!completed.get()) {
                bound.set(false)
                finish(Result.failure(IllegalStateException("Qwen 서비스를 바인딩하지 못했습니다")))
            }
        }
}
