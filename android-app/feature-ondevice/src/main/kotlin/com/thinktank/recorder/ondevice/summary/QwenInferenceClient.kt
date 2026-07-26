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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Binds the disposable Qwen worker process.
 *
 * A result callback only means generation is finished. The host receives the result after the
 * worker Binder dies, which guarantees native cleanup and process reclamation happened before
 * ResourceArbiter can admit the next local model operation.
 */
class QwenInferenceClient(context: Context) {
    private val applicationContext = context.applicationContext
    internal val lastServicePid = AtomicInteger(-1)
    private val activeDrain = AtomicReference<CompletableDeferred<Unit>?>(null)

    suspend fun summarize(
        modelPath: String,
        transcript: String,
        originalSourceHash: String = sourceHash(transcript),
    ): LocalSummary = suspendCancellableCoroutine { continuation ->
        val requestId = UUID.randomUUID().toString()
        val completed = AtomicBoolean(false)
        val bound = AtomicBoolean(false)
        val cancelSent = AtomicBoolean(false)
        val remote = AtomicReference<IQwenInferenceService?>(null)
        val binder = AtomicReference<IBinder?>(null)
        val deathRecipient = AtomicReference<IBinder.DeathRecipient?>(null)
        val callbackResult = AtomicReference<Result<LocalSummary>?>(null)
        val drain = CompletableDeferred<Unit>()
        check(activeDrain.compareAndSet(null, drain)) { "다른 Qwen worker 종료를 기다리는 중입니다" }
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
            activeDrain.compareAndSet(drain, null)
            drain.complete(Unit)
        }

        fun finish(result: Result<LocalSummary>) {
            if (!completed.compareAndSet(false, true)) return
            cleanup()
            continuation.resumeWith(result)
        }

        fun cancelRemote() {
            if (cancelSent.compareAndSet(false, true)) {
                runCatching { remote.get()?.cancel(requestId) }
            }
        }

        val callback = object : IQwenInferenceCallback.Stub() {
            override fun onSuccess(request: String, resultJson: String, servicePid: Int) {
                if (request != requestId) return
                lastServicePid.set(servicePid)
                callbackResult.compareAndSet(
                    null,
                    runCatching { QwenSummaryCodec.decode(resultJson).copy(sourceHash = originalSourceHash) },
                )
            }

            override fun onError(request: String, message: String, servicePid: Int) {
                if (request != requestId) return
                lastServicePid.set(servicePid)
                callbackResult.compareAndSet(
                    null,
                    Result.failure(IllegalStateException(message)),
                )
            }
        }
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    finish(Result.failure(IllegalStateException("Qwen 서비스 Binder가 없습니다")))
                    return
                }
                if (!continuation.isActive) {
                    cleanup()
                    return
                }
                val inference = IQwenInferenceService.Stub.asInterface(service)
                remote.set(inference)
                binder.set(service)
                runCatching {
                    val recipient = IBinder.DeathRecipient {
                        val result = callbackResult.get()
                            ?: Result.failure(
                                IllegalStateException("Qwen 별도 프로세스가 결과 전에 종료되었습니다"),
                            )
                        finish(result)
                    }
                    deathRecipient.set(recipient)
                    service.linkToDeath(recipient, 0)
                    inference.summarize(requestId, modelPath, transcript, callback)
                }.onFailure { finish(Result.failure(it)) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                val result = callbackResult.get()
                    ?: Result.failure(IllegalStateException("Qwen 서비스 연결이 끊어졌습니다"))
                finish(result)
            }

            override fun onBindingDied(name: ComponentName?) {
                val result = callbackResult.get()
                    ?: Result.failure(IllegalStateException("Qwen 서비스 Binder가 종료되었습니다"))
                finish(result)
            }

            override fun onNullBinding(name: ComponentName?) {
                finish(Result.failure(IllegalStateException("Qwen 서비스를 시작하지 못했습니다")))
            }
        }

        continuation.invokeOnCancellation {
            // Keep the binding and DeathRecipient alive until the disposable worker dies. A
            // cancellation handler cannot suspend; QwenSummaryEngine awaits [activeDrain] in a
            // bounded NonCancellable teardown before returning its native lease.
            cancelRemote()
            if (binder.get() == null && remote.get() == null) cleanup()
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
        if (!didBind && !completed.get()) {
            bound.set(false)
            finish(Result.failure(IllegalStateException("Qwen 서비스를 바인딩하지 못했습니다")))
        }
    }

    /** Waits for the currently bound disposable worker to release its Binder. */
    suspend fun awaitActiveDrain(timeoutMs: Long): Boolean {
        val drain = activeDrain.get() ?: return true
        return withTimeoutOrNull(timeoutMs) {
            drain.await()
            true
        } ?: false
    }
}
