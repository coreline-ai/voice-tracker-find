package com.coreline.ai.voice.ondevice.summary

import com.coreline.ai.voice.ondevice.api.LocalSummary
import com.coreline.ai.voice.ondevice.api.RemoteSummaryAttempt
import com.coreline.ai.voice.ondevice.api.RemoteSummaryFailure
import com.coreline.ai.voice.ondevice.api.RemoteSummaryFailureCode
import com.coreline.ai.voice.ondevice.api.RemoteSummaryGateway
import com.coreline.ai.voice.ondevice.api.RemoteSummaryProfile
import com.coreline.ai.voice.ondevice.api.RemoteSummaryProvider
import com.coreline.ai.voice.ondevice.api.RemoteSummaryResult
import com.coreline.ai.voice.ondevice.api.RemoteSummaryUsage
import com.coreline.ai.voice.ondevice.api.SummaryEngine
import com.coreline.ai.voice.ondevice.api.SummaryEngineType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryEngineRouterTest {
    @Test
    fun disconnectedSkipsRemoteAndRunsLocalOnce() = runTest {
        val remote = FakeRemote(profile = null)
        val local = FakeLocal()

        val result = SummaryEngineRouter(remote, local).summarize("전사")

        assertEquals(0, remote.calls)
        assertEquals(1, local.calls)
        assertEquals(SummaryEngineType.GEMMA_LOCAL, result.engine)
        assertEquals("LOCAL_ONLY", result.dataPolicy)
    }

    @Test
    fun remoteSuccessDoesNotRunLocal() = runTest {
        val remote = FakeRemote(
            profile = PROFILE,
            attempt = RemoteSummaryAttempt.Success(
                RemoteSummaryResult(
                    title = "회의",
                    bullets = listOf("핵심"),
                    actionItems = listOf("후속"),
                    provider = RemoteSummaryProvider.CODEX,
                    modelId = "configured-model",
                    providerRequestId = "request-1",
                    latencyMs = 25,
                    usage = RemoteSummaryUsage(10, 4, 14),
                ),
            ),
        )
        val local = FakeLocal()

        val result = SummaryEngineRouter(remote, local).summarize("전사")

        assertEquals(1, remote.calls)
        assertEquals(0, local.calls)
        assertEquals(SummaryEngineType.CODEX_OAUTH, result.engine)
        assertEquals("REMOTE_TRANSCRIPT_OAUTH", result.dataPolicy)
        assertEquals("request-1", result.providerRequestId)
    }

    @Test
    fun eligibleFailureRunsExactlyOneLocalFallback() = runTest {
        val remote = FakeRemote(
            profile = PROFILE,
            attempt = failure(RemoteSummaryFailureCode.TIMEOUT, fallback = true),
        )
        val local = FakeLocal()

        val result = SummaryEngineRouter(remote, local).summarize("전사")

        assertEquals(1, remote.calls)
        assertEquals(1, local.calls)
        assertEquals(SummaryEngineType.GEMMA_LOCAL, result.engine)
        assertEquals(SummaryEngineType.CODEX_OAUTH, result.requestedEngine)
        assertEquals("TIMEOUT", result.fallbackReason)
        assertEquals("codex", result.providerId)
    }

    @Test
    fun nonEligibleFailureNeverRunsLocal() = runTest {
        val remote = FakeRemote(
            profile = PROFILE,
            attempt = failure(RemoteSummaryFailureCode.USER_CANCELLED, fallback = false),
        )
        val local = FakeLocal()

        val thrown = runCatching {
            SummaryEngineRouter(remote, local).summarize("전사")
        }.exceptionOrNull() as SummaryRoutingException

        assertEquals(RemoteSummaryFailureCode.USER_CANCELLED, thrown.failure.code)
        assertEquals(1, remote.calls)
        assertEquals(0, local.calls)
    }

    private class FakeLocal : SummaryEngine {
        var calls = 0
        override suspend fun summarize(transcript: String): LocalSummary {
            calls += 1
            return LocalSummary(
                title = "로컬",
                bullets = listOf("핵심"),
                actionItems = emptyList(),
                engine = SummaryEngineType.GEMMA_LOCAL,
            )
        }
    }

    private class FakeRemote(
        profile: RemoteSummaryProfile?,
        private val attempt: RemoteSummaryAttempt = failure(
            RemoteSummaryFailureCode.NOT_CONNECTED,
            fallback = true,
        ),
    ) : RemoteSummaryGateway {
        override val activeProfile = MutableStateFlow(profile)
        var calls = 0
        override suspend fun summarize(transcript: String): RemoteSummaryAttempt {
            calls += 1
            return attempt
        }
    }

    companion object {
        private val PROFILE = RemoteSummaryProfile(
            profileId = "opaque-profile",
            provider = RemoteSummaryProvider.CODEX,
            displayName = "업무 계정",
        )

        private fun failure(
            code: RemoteSummaryFailureCode,
            fallback: Boolean,
        ): RemoteSummaryAttempt = RemoteSummaryAttempt.Failed(
            RemoteSummaryFailure(code, retryable = fallback, fallbackEligible = fallback),
        )
    }
}
