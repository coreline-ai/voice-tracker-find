package com.thinktank.recorder.ondevice.api

import kotlinx.coroutines.flow.StateFlow

enum class RemoteSummaryProvider(val id: String) {
    ANTHROPIC("anthropic"),
    CODEX("codex"),
    XAI("xai"),
}

data class RemoteSummaryProfile(
    val profileId: String,
    val provider: RemoteSummaryProvider,
    val displayName: String,
)

data class RemoteSummaryUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long?,
)

data class RemoteSummaryResult(
    val title: String,
    val bullets: List<String>,
    val actionItems: List<String>,
    val provider: RemoteSummaryProvider,
    val modelId: String,
    val providerRequestId: String?,
    val latencyMs: Long,
    val usage: RemoteSummaryUsage,
)

enum class RemoteSummaryFailureCode {
    NOT_CONNECTED,
    AUTHENTICATION_REQUIRED,
    NETWORK_UNAVAILABLE,
    RATE_LIMITED,
    PROVIDER_UNAVAILABLE,
    TIMEOUT,
    INVALID_PROVIDER_RESPONSE,
    INVALID_REQUEST,
    USER_CANCELLED,
    APP_CONFIGURATION,
}

data class RemoteSummaryFailure(
    val code: RemoteSummaryFailureCode,
    val retryable: Boolean,
    val fallbackEligible: Boolean,
)

sealed interface RemoteSummaryAttempt {
    data class Success(val result: RemoteSummaryResult) : RemoteSummaryAttempt
    data class Failed(val failure: RemoteSummaryFailure) : RemoteSummaryAttempt
}

/**
 * Android-independent boundary consumed by the local feature. Implementations may send only the
 * supplied transcript to the user-selected OAuth Provider; audio and OAuth credentials never cross
 * this API.
 */
interface RemoteSummaryGateway {
    val activeProfile: StateFlow<RemoteSummaryProfile?>
    suspend fun summarize(transcript: String): RemoteSummaryAttempt
}
