package com.thinktank.recorder.next.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.thinktank.recorder.next.data.local.ChunkEntity
import com.thinktank.recorder.next.data.local.NoteEntity
import com.thinktank.recorder.next.data.local.RecordingSessionEntity
import com.thinktank.recorder.next.data.local.RecordingState
import com.thinktank.recorder.next.data.remote.ApkInfo
import com.thinktank.recorder.next.data.remote.ReceiverApi
import com.thinktank.recorder.next.data.repository.NotesRepository
import com.thinktank.recorder.next.data.repository.QueueActionResult
import com.thinktank.recorder.next.data.repository.RecordingRepository
import com.thinktank.recorder.next.data.settings.AppPreferences
import com.thinktank.recorder.next.data.settings.UserSettings
import com.thinktank.recorder.next.data.storage.AppStorageMonitor
import com.thinktank.recorder.next.data.storage.AppStorageSnapshot
import com.thinktank.recorder.next.recording.RecorderController
import com.thinktank.recorder.next.recording.RecordingRuntime
import com.thinktank.recorder.next.worker.SyncScheduler
import com.thinktank.recorder.next.worker.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
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
    val recentChunks: List<ChunkEntity> = emptyList(),
    val pendingUploads: Int = 0,
    val amplitude: Float = 0f,
    val elapsedMs: Long = 0,
    val commandError: String? = null,
    val queueMessage: String? = null,
    val syncQueue: List<ChunkEntity> = emptyList(),
    val attentionUploads: Int = 0,
    val storage: AppStorageSnapshot? = null,
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
    private val repository: RecordingRepository,
    private val controller: RecorderController,
    private val runtime: RecordingRuntime,
    private val scheduler: SyncScheduler,
    private val storageMonitor: AppStorageMonitor,
) : ViewModel() {
    private val tick = MutableStateFlow(System.currentTimeMillis())
    private val storage = MutableStateFlow<AppStorageSnapshot?>(null)
    private val queueMessage = MutableStateFlow<String?>(null)

    private val liveRecordingState = combine(
        repository.latestSession,
        repository.latestChunk,
        repository.recentChunks,
        repository.pendingUploads,
        repository.amplitude,
    ) { session, chunk, recentChunks, pending, amplitude ->
        RecordingUiState(
            session = session,
            chunk = chunk,
            recentChunks = recentChunks,
            pendingUploads = pending,
            amplitude = amplitude,
        )
    }

    private val baseRecordingState = combine(
        liveRecordingState,
        repository.syncQueue,
        repository.attentionUploads,
        storage,
    ) { state, queue, attention, snapshot ->
        state.copy(
            syncQueue = queue,
            attentionUploads = attention,
            storage = snapshot,
        )
    }

    private val recordingState = combine(baseRecordingState, tick) { state, now ->
        state.copy(
            elapsedMs = if (
                state.session != null &&
                state.session.state != RecordingState.STOPPED &&
                state.session.state != RecordingState.FAILED
            ) {
                (now - state.session.startedAt).coerceAtLeast(0)
            } else {
                state.session?.stoppedAt
                    ?.minus(state.session.startedAt)
                    ?.coerceAtLeast(0)
                    ?: 0
            },
        )
    }

    val uiState: StateFlow<RecordingUiState> = combine(
        recordingState,
        runtime.commandError,
        queueMessage,
    ) { state, error, actionMessage ->
        state.copy(commandError = error, queueMessage = actionMessage)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingUiState())

    init {
        viewModelScope.launch {
            while (isActive) {
                tick.value = System.currentTimeMillis()
                delay(1_000)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.recoverInterruptedDeletes()
            while (isActive) {
                storage.value = storageMonitor.snapshot()
                delay(STORAGE_REFRESH_MS)
            }
        }
    }

    fun start() {
        runtime.clearCommandError()
        runCatching(controller::start).onFailure {
            runtime.reportCommandError(it.message ?: "녹음을 시작하지 못했습니다")
        }
    }

    fun stop() {
        runCatching(controller::stop).onFailure {
            runtime.reportCommandError(it.message ?: "녹음을 정지하지 못했습니다")
        }
    }

    fun retryUpload(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            queueMessage.value = when (val result = repository.retryUpload(id)) {
                QueueActionResult.Completed -> {
                    scheduler.enqueueManual()
                    "업로드 재시도를 시작했습니다."
                }
                is QueueActionResult.Rejected -> result.message
            }
            storage.value = storageMonitor.snapshot()
        }
    }

    fun deleteStoredChunk(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            queueMessage.value = when (val result = repository.deleteStoredChunk(id)) {
                QueueActionResult.Completed -> "기기 보관함에서 원본을 정리했습니다."
                is QueueActionResult.Rejected -> result.message
            }
            storage.value = storageMonitor.snapshot()
        }
    }

    private companion object {
        const val STORAGE_REFRESH_MS = 15_000L
    }
}

data class NotesUiState(
    val notes: List<NoteEntity> = emptyList(),
    val busy: Boolean = false,
    val syncing: Boolean = false,
    val message: String? = null,
)

/** A retry that is waiting for WorkManager backoff is actionable, not an active transfer. */
internal fun isManualSyncInProgress(
    state: WorkInfo.State?,
    runAttemptCount: Int,
): Boolean =
    state == WorkInfo.State.RUNNING ||
        (state == WorkInfo.State.ENQUEUED && runAttemptCount == 0)

internal fun manualSyncConfigurationMessage(isServerConfigured: Boolean): String? =
    if (isServerConfigured) {
        null
    } else {
        "서버 설정이 없습니다. 설정 탭에서 수신기 주소, 사용자 ID, Receiver token을 저장하세요."
    }

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repository: NotesRepository,
    private val scheduler: SyncScheduler,
    private val preferences: AppPreferences,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<NotesUiState> = combine(
        repository.notes,
        busy,
        message,
        scheduler.manualWorkInfo,
    ) { notes, isBusy, text, work ->
        NotesUiState(
            notes = notes,
            busy = isBusy,
            syncing = isManualSyncInProgress(work?.state, work?.runAttemptCount ?: 0),
            // A direct action such as the missing-settings preflight must not be
            // hidden behind the previous WorkManager terminal result.
            message = text ?: work?.syncMessage(),
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

    fun sync() {
        viewModelScope.launch {
            manualSyncConfigurationMessage(preferences.current().isServerConfigured)?.let {
                message.value = it
                return@launch
            }
            message.value = null
            scheduler.enqueueManual()
        }
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

    private fun WorkInfo.syncMessage(): String = when (state) {
        WorkInfo.State.ENQUEUED -> if (runAttemptCount > 0) {
            "서버 연결을 재시도 중입니다${progress.getString(SyncWorker.KEY_ERROR)?.let { " · $it" }.orEmpty()}"
        } else {
            "동기화를 대기 중입니다"
        }
        WorkInfo.State.RUNNING -> "동기화 중입니다"
        WorkInfo.State.SUCCEEDED -> {
            val uploaded = outputData.getInt(SyncWorker.KEY_UPLOADED, 0)
            val notes = outputData.getInt(SyncWorker.KEY_NOTES, 0)
            "동기화 완료 · 녹음 ${uploaded}개 전송 · 노트 ${notes}개 확인"
        }
        WorkInfo.State.FAILED ->
            "동기화 실패 · ${outputData.getString(SyncWorker.KEY_ERROR) ?: "서버 설정과 네트워크를 확인하세요"}"
        WorkInfo.State.CANCELLED -> "동기화가 취소되었습니다"
        WorkInfo.State.BLOCKED -> "동기화를 대기 중입니다"
    }
}

data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
    val testing: Boolean = false,
    val checkingVersion: Boolean = false,
    val connectionMessage: String? = null,
    val apkInfo: ApkInfo? = null,
)

internal suspend fun validateAndCommitServer(
    candidate: UserSettings,
    test: Boolean,
    health: suspend (UserSettings) -> Boolean,
    authenticatedProbe: suspend (UserSettings) -> Unit,
    commit: suspend (UserSettings) -> Unit,
) {
    if (test) {
        check(health(candidate)) { "서버 health 확인 실패" }
        authenticatedProbe(candidate)
    }
    commit(candidate)
}

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
                val candidate = preferences.serverCandidate(url, userId, token)
                validateAndCommitServer(
                    candidate = candidate,
                    test = test,
                    health = api::health,
                    authenticatedProbe = { api.listNotes(it) },
                    commit = {
                        preferences.updateServer(
                            it.serverUrl,
                            it.userId,
                            it.token,
                        )
                    },
                )
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
