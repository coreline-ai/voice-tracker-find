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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.thinktank.recorder.ondevice.api.OnDeviceSessionState
import com.thinktank.recorder.ondevice.api.SttEngineType
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import com.thinktank.recorder.ondevice.data.OnDeviceSessionEntity
import com.thinktank.recorder.ondevice.modelpack.ModelId
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun OnDeviceScreen(
    state: OnDeviceUiState,
    mainRecorderActive: Boolean,
    onSelectStt: (SttEngineType) -> Unit,
    onSelectSummary: (SummaryEngineType) -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onCancelListening: () -> Unit,
    onHostStopped: () -> Unit,
    onSummarize: (String) -> Unit,
    onRetryTranscription: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onDownloadModel: (ModelId, Boolean) -> Unit,
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
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var moonshineLicenseAccepted by rememberSaveable { mutableStateOf(false) }
    var showMoonshineLicense by remember { mutableStateOf(false) }
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
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Hero(heroImageRes)
        }
        item {
            PrivacyBanner(Modifier.padding(horizontal = 20.dp))
        }
        state.message?.let { message ->
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(horizontal = 20.dp),
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                SectionTitle("음성 인식", "사용할 온디바이스 STT 엔진을 선택하세요.")
                Spacer(Modifier.height(10.dp))
                EngineChoice(
                    title = "시스템 온디바이스 STT",
                    description = if (state.systemSttAvailable) {
                        "설치 없이 라이브 한국어 받아쓰기"
                    } else {
                        "이 기기에서 사용할 수 없음"
                    },
                    selected = state.selectedStt == SttEngineType.ANDROID_ON_DEVICE,
                    available = state.systemSttAvailable,
                    onClick = { onSelectStt(SttEngineType.ANDROID_ON_DEVICE) },
                )
                Spacer(Modifier.height(8.dp))
                val moonshineReady = state.models
                    .firstOrNull { it.descriptor.id == ModelId.MOONSHINE_KO }
                    ?.status == ModelUiStatus.READY
                EngineChoice(
                    title = "Moonshine 한국어 STT",
                    description = if (!state.nativeAiAvailable) {
                        state.nativeAiUnavailableReason ?: "이 기기에서 사용할 수 없음"
                    } else if (moonshineReady) {
                        "모델 설치됨 · 독립 로컬 음성 인식"
                    } else {
                        "약 47MB 다운로드 · 설치 후 약 69MB"
                    },
                    selected = state.selectedStt == SttEngineType.MOONSHINE_LOCAL,
                    available = state.nativeAiAvailable,
                    onClick = { onSelectStt(SttEngineType.MOONSHINE_LOCAL) },
                )
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                SectionTitle("요약 방식", "전사 후 실행할 로컬 처리 방식을 선택하세요.")
                Spacer(Modifier.height(10.dp))
                SummaryChoices(state, onSelectSummary)
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
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle("AI 모델 관리", "모델 파일만 내려받고 추론은 기기에서 실행합니다.")
                    }
                    Text("Wi-Fi만", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                }
                Spacer(Modifier.height(10.dp))
                state.models.forEach { model ->
                    ModelCard(
                        model = model,
                        wifiOnly = wifiOnly,
                        installationAvailable = state.nativeAiAvailable,
                        licenseAccepted = model.descriptor.id != ModelId.MOONSHINE_KO ||
                            moonshineLicenseAccepted,
                        onReadLicense = {
                            if (model.descriptor.id == ModelId.MOONSHINE_KO) {
                                showMoonshineLicense = true
                            }
                        },
                        onDownload = { onDownloadModel(model.descriptor.id, wifiOnly) },
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
            items(state.sessions, key = { it.id }) { session ->
                SessionCard(
                    session = session,
                    active = state.activeSessionId == session.id,
                    onSummarize = { onSummarize(session.id) },
                    onRetryTranscription = { onRetryTranscription(session.id) },
                    onDelete = { onDeleteSession(session.id) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }

    if (showMoonshineLicense) {
        val licenseText = remember {
            runCatching {
                context.assets.open("licenses/MOONSHINE-KOREAN-MODEL-LICENSE.txt")
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrElse { "모델 라이선스 원문을 불러오지 못했습니다." }
        }
        AlertDialog(
            onDismissRequest = { showMoonshineLicense = false },
            title = { Text("Moonshine 한국어 모델 라이선스") },
            text = {
                Text(
                    licenseText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .height(420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        moonshineLicenseAccepted = true
                        showMoonshineLicense = false
                    },
                ) {
                    Text("읽었으며 동의")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMoonshineLicense = false }) { Text("닫기") }
            },
        )
    }
}

@Composable
private fun Hero(imageRes: Int?) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(210.dp)
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
                .padding(end = 18.dp)
                .size(150.dp),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp),
        ) {
            Text(
                "LOCAL AI",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFFD9C49A),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "목소리에서\n정리된 기록까지",
                style = MaterialTheme.typography.headlineLarge,
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
                    "음성·전사·요약은 외부로 전송하지 않습니다. 네트워크는 모델 설치에만 사용합니다.",
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
private fun EngineChoice(
    title: String,
    description: String,
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
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (selected) Icons.Default.Check else Icons.Default.Mic,
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
        }
    }
}

@Composable
private fun SummaryChoices(
    state: OnDeviceUiState,
    onSelect: (SummaryEngineType) -> Unit,
) {
    val qwenReady = state.models
        .firstOrNull { it.descriptor.id == ModelId.QWEN_SUMMARY_KO }
        ?.status == ModelUiStatus.READY
    listOf(
        Triple(SummaryEngineType.EXTRACTIVE_KOTLIN, "빠른 요약", "추가 모델 없음 · 원문 문장만 선별"),
        Triple(
            SummaryEngineType.QWEN_LOCAL,
            "Qwen 로컬 AI · 실험적",
            if (!state.nativeAiAvailable) {
                state.nativeAiUnavailableReason ?: "이 기기에서 사용할 수 없음"
            } else if (qwenReady) {
                "모델 설치됨 · 생성형 제목·요약 · RAM 3GB 이상"
            } else {
                "563MB 모델 설치 필요 · RAM 3GB 이상"
            },
        ),
        Triple(SummaryEngineType.NONE, "요약하지 않음", "전사 원문만 기기에 저장"),
    ).forEachIndexed { index, item ->
        EngineChoice(
            title = item.second,
            description = item.third,
            selected = state.selectedSummary == item.first,
            available = item.first != SummaryEngineType.QWEN_LOCAL || state.nativeAiAvailable,
            onClick = { onSelect(item.first) },
        )
        if (index < 2) Spacer(Modifier.height(8.dp))
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
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                if (state.listening) Icons.Default.GraphicEq else Icons.Default.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    mainRecorderActive -> "기본 녹음이 사용 중입니다"
                    state.listening -> "온디바이스 음성 인식 중"
                    state.processing -> state.processingLabel ?: "기기에서 처리 중"
                    else -> "새 로컬 기록"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            if (state.partialTranscript.isNotBlank()) {
                Text(
                    state.partialTranscript,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp),
                )
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
            Spacer(Modifier.height(18.dp))
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
                    enabled = !mainRecorderActive && !state.processing &&
                        (state.selectedStt != SttEngineType.ANDROID_ON_DEVICE ||
                            state.systemSttAvailable) &&
                        (state.selectedStt != SttEngineType.MOONSHINE_LOCAL ||
                            state.nativeAiAvailable),
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (state.selectedStt == SttEngineType.MOONSHINE_LOCAL) {
                            "로컬 STT 시작"
                        } else {
                            "말하기 시작"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelUiState,
    wifiOnly: Boolean,
    installationAvailable: Boolean,
    licenseAccepted: Boolean,
    onReadLicense: () -> Unit,
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
                    if (model.descriptor.id == ModelId.MOONSHINE_KO) {
                        Text(
                            "Powered by Moonshine AI",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
            if (
                model.descriptor.id == ModelId.MOONSHINE_KO &&
                model.status != ModelUiStatus.READY
            ) {
                Text(
                    "한국어 모델은 Community License가 적용되며 상업 이용 조건이 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = onReadLicense) {
                    Text(if (licenseAccepted) "라이선스 동의 완료" else "라이선스 읽기 및 동의")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                when (model.status) {
                    ModelUiStatus.READY -> {
                        OutlinedButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("삭제")
                        }
                    }
                    ModelUiStatus.DOWNLOADING,
                    ModelUiStatus.WAITING_FOR_WIFI,
                    ModelUiStatus.VERIFYING,
                    ModelUiStatus.INSTALLING,
                    -> {
                        OutlinedButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("일시정지")
                        }
                    }
                    else -> {
                        OutlinedButton(
                            onClick = onImport,
                            enabled = licenseAccepted && installationAvailable,
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("파일")
                        }
                        FilledTonalButton(
                            onClick = onDownload,
                            enabled = licenseAccepted && installationAvailable,
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text(
                                if (model.status == ModelUiStatus.PAUSED) "이어받기"
                                else if (wifiOnly) "Wi-Fi 설치" else "설치",
                            )
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
    active: Boolean,
    onSummarize: () -> Unit,
    onRetryTranscription: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
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
            if (session.transcript.isNotBlank()) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("전사 원문", style = MaterialTheme.typography.labelLarge)
                Text(
                    session.transcript,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (session.summary.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("핵심 요약", style = MaterialTheme.typography.labelLarge)
                session.summary.lines().filter(String::isNotBlank).forEach {
                    Text("• $it", style = MaterialTheme.typography.bodyMedium)
                }
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
            if (
                session.transcript.isBlank() &&
                !session.audioPath.isNullOrBlank() &&
                session.sttEngine == SttEngineType.MOONSHINE_LOCAL.name &&
                session.state in setOf(
                    OnDeviceSessionState.AUDIO_READY.name,
                    OnDeviceSessionState.FAILED_RECOVERABLE.name,
                ) &&
                session.failureStage != com.thinktank.recorder.ondevice.api.OnDeviceFailureStage.DELETE.name
            ) {
                OutlinedButton(
                    onClick = onRetryTranscription,
                    enabled = !active,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Text("전사 다시 시도")
                }
            }
        }
    }
}

private fun modelStatusText(model: ModelUiState): String = when (model.status) {
    ModelUiStatus.NOT_INSTALLED -> "${formatBytes(model.descriptor.approximateDownloadBytes)} · 설치되지 않음"
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
