package com.coreline.ai.voice.cloudsummary

import android.content.Context
import android.content.Intent
import ai.coreline.oauthllm.android.AndroidOAuthLlm
import ai.coreline.oauthllm.android.AndroidOAuthLlmClient
import ai.coreline.oauthllm.android.AndroidOAuthLlmConfiguration
import ai.coreline.oauthllm.android.OAuthProviderRegistration
import ai.coreline.oauthllm.api.ConnectRequest
import ai.coreline.oauthllm.api.ConnectResult
import ai.coreline.oauthllm.api.LlmConnection
import ai.coreline.oauthllm.api.LlmConnectionStatus
import ai.coreline.oauthllm.api.LlmGenerateRequest
import ai.coreline.oauthllm.api.LlmProvider
import ai.coreline.oauthllm.api.OAuthLlmException
import ai.coreline.oauthllm.api.OAuthLlmFailure
import ai.coreline.oauthllm.api.OAuthLlmFailureCode
import com.coreline.ai.voice.ondevice.api.RemoteSummaryAttempt
import com.coreline.ai.voice.ondevice.api.RemoteSummaryFailure
import com.coreline.ai.voice.ondevice.api.RemoteSummaryFailureCode
import com.coreline.ai.voice.ondevice.api.RemoteSummaryGateway
import com.coreline.ai.voice.ondevice.api.RemoteSummaryProfile
import com.coreline.ai.voice.ondevice.api.RemoteSummaryProvider
import com.coreline.ai.voice.ondevice.api.RemoteSummaryResult
import com.coreline.ai.voice.ondevice.api.RemoteSummaryUsage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Singleton
class OAuthCloudSummaryService @Inject constructor(
    @ApplicationContext context: Context,
) : RemoteSummaryGateway, OAuthAccountController {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val registrations = buildSet {
        addRegistration(LlmProvider.ANTHROPIC, BuildConfig.ANTHROPIC_CLIENT_ID)
        addRegistration(LlmProvider.CODEX, BuildConfig.CODEX_CLIENT_ID)
        addRegistration(LlmProvider.XAI, BuildConfig.XAI_CLIENT_ID)
    }
    private val client: AndroidOAuthLlmClient? = registrations.takeIf { it.isNotEmpty() }?.let {
        AndroidOAuthLlm.create(
            context,
            AndroidOAuthLlmConfiguration(registrations),
        )
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val selectedProfileId = MutableStateFlow(preferences.getString(KEY_ACTIVE_PROFILE, null))
    private val _accountState = MutableStateFlow(
        OAuthAccountState(providers = providerOptions()),
    )
    override val accountState: StateFlow<OAuthAccountState> = _accountState.asStateFlow()
    private val _activeProfile = MutableStateFlow<RemoteSummaryProfile?>(null)
    override val activeProfile: StateFlow<RemoteSummaryProfile?> = _activeProfile.asStateFlow()

    init {
        client?.let { sdk ->
            scope.launch {
                sdk.connections.collectLatest(::updateConnections)
            }
        }
    }

    override fun createConnectIntent(provider: RemoteSummaryProvider): Intent {
        check(registrations.any { it.provider == provider.toSdk() }) {
            "${provider.id} 공개 client ID가 설정되지 않았습니다."
        }
        return requireNotNull(client).createConnectIntent(ConnectRequest(provider.toSdk()))
    }

    override suspend fun completeConnect(data: Intent?): OAuthAccountActionResult =
        when (val result = requireNotNull(client).completeConnect(data)) {
            is ConnectResult.Connected -> {
                select(result.connection.profileId)
                OAuthAccountActionResult.Completed
            }
            ConnectResult.Cancelled -> OAuthAccountActionResult.Cancelled
            is ConnectResult.Failed -> OAuthAccountActionResult.Failed(result.failure.code.name)
        }

    override fun select(profileId: String) {
        val sdk = client ?: return
        val exists = sdk.connections.value.any {
            it.profileId == profileId && it.status == LlmConnectionStatus.CONNECTED
        }
        if (!exists) return
        selectedProfileId.value = profileId
        preferences.edit().putString(KEY_ACTIVE_PROFILE, profileId).apply()
        updateConnections(sdk.connections.value)
    }

    override suspend fun disconnect(profileId: String): OAuthAccountActionResult = try {
        requireNotNull(client).disconnect(profileId)
        if (selectedProfileId.value == profileId) clearSelection()
        OAuthAccountActionResult.Completed
    } catch (error: OAuthLlmException) {
        OAuthAccountActionResult.Failed(error.failure.code.name)
    }

    override suspend fun summarize(transcript: String): RemoteSummaryAttempt {
        val profile = activeProfile.value ?: return RemoteSummaryAttempt.Failed(
            RemoteSummaryFailure(
                RemoteSummaryFailureCode.NOT_CONNECTED,
                retryable = false,
                fallbackEligible = true,
            ),
        )
        val model = modelFor(profile.provider)
        if (model.isBlank()) {
            return RemoteSummaryAttempt.Failed(
                RemoteSummaryFailure(
                    RemoteSummaryFailureCode.APP_CONFIGURATION,
                    retryable = false,
                    fallbackEligible = true,
                ),
            )
        }
        val input = runCatching { CloudSummaryPrompt.build(transcript) }.getOrElse {
            return RemoteSummaryAttempt.Failed(
                RemoteSummaryFailure(
                    RemoteSummaryFailureCode.INVALID_REQUEST,
                    retryable = false,
                    fallbackEligible = false,
                ),
            )
        }
        return try {
            val generated = requireNotNull(client).generate(
                profile.profileId,
                LlmGenerateRequest(
                    operationId = UUID.randomUUID().toString(),
                    model = model,
                    messages = input.messages,
                    maxOutputTokens = MAX_OUTPUT_TOKENS,
                    responseSchema = input.schema,
                    timeoutMillis = TIMEOUT_MILLIS,
                ),
            )
            val parsed = runCatching { CloudSummaryParser.parse(generated.text) }.getOrElse {
                return RemoteSummaryAttempt.Failed(
                    RemoteSummaryFailure(
                        RemoteSummaryFailureCode.INVALID_PROVIDER_RESPONSE,
                        retryable = false,
                        fallbackEligible = true,
                    ),
                )
            }
            RemoteSummaryAttempt.Success(
                RemoteSummaryResult(
                    title = parsed.title,
                    bullets = parsed.bullets,
                    actionItems = parsed.actionItems,
                    provider = generated.provider.toDomain(),
                    modelId = generated.model,
                    providerRequestId = generated.providerRequestId,
                    latencyMs = generated.latencyMillis,
                    usage = RemoteSummaryUsage(
                        inputTokens = generated.usage?.inputTokens ?: 0,
                        outputTokens = generated.usage?.outputTokens ?: 0,
                        totalTokens = generated.usage?.totalTokens,
                    ),
                ),
            )
        } catch (error: OAuthLlmException) {
            RemoteSummaryAttempt.Failed(error.failure.toRemoteSummaryFailure())
        }
    }

    private fun updateConnections(connections: List<LlmConnection>) {
        val selected = selectedProfileId.value
        val connected = connections.firstOrNull {
            it.profileId == selected && it.status == LlmConnectionStatus.CONNECTED
        }
        if (selected != null && connected == null) {
            clearSelection()
        }
        _activeProfile.value = connected?.toDomain()
        _accountState.value = OAuthAccountState(
            providers = providerOptions(),
            profiles = connections.map { connection ->
                OAuthAccountProfile(
                    profileId = connection.profileId,
                    provider = connection.provider.toDomain(),
                    displayName = connection.displayName
                        ?.takeIf(String::isNotBlank)
                        ?: "${connection.provider.id} 계정",
                    authenticationRequired =
                        connection.status == LlmConnectionStatus.AUTHENTICATION_REQUIRED,
                )
            },
            activeProfileId = connected?.profileId,
        )
    }

    private fun clearSelection() {
        selectedProfileId.value = null
        preferences.edit().remove(KEY_ACTIVE_PROFILE).apply()
        _activeProfile.value = null
    }

    private fun providerOptions(): List<OAuthProviderOption> = RemoteSummaryProvider.entries.map { provider ->
        OAuthProviderOption(
            provider = provider,
            configured = registrations.any { it.provider == provider.toSdk() },
            modelConfigured = modelFor(provider).isNotBlank(),
        )
    }

    private fun modelFor(provider: RemoteSummaryProvider): String = when (provider) {
        RemoteSummaryProvider.ANTHROPIC -> BuildConfig.ANTHROPIC_MODEL
        RemoteSummaryProvider.CODEX -> BuildConfig.CODEX_MODEL
        RemoteSummaryProvider.XAI -> BuildConfig.XAI_MODEL
    }.trim()

    private fun MutableSet<OAuthProviderRegistration>.addRegistration(
        provider: LlmProvider,
        clientId: String,
    ) {
        clientId.trim().takeIf(String::isNotBlank)?.let {
            add(OAuthProviderRegistration(provider, it))
        }
    }

    private fun LlmConnection.toDomain(): RemoteSummaryProfile = RemoteSummaryProfile(
        profileId = profileId,
        provider = provider.toDomain(),
        displayName = displayName?.takeIf(String::isNotBlank) ?: "${provider.id} 계정",
    )

    private fun LlmProvider.toDomain(): RemoteSummaryProvider = when (this) {
        LlmProvider.ANTHROPIC -> RemoteSummaryProvider.ANTHROPIC
        LlmProvider.CODEX -> RemoteSummaryProvider.CODEX
        LlmProvider.XAI -> RemoteSummaryProvider.XAI
    }

    private fun RemoteSummaryProvider.toSdk(): LlmProvider = when (this) {
        RemoteSummaryProvider.ANTHROPIC -> LlmProvider.ANTHROPIC
        RemoteSummaryProvider.CODEX -> LlmProvider.CODEX
        RemoteSummaryProvider.XAI -> LlmProvider.XAI
    }

    private companion object {
        const val PREFERENCES = "oauth_cloud_summary"
        const val KEY_ACTIVE_PROFILE = "active_profile"
        const val MAX_OUTPUT_TOKENS = 1_200
        const val TIMEOUT_MILLIS = 90_000L
    }
}

internal fun OAuthLlmFailure.toRemoteSummaryFailure(): RemoteSummaryFailure = RemoteSummaryFailure(
    code = when (code) {
        OAuthLlmFailureCode.NOT_CONNECTED -> RemoteSummaryFailureCode.NOT_CONNECTED
        OAuthLlmFailureCode.AUTHENTICATION_REQUIRED ->
            RemoteSummaryFailureCode.AUTHENTICATION_REQUIRED
        OAuthLlmFailureCode.NETWORK_UNAVAILABLE -> RemoteSummaryFailureCode.NETWORK_UNAVAILABLE
        OAuthLlmFailureCode.RATE_LIMITED -> RemoteSummaryFailureCode.RATE_LIMITED
        OAuthLlmFailureCode.PROVIDER_UNAVAILABLE -> RemoteSummaryFailureCode.PROVIDER_UNAVAILABLE
        OAuthLlmFailureCode.TIMEOUT -> RemoteSummaryFailureCode.TIMEOUT
        OAuthLlmFailureCode.INVALID_PROVIDER_RESPONSE ->
            RemoteSummaryFailureCode.INVALID_PROVIDER_RESPONSE
        OAuthLlmFailureCode.INVALID_REQUEST -> RemoteSummaryFailureCode.INVALID_REQUEST
        OAuthLlmFailureCode.USER_CANCELLED -> RemoteSummaryFailureCode.USER_CANCELLED
    },
    retryable = retryable,
    fallbackEligible = fallbackEligible,
)
