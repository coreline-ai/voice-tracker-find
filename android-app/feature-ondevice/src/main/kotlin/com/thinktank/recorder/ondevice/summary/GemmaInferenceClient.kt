package com.thinktank.recorder.ondevice.summary

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.thinktank.recorder.ondevice.api.LocalSummary
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine

class GemmaInferenceClient(context: Context) {
    private val applicationContext = context.applicationContext

    suspend fun summarize(modelPath: String, transcript: String): LocalSummary =
        suspendCancellableCoroutine { continuation ->
            val requestId = UUID.randomUUID().toString()
            val completed = AtomicBoolean(false)
            val bound = AtomicBoolean(false)
            val remote = AtomicReference<IGemmaInferenceService?>(null)
            lateinit var connection: ServiceConnection

            fun cleanup() {
                if (bound.compareAndSet(true, false)) {
                    runCatching { applicationContext.unbindService(connection) }
                }
            }

            fun finish(result: Result<LocalSummary>) {
                if (!completed.compareAndSet(false, true)) return
                cleanup()
                continuation.resumeWith(result)
            }

            val callback = object : IGemmaInferenceCallback.Stub() {
                override fun onSuccess(request: String, resultJson: String, servicePid: Int) {
                    if (request == requestId) {
                        finish(runCatching { GemmaSummaryCodec.decode(resultJson) })
                    }
                }

                override fun onError(request: String, message: String, servicePid: Int) {
                    if (request == requestId) {
                        finish(Result.failure(IllegalStateException(message)))
                    }
                }
            }
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (service == null) {
                        finish(Result.failure(IllegalStateException("Gemma 서비스 Binder가 없습니다.")))
                        return
                    }
                    val inference = IGemmaInferenceService.Stub.asInterface(service)
                    remote.set(inference)
                    runCatching {
                        inference.summarize(requestId, modelPath, transcript, callback)
                    }.onFailure { finish(Result.failure(it)) }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (!completed.get()) {
                        finish(Result.failure(IllegalStateException("Gemma 서비스 연결이 끊어졌습니다.")))
                    }
                }

                override fun onBindingDied(name: ComponentName?) {
                    if (!completed.get()) {
                        finish(Result.failure(IllegalStateException("Gemma 별도 프로세스가 종료되었습니다.")))
                    }
                }

                override fun onNullBinding(name: ComponentName?) {
                    finish(Result.failure(IllegalStateException("Gemma 서비스를 시작하지 못했습니다.")))
                }
            }

            continuation.invokeOnCancellation {
                runCatching { remote.get()?.cancel(requestId) }
                cleanup()
            }
            bound.set(true)
            val didBind = runCatching {
                applicationContext.bindService(
                    Intent(applicationContext, GemmaInferenceService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrElse {
                finish(Result.failure(it))
                false
            }
            if (!didBind && !completed.get()) {
                bound.set(false)
                finish(Result.failure(IllegalStateException("Gemma 서비스를 바인딩하지 못했습니다.")))
            }
        }
}
