package com.thinktank.recorder.next.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thinktank.recorder.next.data.local.ChunkEntity
import com.thinktank.recorder.next.data.local.NoteEntity
import com.thinktank.recorder.next.data.local.RecordingSessionEntity
import com.thinktank.recorder.next.data.local.RecordingState
import com.thinktank.recorder.next.data.remote.ApkInfo
import com.thinktank.recorder.next.data.remote.ReceiverApi
import com.thinktank.recorder.next.data.repository.NotesRepository
import com.thinktank.recorder.next.data.repository.RecordingRepository
import com.thinktank.recorder.next.data.settings.AppPreferences
import com.thinktank.recorder.next.data.settings.UserSettings
import com.thinktank.recorder.next.recording.RecorderController
import com.thinktank.recorder.next.worker.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RecordingUiState(
    val session: RecordingSessionEntity? = null,
    val chunk: ChunkEntity? = null,
    val pendingUploads: Int = 0,
    val amplitude: Float = 0f,
    val elapsedMs: Long = 0,
    val commandError: String? = null,
) {
    val isActive: Boolean
        get() = session?.state in setOf(
            RecordingState.PREPARING,
            RecordingState.RECORDING,
            RecordingState.WAITING,
            RecordingState.FINALIZING,
        )
}

@HiltViewModel
class RecordingViewModel @Inject constructor(
    repository: RecordingRepository,
    private val controller: RecorderController,
) : ViewModel() {
    private val tick = MutableStateFlow(System.currentTimeMillis())
    private val commandError = MutableStateFlow<String?>(null)

    private val recordingState = combine(
        repository.latestSession,
        repository.latestChunk,
        repository.pendingUploads,
        repository.amplitude,
        tick,
    ) { session, chunk, pending, amplitude, now ->
        RecordingUiState(
            session = session,
            chunk = chunk,
            pendingUploads = pending,
            amplitude = amplitude,
            elapsedMs = if (
                session != null &&
                session.state != RecordingState.STOPPED &&
                session.state != RecordingState.FAILED
            ) {
                (now - session.startedAt).coerceAtLeast(0)
            } else {
                session?.stoppedAt?.minus(session.startedAt)?.coerceAtLeast(0) ?: 0
            },
        )
    }

    val uiState: StateFlow<RecordingUiState> = combine(
        recordingState,
        commandError,
    ) { state, error -> state.copy(commandError = error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingUiState())

    init {
        viewModelScope.launch {
            while (isActive) {
                tick.value = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }

    fun start() {
        commandError.value = null
        runCatching(controller::start).onFailure {
            commandError.value = it.message ?: "녹음을 시작하지 못했습니다"
        }
    }

    fun stop() {
        runCatching(controller::stop).onFailure {
            commandError.value = it.message ?: "녹음을 정지하지 못했습니다"
        }
    }
}

data class NotesUiState(
    val notes: List<NoteEntity> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repository: NotesRepository,
    private val scheduler: SyncScheduler,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<NotesUiState> = combine(
        repository.notes,
        busy,
        message,
    ) { notes, isBusy, text -> NotesUiState(notes, isBusy, text) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

    fun sync() {
        scheduler.enqueueManual()
        message.value = "동기화를 예약했습니다"
    }

    fun create(folder: String, name: String, content: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            busy.value = true
            repository.create(folder, name, content)
                .onSuccess {
                    message.value = "노트를 만들었습니다"
                    onCreated(it.serverId)
                }
                .onFailure { message.value = it.message ?: "노트를 만들지 못했습니다" }
            busy.value = false
        }
    }

    fun save(id: String, content: String) {
        viewModelScope.launch {
            busy.value = true
            repository.save(id, content)
                .onSuccess {
                    message.value = if (it.syncState == "CONFLICT") {
                        "충돌이 발생해 로컬 편집을 보존했습니다"
                    } else {
                        "노트를 저장했습니다"
                    }
                }
                .onFailure { message.value = it.message ?: "저장하지 못했습니다" }
            busy.value = false
        }
    }

    fun archive(id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            repository.archive(id)
                .onSuccess {
                    message.value = "노트를 보관했습니다"
                    onDone()
                }
                .onFailure { message.value = it.message ?: "보관하지 못했습니다" }
            busy.value = false
        }
    }
}

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val testing: Boolean = false,
    val checkingVersion: Boolean = false,
    val connectionMessage: String? = null,
    val apkInfo: ApkInfo? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val api: ReceiverApi,
    private val scheduler: SyncScheduler,
) : ViewModel() {
    private val testing = MutableStateFlow(false)
    private val checkingVersion = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val apkInfo = MutableStateFlow<ApkInfo?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        preferences.settings,
        testing,
        checkingVersion,
        message,
        apkInfo,
    ) { settings, isTesting, isCheckingVersion, text, version ->
        SettingsUiState(
            settings = settings,
            testing = isTesting,
            checkingVersion = isCheckingVersion,
            connectionMessage = text,
            apkInfo = version,
        )
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun saveServer(url: String, userId: String, token: String, test: Boolean = false) {
        viewModelScope.launch {
            testing.value = test
            runCatching {
                preferences.updateServer(url, userId, token)
                if (test) {
                    val saved = preferences.current()
                    check(api.health(saved)) { "서버 health 확인 실패" }
                    api.listNotes(saved)
                }
            }.onSuccess {
                message.value = if (test) "서버와 안전하게 연결되었습니다" else "서버 설정을 저장했습니다"
            }.onFailure {
                message.value = it.message ?: "서버 설정을 확인하세요"
            }
            testing.value = false
        }
    }

    fun updateChunkMinutes(value: Int) {
        viewModelScope.launch { preferences.updateChunkMinutes(value) }
    }

    fun updateSchedule(enabled: Boolean, start: Int, end: Int) {
        viewModelScope.launch { preferences.updateSchedule(enabled, start, end) }
    }

    fun updateAutoSync(enabled: Boolean, wifiOnly: Boolean) {
        viewModelScope.launch {
            preferences.updateAutoSync(enabled, wifiOnly)
            scheduler.reconcileAutoSync()
        }
    }

    fun checkVersion() {
        viewModelScope.launch {
            checkingVersion.value = true
            runCatching {
                val saved = preferences.current()
                require(saved.serverUrl.isNotBlank() && saved.token.isNotBlank()) {
                    "서버 설정을 먼저 저장하세요"
                }
                api.apkInfo(saved)
            }.onSuccess {
                apkInfo.value = it
                message.value = "서버의 앱 버전을 확인했습니다"
            }.onFailure {
                message.value = it.message ?: "버전 정보를 확인하지 못했습니다"
            }
            checkingVersion.value = false
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch { preferences.completeOnboarding() }
    }
}
