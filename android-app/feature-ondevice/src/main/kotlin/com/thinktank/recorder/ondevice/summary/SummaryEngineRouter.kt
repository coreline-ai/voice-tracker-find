package com.thinktank.recorder.ondevice.summary

import com.thinktank.recorder.ondevice.api.LocalSummary
import com.thinktank.recorder.ondevice.api.RemoteSummaryAttempt
import com.thinktank.recorder.ondevice.api.RemoteSummaryFailure
import com.thinktank.recorder.ondevice.api.RemoteSummaryGateway
import com.thinktank.recorder.ondevice.api.RemoteSummaryProvider
import com.thinktank.recorder.ondevice.api.SummaryEngine
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import java.security.MessageDigest

class SummaryRoutingException(
    val failure: RemoteSummaryFailure,
) : Exception("Remote summary failed: ${failure.code}")

/** Consumer-owned OAuth-first/local-fallback policy. It never switches between remote Providers. */
class SummaryEngineRouter(
    private val remote: RemoteSummaryGateway,
    private val local: SummaryEngine,
) : SummaryEngine {
    override suspend fun summarize(transcript: String): LocalSummary {
        require(transcript.isNotBlank()) { "요약할 전사 원문이 없습니다." }
        val profile = remote.activeProfile.value
            ?: return local.summarize(transcript).copy(
                requestedEngine = SummaryEngineType.GEMMA_LOCAL,
                dataPolicy = DATA_POLICY_LOCAL_ONLY,
            )
        val requestedEngine = profile.provider.toEngine()
        return when (val attempt = remote.summarize(transcript)) {
            is RemoteSummaryAttempt.Success -> attempt.result.let { result ->
                LocalSummary(
                    title = result.title,
                    bullets = result.bullets,
                    actionItems = result.actionItems,
                    engine = result.provider.toEngine(),
                    requestedEngine = requestedEngine,
                    sourceHash = sha256(transcript),
                    requestedModelId = result.modelId,
                    actualModelId = result.modelId,
                    runtimeType = "OAUTH_CLOUD",
                    generationProfile = "STRUCTURED_JSON_V1",
                    durationMs = result.latencyMs,
                    inputChars = transcript.length,
                    outputChars = result.title.length +
                        result.bullets.sumOf(String::length) +
                        result.actionItems.sumOf(String::length),
                    providerId = result.provider.id,
                    providerRequestId = result.providerRequestId,
                    inputTokens = result.usage.inputTokens,
                    outputTokens = result.usage.outputTokens,
                    dataPolicy = DATA_POLICY_REMOTE_TRANSCRIPT,
                    validationStatus = "VALID",
                )
            }
            is RemoteSummaryAttempt.Failed -> {
                if (!attempt.failure.fallbackEligible) {
                    throw SummaryRoutingException(attempt.failure)
                }
                local.summarize(transcript).copy(
                    requestedEngine = requestedEngine,
                    providerId = profile.provider.id,
                    fallbackReason = attempt.failure.code.name,
                    dataPolicy = DATA_POLICY_REMOTE_THEN_LOCAL,
                )
            }
        }
    }

    private fun RemoteSummaryProvider.toEngine(): SummaryEngineType = when (this) {
        RemoteSummaryProvider.ANTHROPIC -> SummaryEngineType.ANTHROPIC_OAUTH
        RemoteSummaryProvider.CODEX -> SummaryEngineType.CODEX_OAUTH
        RemoteSummaryProvider.XAI -> SummaryEngineType.XAI_OAUTH
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val DATA_POLICY_LOCAL_ONLY = "LOCAL_ONLY"
        const val DATA_POLICY_REMOTE_TRANSCRIPT = "REMOTE_TRANSCRIPT_OAUTH"
        const val DATA_POLICY_REMOTE_THEN_LOCAL = "REMOTE_TRANSCRIPT_THEN_LOCAL"
    }
}
