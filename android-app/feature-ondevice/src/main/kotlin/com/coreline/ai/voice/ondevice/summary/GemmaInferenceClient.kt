package com.coreline.ai.voice.ondevice.summary

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.coreline.ai.voice.ondevice.api.LocalSummary
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

class GemmaInferenceClient(context: Context) {
    private val applicationContext = context.applicationContext

    suspend fun summarize(modelPath: String, transcript: String): LocalSummary =
        summarizeBatch(modelPath, listOf(transcript)).single()

    suspend fun summarizeBatch(
        modelPath: String,
        transcripts: List<String>,
        onSummary: suspend (index: Int, summary: LocalSummary) -> Unit = { _, _ -> },
    ): List<LocalSummary> = withBatch(modelPath) { summarize ->
        require(transcripts.isNotEmpty()) { "요약할 Gemma 입력이 없습니다." }
        transcripts.mapIndexed { index, transcript ->
            summarize(transcript).also { summary -> onSummary(index, summary) }
        }
    }

    suspend fun <T> withBatch(
        modelPath: String,
        block: suspend (summarize: suspend (String) -> LocalSummary) -> T,
    ): T {
        val batchId = UUID.randomUUID().toString()
        val binding = bind()
        return try {
            val summarize: suspend (String) -> LocalSummary = { transcript ->
                withTimeout(MAX_NODE_TIME_MS) {
                    summarizeNode(
                        remote = binding.remote,
                        batchId = batchId,
                        modelPath = modelPath,
                        transcript = transcript,
                    )
                }
            }
            block(summarize)
        } finally {
            runCatching { binding.remote.finishBatch(batchId) }
            binding.close()
        }
    }

    private suspend fun summarizeNode(
        remote: IGemmaInferenceService,
        batchId: String,
        modelPath: String,
        transcript: String,
    ): LocalSummary =
        suspendCancellableCoroutine { continuation ->
            val requestId = UUID.randomUUID().toString()
            val completed = AtomicBoolean(false)

            fun finish(result: Result<LocalSummary>) {
                if (!completed.compareAndSet(false, true)) return
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
            continuation.invokeOnCancellation {
                runCatching { remote.cancel(requestId) }
            }
            runCatching {
                remote.summarizeNode(
                    batchId,
                    requestId,
                    sha256(transcript),
                    modelPath,
                    transcript,
                    callback,
                )
            }.onFailure { finish(Result.failure(it)) }
        }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private suspend fun bind(): BoundGemmaService =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            lateinit var connection: ServiceConnection

            fun finish(result: Result<BoundGemmaService>) {
                if (!completed.compareAndSet(false, true)) return
                continuation.resumeWith(result)
            }

            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (service == null) {
                        finish(Result.failure(IllegalStateException("Gemma 서비스 Binder가 없습니다.")))
                    } else {
                        finish(
                            Result.success(
                                BoundGemmaService(
                                    remote = IGemmaInferenceService.Stub.asInterface(service),
                                    closeBinding = {
                                        runCatching { applicationContext.unbindService(this) }
                                    },
                                ),
                            ),
                        )
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit

                override fun onBindingDied(name: ComponentName?) {
                    finish(Result.failure(IllegalStateException("Gemma 별도 프로세스가 종료되었습니다.")))
                }

                override fun onNullBinding(name: ComponentName?) {
                    finish(Result.failure(IllegalStateException("Gemma 서비스를 시작하지 못했습니다.")))
                }
            }

            continuation.invokeOnCancellation {
                if (!completed.get()) runCatching { applicationContext.unbindService(connection) }
            }
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
            if (!didBind) {
                finish(Result.failure(IllegalStateException("Gemma 서비스를 바인딩하지 못했습니다.")))
            }
        }

    private class BoundGemmaService(
        val remote: IGemmaInferenceService,
        private val closeBinding: () -> Unit,
    ) {
        fun close() = closeBinding()
    }

    private companion object {
        const val MAX_NODE_TIME_MS = 240_000L
    }
}
