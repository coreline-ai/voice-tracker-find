package com.thinktank.recorder.cloudsummary

import android.content.Intent
import com.thinktank.recorder.ondevice.api.RemoteSummaryProvider
import kotlinx.coroutines.flow.StateFlow

data class OAuthProviderOption(
    val provider: RemoteSummaryProvider,
    val configured: Boolean,
    val modelConfigured: Boolean,
)

data class OAuthAccountProfile(
    val profileId: String,
    val provider: RemoteSummaryProvider,
    val displayName: String,
    val authenticationRequired: Boolean,
)

data class OAuthAccountState(
    val providers: List<OAuthProviderOption>,
    val profiles: List<OAuthAccountProfile> = emptyList(),
    val activeProfileId: String? = null,
)

sealed interface OAuthAccountActionResult {
    data object Completed : OAuthAccountActionResult
    data object Cancelled : OAuthAccountActionResult
    data class Failed(val code: String) : OAuthAccountActionResult
}

interface OAuthAccountController {
    val accountState: StateFlow<OAuthAccountState>
    fun createConnectIntent(provider: RemoteSummaryProvider): Intent
    suspend fun completeConnect(data: Intent?): OAuthAccountActionResult
    fun select(profileId: String)
    suspend fun disconnect(profileId: String): OAuthAccountActionResult
}
