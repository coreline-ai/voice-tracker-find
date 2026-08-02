package com.thinktank.recorder.next.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinktank.recorder.cloudsummary.OAuthAccountActionResult
import com.thinktank.recorder.cloudsummary.OAuthAccountController
import com.thinktank.recorder.cloudsummary.OAuthAccountState
import com.thinktank.recorder.cloudsummary.OAuthProviderOption
import com.thinktank.recorder.ondevice.api.RemoteSummaryProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CloudAccountsUiState(
    val accounts: OAuthAccountState = OAuthAccountState(
        providers = RemoteSummaryProvider.entries.map { OAuthProviderOption(it, false, false) },
    ),
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class CloudAccountsViewModel @Inject constructor(
    private val controller: OAuthAccountController,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<CloudAccountsUiState> = combine(
        controller.accountState,
        busy,
        message,
    ) { accounts, working, text ->
        CloudAccountsUiState(accounts, working, text)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudAccountsUiState())

    fun connectIntent(provider: RemoteSummaryProvider): Intent? = runCatching {
        controller.createConnectIntent(provider)
    }.onFailure {
        message.value = "이 Provider의 공개 OAuth client ID가 빌드에 설정되지 않았습니다."
    }.getOrNull()

    fun completeConnect(data: Intent?) {
        viewModelScope.launch {
            busy.value = true
            message.value = controller.completeConnect(data).message(
                completed = "OAuth 계정을 연결하고 활성화했습니다.",
            )
            busy.value = false
        }
    }

    fun select(profileId: String) {
        controller.select(profileId)
        message.value = "이 계정을 클라우드 우선 요약에 사용합니다."
    }

    fun disconnect(profileId: String) {
        viewModelScope.launch {
            busy.value = true
            message.value = controller.disconnect(profileId).message(
                completed = "기기에 저장된 OAuth 연결을 해제했습니다.",
            )
            busy.value = false
        }
    }

    private fun OAuthAccountActionResult.message(completed: String): String = when (this) {
        OAuthAccountActionResult.Completed -> completed
        OAuthAccountActionResult.Cancelled -> "OAuth 연결을 취소했습니다."
        is OAuthAccountActionResult.Failed -> "OAuth 작업을 완료하지 못했습니다 · $code"
    }
}
