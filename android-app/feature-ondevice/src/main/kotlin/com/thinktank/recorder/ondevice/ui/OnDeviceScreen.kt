package com.thinktank.recorder.ondevice.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.thinktank.recorder.ondevice.api.OnDeviceSessionState
import com.thinktank.recorder.ondevice.api.MainRecordingSource
import com.thinktank.recorder.ondevice.api.SttCaptureProfile
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import com.thinktank.recorder.ondevice.data.OnDeviceSessionEntity
import com.thinktank.recorder.ondevice.modelpack.ModelId
import com.thinktank.recorder.ondevice.stt.SenseVoiceFileSttAvailability
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun OnDeviceScreen(
    state: OnDeviceUiState,
    mainRecorderActive: Boolean,
    onSelectMainRecording: (String) -> Unit,
    onTranscribeSelectedRecording: () -> Unit,
    onSelectSttProfile: (SttCaptureProfile) -> Unit,
    onSelectSummary: (SummaryEngineType) -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onCancelListening: () -> Unit,
    onHostStopped: () -> Unit,
    onClearMessage: () -> Unit,
    onSummarize: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onDownloadModel: (ModelId) -> Unit,
    onImportModel: (ModelId, android.net.Uri) -> Unit,
    onPauseModel: (ModelId) -> Unit,
    onDeleteModel: (ModelId) -> Unit,
    modifier: Modifier = Modifier,
    heroImageRes: Int? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestHostStopped by rememberUpdatedState(onHostStopped)
    var pendingImport by remember { mutableStateOf<ModelId?>(null) }
    var sourcePickerShown by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onStartListening()
    }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val model = pendingImport
        pendingImport = null
        if (uri != null && model != null) onImportModel(model, uri)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) latestHostStopped()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            latestHostStopped()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("ondevice-scroll"),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(bottom = 128.dp),
    ) {
        item {
            Hero(heroImageRes)
        }
        item {
            PrivacyBanner(Modifier.padding(horizontal = 20.dp))
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                SectionTitle("음성 인식", "실시간은 시스템 STT, 완료 녹음은 SenseVoice 로컬 STT로 처리합니다.")
                Spacer(Modifier.height(8.dp))
                SystemSttCard(available = state.systemSttAvailable)
                Spacer(Modifier.height(18.dp))
                SectionTitle(
                    "연속 전사 기준",
                    "문장이 확정되면 자동으로 다시 듣습니다. 기기 인식기가 실제 무음 시점을 판단합니다.",
                )
                Spacer(Modifier.height(10.dp))
                SttCaptureChoices(state, onSelectSttProfile)
            }
        }
        item {
            ListeningCard(
                state = state,
                mainRecorderActive = mainRecorderActive,
                onStart = {
                    if (
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        onStartListening()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStop = onStopListening,
                onCancel = onCancelListening,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item {
            MainRecordingImportCard(
                state = state,
                mainRecorderActive = mainRecorderActive,
                onOpenPicker = { sourcePickerShown = true },
                onTranscribe = onTranscribeSelectedRecording,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        state.message?.let { message ->
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(horizontal = 20.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    ) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onClearMessage) {
                            Icon(Icons.Default.Close, contentDescription = "알림 닫기")
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                SectionTitle("요약 방식", "전사 후 적용할 기기 내 처리 방식을 고르세요.")
                Spacer(Modifier.height(10.dp))
                SummaryChoices(state, onSelectSummary)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle("로컬 모델 관리", "파일 전사와 요약은 기기에서 실행합니다.")
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Wi‑Fi 전용", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(10.dp))
                state.models.forEach { model ->
                    ModelCard(
                        model = model,
                        installationAvailable = state.nativeAiAvailable,
                        onDownload = { onDownloadModel(model.descriptor.id) },
                        onImport = {
                            pendingImport = model.descriptor.id
                            fileLauncher.launch(arrayOf("*/*"))
                        },
                        onPause = { onPauseModel(model.descriptor.id) },
                        onDelete = { onDeleteModel(model.descriptor.id) },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                SectionTitle("로컬 기록", "서버 동기화 대상에 포함되지 않습니다.")
            }
        }
        if (state.sessions.isEmpty()) {
            item {
                Text(
                    "아직 온디바이스 기록이 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        } else {
            itemsIndexed(state.sessions, key = { _, session -> session.id }) { index, session ->
                SessionCard(
                    session = session,
                    latest = index == 0,
                    active = state.activeSessionId == session.id,
                    onSummarize = { onSummarize(session.id) },
                    onDelete = { onDeleteSession(session.id) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
    }

    if (sourcePickerShown) {
        MainRecordingSourceSheet(
            sources = state.mainRecordingSources,
            selectedId = state.selectedMainRecordingId,
            onSelect = {
                onSelectMainRecording(it)
                sourcePickerShown = false
            },
            onDismiss = { sourcePickerShown = false },
        )
    }

}

@Composable
private fun Hero(imageRes: Int?) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(164.dp)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF16231F),
                        Color(0xFF29443B),
                        Color(0xFF78664A),
                    ),
                ),
            ),
    ) {
        if (imageRes != null) {
            Image(
                painter = painterResource(imageRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xF2182924),
                                Color(0xA3223831),
                                Color(0x553F3A2D),
                            ),
                        ),
                    ),
            )
        }
        Icon(
            Icons.Default.GraphicEq,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.12f),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 20.dp)
                .size(116.dp),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp),
        ) {
            Text(
                "LOCAL AI",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFFD9C49A),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "목소리에서\n정리된 기록까지",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun PrivacyBanner(modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Security, contentDescription = null)
            Column {
                Text("이 기기에서만 처리", style = MaterialTheme.typography.titleMedium)
                Text(
                    "음성·전사·요약은 기기 밖으로 전송하지 않습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SystemSttCard(available: Boolean) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (available) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                tint = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Column(Modifier.weight(1f)) {
                Text("시스템 온디바이스 STT", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (available) "설치 없이 라이브 한국어 받아쓰기" else "이 기기에서 사용할 수 없음",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (available) "사용 가능" else "미지원",
                style = MaterialTheme.typography.labelMedium,
                color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SummaryChoice(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    available: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Surface(
        onClick = onClick,
        enabled = available,
        shape = RoundedCornerShape(16.dp),
        color = container,
        tonalElevation = if (selected) 2.dp else 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "선택됨")
            }
        }
    }
}

@Composable
private fun SummaryChoices(
    state: OnDeviceUiState,
    onSelect: (SummaryEngineType) -> Unit,
) {
    fun ready(id: ModelId): Boolean =
        state.models.firstOrNull { it.descriptor.id == id }?.status == ModelUiStatus.READY
    listOf(
        Triple(SummaryEngineType.EXTRACTIVE_KOTLIN, "빠른 요약", "추가 모델 없음 · 원문 문장만 선별"),
        Triple(
            SummaryEngineType.QWEN_LOCAL,
            "Qwen 로컬 AI · 실험적",
            if (!state.nativeAiAvailable) {
                state.nativeAiUnavailableReason ?: "이 기기에서 사용할 수 없음"
            } else if (ready(ModelId.QWEN_SUMMARY_KO)) {
                "모델 설치됨 · 생성형 제목·요약 · RAM 3GB 이상"
            } else {
                "563MB 모델 설치 필요 · RAM 3GB 이상"
            },
        ),
        Triple(
            SummaryEngineType.EXAONE_LOCAL,
            "EXAONE 한국어 AI · 후보",
            if (ready(ModelId.EXAONE_SUMMARY_KO)) {
                "812MB 모델 설치됨 · 한국어 2단계 요약"
            } else {
                "812MB 모델 설치 필요 · 한국어 품질 우선 후보"
            },
        ),
        Triple(
            SummaryEngineType.GEMMA_LOCAL,
            "Gemma 3 경량 AI · 후보",
            if (ready(ModelId.GEMMA_SUMMARY_KO)) {
                "공식 모델 설치됨 · LiteRT-LM CPU 실행"
            } else {
                "공식 .litertlm 파일 가져오기 필요 · 약 557MB"
            },
        ),
        Triple(SummaryEngineType.NONE, "요약하지 않음", "전사 원문만 기기에 저장"),
    ).forEachIndexed { index, item ->
        val icon = when (item.first) {
            SummaryEngineType.EXTRACTIVE_KOTLIN -> Icons.Default.GraphicEq
            SummaryEngineType.QWEN_LOCAL,
            SummaryEngineType.QWEN_LOCAL_GROUNDED,
            SummaryEngineType.EXAONE_LOCAL,
            SummaryEngineType.GEMMA_LOCAL,
            -> Icons.Default.Memory
            SummaryEngineType.NONE -> Icons.Default.Stop
        }
        SummaryChoice(
            title = item.second,
            description = item.third,
            icon = icon,
            selected = state.selectedSummary == item.first,
            available = when (item.first) {
                SummaryEngineType.QWEN_LOCAL ->
                    state.nativeAiAvailable && ready(ModelId.QWEN_SUMMARY_KO)
                SummaryEngineType.EXAONE_LOCAL ->
                    state.nativeAiAvailable && ready(ModelId.EXAONE_SUMMARY_KO)
                SummaryEngineType.GEMMA_LOCAL ->
                    state.nativeAiAvailable && ready(ModelId.GEMMA_SUMMARY_KO)
                else -> true
            },
            onClick = { onSelect(item.first) },
        )
        if (index < 4) Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SttCaptureChoices(
    state: OnDeviceUiState,
    onSelect: (SttCaptureProfile) -> Unit,
) {
    SttCaptureProfile.entries.forEachIndexed { index, profile ->
        SummaryChoice(
            title = profile.title,
            description = profile.description,
            icon = Icons.Default.GraphicEq,
            selected = state.selectedSttProfile == profile,
            available = !state.listening,
            onClick = { onSelect(profile) },
        )
        if (index < SttCaptureProfile.entries.lastIndex) Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MainRecordingImportCard(
    state: OnDeviceUiState,
    mainRecorderActive: Boolean,
    onOpenPicker: () -> Unit,
    onTranscribe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = state.mainRecordingSources.firstOrNull { it.id == state.selectedMainRecordingId }
    val availabilityMessage = when (state.fileSttAvailability) {
        SenseVoiceFileSttAvailability.READY ->
            "SenseVoice 한국어 파일 STT가 설치되었습니다. 녹음은 네트워크 없이 기기 안에서 전사됩니다."
        SenseVoiceFileSttAvailability.MODEL_NOT_INSTALLED ->
            "아래 모델 관리에서 SenseVoice 한국어 파일 STT를 Wi-Fi로 설치한 뒤 사용할 수 있습니다."
        SenseVoiceFileSttAvailability.NATIVE_UNSUPPORTED ->
            "이 기기는 arm64 로컬 STT runtime을 지원하지 않습니다."
    }
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("1번 탭 녹음 분석", style = MaterialTheme.typography.titleLarge)
            Text(
                "완료된 원본을 선택해 분석용 PCM으로 변환한 뒤 텍스트를 추출합니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(
                onClick = onOpenPicker,
                enabled = !state.fileTranscribing && !state.listening && !state.processing,
                modifier = Modifier.padding(top = 14.dp),
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (selected == null) "1번 탭 녹음 선택" else "다른 녹음 선택")
            }
            if (selected != null) {
                Text(
                    "선택됨 · ${formatTime(selected.createdAt)} · ${formatDuration(selected.durationMs / 1_000)} · ${selected.extension.uppercase()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "원본은 변경하지 않으며 분석용 PCM 임시 파일만 생성합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Text(
                    if (state.mainRecordingSources.isEmpty()) {
                        "선택 가능한 완료 녹음이 없습니다."
                    } else {
                        "완료된 원본 하나를 선택하세요."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Text(
                availabilityMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.fileSttAvailability == SenseVoiceFileSttAvailability.READY) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 10.dp),
            )
            Button(
                onClick = onTranscribe,
                enabled = selected != null &&
                    !mainRecorderActive &&
                    !state.listening &&
                    !state.processing &&
                    state.fileSttAvailability == SenseVoiceFileSttAvailability.READY,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("PCM 변환 후 텍스트 추출")
            }
        }
    }
}

@Composable
private fun MainRecordingSourceSheet(
    sources: List<MainRecordingSource>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("1번 탭 완료 녹음", style = MaterialTheme.typography.titleLarge)
            Text(
                "녹음 중·정리 중·무결성 정보가 없는 파일은 표시하지 않습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            if (sources.isEmpty()) {
                Text(
                    "선택 가능한 완료 녹음이 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 32.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    items(sources, key = { it.id }) { source ->
                        SummaryChoice(
                            title = "${formatTime(source.createdAt)} · ${formatDuration(source.durationMs / 1_000)}",
                            description = "${source.extension.uppercase()} · ${formatBytes(source.sizeBytes)} · ${sourceStorageLabel(source.storageState)}",
                            icon = Icons.Default.GraphicEq,
                            selected = source.id == selectedId,
                            available = true,
                            onClick = { onSelect(source.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListeningCard(
    state: OnDeviceUiState,
    mainRecorderActive: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transcriptScrollState = rememberScrollState()
    LaunchedEffect(state.liveTranscript, state.partialTranscript) {
        transcriptScrollState.animateScrollTo(transcriptScrollState.maxValue)
    }
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (state.listening) Icons.Default.GraphicEq else Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        when {
                            mainRecorderActive -> "기본 녹음이 사용 중입니다"
                            state.listening -> "온디바이스 음성 인식 중"
                            state.fileTranscribing -> "1번 탭 녹음 전사 중"
                            state.processing -> state.processingLabel ?: "기기에서 처리 중"
                            else -> "새 로컬 기록"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (!state.listening && !state.processing && !mainRecorderActive) {
                        Text(
                            "문장마다 자동으로 다시 듣고, 완료하면 전사와 요약을 저장합니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (
                state.listening ||
                state.fileTranscribing ||
                state.liveTranscript.isNotBlank() ||
                state.partialTranscript.isNotBlank()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(208.dp)
                        .padding(top = 14.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            RoundedCornerShape(14.dp),
                        )
                        .padding(14.dp)
                        .verticalScroll(transcriptScrollState),
                ) {
                    Text(
                        "현재 상태 · ${state.message ?: if (state.listening) "음성을 기다리는 중" else "대기"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (state.liveTranscript.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text("확정된 전사", style = MaterialTheme.typography.labelLarge)
                        Text(
                            state.liveTranscript,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (state.partialTranscript.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text("현재 인식 중", style = MaterialTheme.typography.labelLarge)
                        Text(
                            state.partialTranscript,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else if (state.liveTranscript.isBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "말씀을 시작하면 이 영역에 실시간 전사가 표시됩니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (state.processing) {
                Spacer(Modifier.height(14.dp))
                state.processingProgress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(16.dp))
            if (state.listening) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("완료")
                    }
                    OutlinedButton(onClick = onCancel) { Text("취소") }
                }
            } else if (state.processing) {
                OutlinedButton(onClick = onCancel) { Text("처리 취소") }
            } else {
                Button(
                    onClick = onStart,
                    enabled = !mainRecorderActive && !state.processing && state.systemSttAvailable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("말하기 시작")
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelUiState,
    installationAvailable: Boolean,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, contentDescription = null)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(model.descriptor.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        model.descriptor.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "실행 방식 · ${model.descriptor.runtimeType.name.replace('_', '-')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                if (model.status == ModelUiStatus.READY) {
                    Icon(Icons.Default.Check, contentDescription = "설치 완료")
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                modelStatusText(model),
                style = MaterialTheme.typography.labelMedium,
                color = if (model.status == ModelUiStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (
                model.status in setOf(
                    ModelUiStatus.DOWNLOADING,
                    ModelUiStatus.VERIFYING,
                    ModelUiStatus.INSTALLING,
                    ModelUiStatus.WAITING_FOR_WIFI,
                    ModelUiStatus.PAUSED,
                )
            ) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { model.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            model.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                when (model.status) {
                    ModelUiStatus.READY -> OutlinedButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("삭제")
                    }
                    ModelUiStatus.DOWNLOADING,
                    ModelUiStatus.WAITING_FOR_WIFI,
                    ModelUiStatus.VERIFYING,
                    ModelUiStatus.INSTALLING,
                    -> OutlinedButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("일시정지")
                    }
                    else -> {
                        OutlinedButton(onClick = onImport, enabled = installationAvailable) {
                            Icon(Icons.Default.UploadFile, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text(
                                if (model.descriptor.remoteDownloadEnabled) {
                                    "파일"
                                } else {
                                    "공식 파일 가져오기"
                                },
                            )
                        }
                        if (model.descriptor.remoteDownloadEnabled) {
                            FilledTonalButton(onClick = onDownload, enabled = installationAvailable) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text(if (model.status == ModelUiStatus.PAUSED) "Wi‑Fi에서 이어받기" else "Wi‑Fi 설치")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: OnDeviceSessionEntity,
    latest: Boolean,
    active: Boolean,
    onSummarize: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var fullTranscriptShown by rememberSaveable(session.id) { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth().testTag("session-${session.id}"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (latest) {
                        Text(
                            "최신 결과",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        session.title.ifBlank { "로컬 음성 기록" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${formatTime(session.createdAt)} · ${sessionStateLabel(session.state)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onDelete,
                    enabled = !active && !session.state.isActiveSessionState(),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "기록 삭제")
                }
            }
            if (session.sourceType == OnDeviceSessionEntity.SOURCE_TYPE_MAIN_RECORDER_CHUNK) {
                Text(
                    "원본 · 1번 탭 녹음${session.sourceDurationMs?.let { " · ${formatDuration(it / 1_000)}" }.orEmpty()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                "전사 방식 · ${sttEngineLabel(session.sttEngine)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (session.transcript.isNotBlank()) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text(
                    "전사 원문 · ${session.transcript.length}자",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    session.transcript,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = { fullTranscriptShown = true },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text("전체 전사 보기")
                }
            }
            if (session.summary.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("핵심 요약", style = MaterialTheme.typography.labelLarge)
                Text(
                    summaryProcessingLabel(session),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 3.dp),
                )
                session.summary.lines().filter(String::isNotBlank).forEachIndexed { index, text ->
                    Text(
                        "• $text",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("summary-${session.id}-$index"),
                    )
                }
                Text(
                    session.summaryPolicyVersion?.let { "요약 정책 v$it" } ?: "이전 요약 정책",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (!session.summaryFallbackReason.isNullOrBlank()) {
                    Text(
                        fallbackLabel(session.summaryFallbackReason),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (
                session.summary.isBlank() &&
                session.state == OnDeviceSessionState.COMPLETE.name
            ) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "요약 방식 · ${summaryEngineLabel(session.summaryEngine)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.actionItems.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("확인할 일", style = MaterialTheme.typography.labelLarge)
                session.actionItems.lines().filter(String::isNotBlank).forEach {
                    Text("□ $it", style = MaterialTheme.typography.bodyMedium)
                }
            }
            session.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (
                session.transcript.isNotBlank() &&
                session.state in setOf(
                    OnDeviceSessionState.TRANSCRIPT_READY.name,
                    OnDeviceSessionState.FAILED_RECOVERABLE.name,
                ) &&
                session.failureStage !=
                com.thinktank.recorder.ondevice.api.OnDeviceFailureStage.DELETE.name
            ) {
                OutlinedButton(onClick = onSummarize, modifier = Modifier.padding(top = 10.dp)) {
                    Text("현재 방식으로 요약")
                }
            }
        }
    }
    if (fullTranscriptShown) {
        FullTranscriptSheet(
            transcript = session.transcript,
            onDismiss = { fullTranscriptShown = false },
        )
    }
}

@Composable
private fun FullTranscriptSheet(
    transcript: String,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("full-transcript-sheet"),
        ) {
            Text("전체 전사", style = MaterialTheme.typography.titleLarge)
            Text(
                "${transcript.length}자 · 이 기기에 저장된 원문 전체입니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .padding(14.dp)
                        .testTag("full-transcript-scroll"),
                ) {
                    SelectionContainer {
                        Text(transcript, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("닫기")
            }
        }
    }
}

private fun modelStatusText(model: ModelUiState): String = when (model.status) {
    ModelUiStatus.NOT_INSTALLED -> if (model.descriptor.remoteDownloadEnabled) {
        "${formatBytes(model.descriptor.approximateDownloadBytes)} · 설치되지 않음"
    } else {
        "${formatBytes(model.descriptor.approximateDownloadBytes)} · 공식 모델 파일 가져오기 필요"
    }
    ModelUiStatus.WAITING_FOR_WIFI -> "Wi-Fi 연결 대기 · ${formatBytes(model.downloadedBytes)}"
    ModelUiStatus.DOWNLOADING ->
        buildString {
            append("${formatBytes(model.downloadedBytes)} / ${formatBytes(model.totalBytes)}")
            if (model.bytesPerSecond > 0) append(" · ${formatBytes(model.bytesPerSecond)}/s")
            if (model.etaSeconds > 0) append(" · 약 ${formatDuration(model.etaSeconds)} 남음")
        }
    ModelUiStatus.VERIFYING -> "SHA-256 검증 중"
    ModelUiStatus.INSTALLING -> "안전하게 설치 중"
    ModelUiStatus.READY -> "사용 준비 완료 · ${formatBytes(model.installedBytes)}"
    ModelUiStatus.PAUSED -> "일시정지 · ${formatBytes(model.downloadedBytes)} 받음"
    ModelUiStatus.FAILED -> "설치 실패"
}

private fun sessionStateLabel(state: String): String = when (state) {
    OnDeviceSessionState.STARTING.name -> "시작 중"
    OnDeviceSessionState.LISTENING.name -> "듣는 중"
    OnDeviceSessionState.AUDIO_READY.name -> "전사 대기"
    OnDeviceSessionState.TRANSCRIBING.name -> "전사 중"
    OnDeviceSessionState.TRANSCRIPT_READY.name -> "전사 완료"
    OnDeviceSessionState.SUMMARIZING.name -> "요약 중"
    OnDeviceSessionState.CANCELLING.name -> "취소 정리 중"
    OnDeviceSessionState.DELETING.name -> "삭제 중"
    OnDeviceSessionState.COMPLETE.name -> "완료"
    OnDeviceSessionState.CANCELLED.name -> "취소됨"
    OnDeviceSessionState.FAILED_RECOVERABLE.name -> "다시 시도 가능"
    OnDeviceSessionState.FAILED_PERMANENT.name -> "처리 실패"
    else -> "대기"
}

private fun summaryEngineLabel(engine: String): String = when (engine) {
    SummaryEngineType.EXTRACTIVE_KOTLIN.name -> "빠른 요약 · Kotlin 추출형"
    SummaryEngineType.QWEN_LOCAL.name -> "Qwen 로컬 AI"
    SummaryEngineType.QWEN_LOCAL_GROUNDED.name -> "Qwen 로컬 AI · 원문 근거 보강"
    SummaryEngineType.EXAONE_LOCAL.name -> "EXAONE 한국어 로컬 AI"
    SummaryEngineType.GEMMA_LOCAL.name -> "Gemma 3 LiteRT-LM"
    SummaryEngineType.NONE.name -> "요약하지 않음"
    else -> "알 수 없는 방식"
}

private fun summaryProcessingLabel(session: OnDeviceSessionEntity): String {
    val actual = summaryEngineLabel(session.summaryEngine)
    val requested = session.requestedSummaryEngine
        ?.takeIf { it != session.summaryEngine }
        ?.let(::summaryEngineLabel)
    val engineLabel = if (requested == null) {
        "처리 방식 · $actual"
    } else {
        "요청 · $requested / 실제 · $actual"
    }
    val modelLabel = listOfNotNull(
        session.actualSummaryModelId?.let { "모델 $it" },
        session.summaryRuntimeType,
        session.summaryModelVersion?.let { "v$it" },
    ).joinToString(" · ")
    return if (modelLabel.isBlank()) engineLabel else "$engineLabel\n$modelLabel"
}

private fun fallbackLabel(reason: String): String = when {
    reason.contains("QWEN_QUALITY_REJECTED") -> if (':' in reason) {
        "선택한 AI 품질 검사 후 원문 기반 요약으로 대체 · ${reason.substringAfter(':')}"
    } else {
        "선택한 AI 품질 검사 후 원문 기반 요약으로 대체"
    }
    reason.contains("LOCAL_LLM_RUNTIME_FAILED") || reason.contains("QWEN_RUNTIME_FAILED") ->
        "선택한 AI 실행 실패 후 원문 기반 요약으로 대체"
    reason.contains("NO_SAFE_EXTRACTIVE_SUMMARY") -> "안전한 핵심을 만들지 못해 전사 원문만 보존"
    else -> "원문 기반 안전 대체 요약"
}

private fun sttEngineLabel(engine: String): String = when (engine) {
    "SENSEVOICE_LOCAL_FILE" -> "SenseVoice 로컬 파일 STT"
    "ANDROID_ON_DEVICE" -> "Android 시스템 온디바이스 STT"
    "ANDROID_ON_DEVICE_FILE" -> "이전 시스템 파일 STT 기록"
    else -> "알 수 없는 방식"
}

private fun sourceStorageLabel(state: String): String = when (state) {
    "READY" -> "기기 보관됨"
    "UPLOADED" -> "동기화 완료 · 기기 보관됨"
    "RETRY" -> "동기화 재시도 대기"
    "FAILED" -> "동기화 실패 · 원본 보관됨"
    "CONFLICT" -> "동기화 충돌 · 원본 보관됨"
    else -> "기기 보관됨"
}

private fun String.isActiveSessionState(): Boolean =
    this in setOf(
        OnDeviceSessionState.STARTING.name,
        OnDeviceSessionState.LISTENING.name,
        OnDeviceSessionState.TRANSCRIBING.name,
        OnDeviceSessionState.SUMMARIZING.name,
        OnDeviceSessionState.CANCELLING.name,
        OnDeviceSessionState.DELETING.name,
    )

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1fGB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.0fMB".format(bytes / (1024.0 * 1024))
    else -> "%.0fKB".format(bytes / 1024.0)
}

private fun formatDuration(seconds: Long): String = when {
    seconds >= 3_600 -> "${seconds / 3_600}시간 ${(seconds % 3_600) / 60}분"
    seconds >= 60 -> "${seconds / 60}분 ${seconds % 60}초"
    else -> "${seconds}초"
}

private fun formatTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("M월 d일 HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
