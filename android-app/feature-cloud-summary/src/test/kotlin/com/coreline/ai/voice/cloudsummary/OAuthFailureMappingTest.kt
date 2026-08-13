package com.coreline.ai.voice.cloudsummary

import ai.coreline.oauthllm.api.OAuthLlmFailure
import com.coreline.ai.voice.ondevice.api.RemoteSummaryFailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthFailureMappingTest {
    @Test
    fun retryAndFallbackFlagsArePreserved() {
        val timeout = OAuthLlmFailure.Timeout.toRemoteSummaryFailure()
        assertEquals(RemoteSummaryFailureCode.TIMEOUT, timeout.code)
        assertTrue(timeout.retryable)
        assertTrue(timeout.fallbackEligible)

        val cancelled = OAuthLlmFailure.UserCancelled.toRemoteSummaryFailure()
        assertEquals(RemoteSummaryFailureCode.USER_CANCELLED, cancelled.code)
        assertFalse(cancelled.retryable)
        assertFalse(cancelled.fallbackEligible)

        val invalid = OAuthLlmFailure.InvalidRequest.toRemoteSummaryFailure()
        assertEquals(RemoteSummaryFailureCode.INVALID_REQUEST, invalid.code)
        assertFalse(invalid.fallbackEligible)
    }
}
