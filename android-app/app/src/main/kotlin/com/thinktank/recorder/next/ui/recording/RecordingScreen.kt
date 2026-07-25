package com.thinktank.recorder.next.ui.recording

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.thinktank.recorder.next.R
import com.thinktank.recorder.next.data.local.ChunkEntity
import com.thinktank.recorder.next.data.local.ChunkState
import com.thinktank.recorder.next.data.local.RecordingState
import com.thinktank.recorder.next.ui.RecordingUiState
import com.thinktank.recorder.next.ui.common.RecordControl
import com.thinktank.recorder.next.ui.common.SectionLabel
import com.thinktank.recorder.next.ui.common.SoundThread
import com.thinktank.recorder.next.ui.common.StatusPill
import com.thinktank.recorder.next.ui.common.formatDuration
import com.thinktank.recorder.next.ui.theme.ArchiveInk
import com.thinktank.recorder.next.ui.theme.ArchivePaper
import java.io.File

@Composable
fun RecordingScreen(
    state: RecordingUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    audioInputAvailable: Boolean? = null,
    blockedByLocalAi: Boolean = false,
) {
    val context = LocalContext.current
    val detectedAudioInput = remember(context) {
        hasUsableAudioInput(context.getSystemService(AudioManager::class.java))
    }
    val hasAudioInput = audioInputAvailable ?: detectedAudioInput
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val permissions = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            permissionMessage = null
            onStart()
        } else {
            permissionMessage = "마이크 권한이 있어야 녹음을 시작할 수 있습니다"
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(250.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.hero_recording_chamber),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                ArchiveInk.copy(alpha = 0.15f),
                                ArchiveInk.copy(alpha = 0.2f),
                                ArchiveInk,
                            ),
                        ),
                    ),
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 24.dp, vertical = 22.dp),
            ) {
                StatusPill(
                    text = statusLabel(state),
                    good = state.session?.state == RecordingState.RECORDING,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = formatDuration(state.elapsedMs),
                    style = MaterialTheme.typography.displayLarge,
                    color = ArchivePaper,
                )
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SoundThread(
                amplitude = state.amplitude,
                active = state.session?.state == RecordingState.RECORDING,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            RecordControl(
                active = state.isActive,
                enabled = hasAudioInput &&
                    state.session?.state != RecordingState.FINALIZING &&
                    (state.isActive || !blockedByLocalAi),
                onClick = {
                    if (state.isActive) {
                        onStop()
                    } else if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        onStart()
                    } else {
                        permissionLauncher.launch(permissions)
                    }
                },
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = when {
                    !hasAudioInput -> "사용 가능한 마이크 입력이 없습니다"
                    state.isActive -> "눌러서 기록을 안전하게 마칩니다"
                    blockedByLocalAi -> "로컬 AI 음성 인식이 마이크를 사용 중입니다"
                    else -> "눌러서 기록을 시작합니다"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasAudioInput) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (!hasAudioInput) {
                Text(
                    "현재 기기의 오디오 정책에는 내장 마이크가 없습니다. 지원되는 USB 마이크를 연결한 뒤 다시 실행하세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            permissionMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            state.commandError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Spacer(Modifier.height(42.dp))
            SectionLabel("기기 보관함")
            Text(
                "동기화가 끝나도 녹음 원본은 이 기기에 유지됩니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
            RecentRecordings(state.recentChunks)
            Spacer(Modifier.height(42.dp))
            SectionLabel("기록 상태")
            Spacer(Modifier.height(12.dp))
            DiagnosticRow(
                icon = { Icon(Icons.Outlined.GraphicEq, contentDescription = null) },
                label = "현재 청크",
                value = state.chunk?.path?.substringAfterLast('/') ?: "아직 없음",
            )
            DiagnosticRow(
                icon = { Icon(Icons.Outlined.CloudUpload, contentDescription = null) },
                label = "업로드 대기",
                value = "${state.pendingUploads}개",
            )
            DiagnosticRow(
                icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                label = "마지막 상태",
                value = state.session?.lastError ?: state.session?.state ?: "대기",
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun RecentRecordings(chunks: List<ChunkEntity>) {
    if (chunks.isEmpty()) {
        Text(
            "아직 기기에 보관된 녹음이 없습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose { player?.release() }
    }

    fun stopPlayback() {
        player?.let { activePlayer ->
            runCatching {
                if (activePlayer.isPlaying) activePlayer.stop()
            }
            activePlayer.release()
        }
        player = null
        playingId = null
    }

    chunks.forEach { chunk ->
        val file = File(chunk.path)
        val isStoredOnDevice = file.isFile
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Folder, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(
                    "녹음 · ${formatDuration(chunk.durationMs ?: 0)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    chunkStorageLabel(chunk.state, isStoredOnDevice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                enabled = isStoredOnDevice,
                onClick = {
                    if (playingId == chunk.id) {
                        stopPlayback()
                    } else {
                        stopPlayback()
                        playbackError = null
                        val next = MediaPlayer()
                        runCatching {
                            next.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build(),
                            )
                            next.setDataSource(chunk.path)
                            next.setOnCompletionListener {
                                if (player === next) {
                                    player = null
                                    playingId = null
                                }
                                next.release()
                            }
                            next.prepare()
                            next.start()
                        }.onSuccess {
                            player = next
                            playingId = chunk.id
                        }.onFailure {
                            next.release()
                            playbackError = it.message ?: "녹음을 재생할 수 없습니다"
                        }
                    }
                },
            ) {
                Icon(
                    if (playingId == chunk.id) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    contentDescription = if (playingId == chunk.id) "재생 정지" else "녹음 재생",
                )
            }
        }
    }
    playbackError?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun chunkStorageLabel(state: String, isStoredOnDevice: Boolean): String = when {
    !isStoredOnDevice -> "기기 원본을 찾을 수 없음"
    state == ChunkState.UPLOADED -> "업로드됨 · 기기에 보관됨"
    state in setOf(ChunkState.READY, ChunkState.RETRY) -> "업로드 대기 · 기기에 보관됨"
    state in setOf(ChunkState.CLAIMED, ChunkState.UPLOADING) -> "업로드 중 · 기기에 보관됨"
    state == ChunkState.CONFLICT -> "업로드 확인 필요 · 기기에 보관됨"
    state == ChunkState.FAILED -> "업로드 실패 · 기기에 보관됨"
    state == ChunkState.QUARANTINED -> "확인 필요 · 기기에 보관됨"
    else -> "기기에 보관됨"
}

private fun hasUsableAudioInput(audioManager: AudioManager): Boolean =
    audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).any { device ->
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            device.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX
    }

@Composable
private fun DiagnosticRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

private fun statusLabel(state: RecordingUiState): String = when (state.session?.state) {
    RecordingState.PREPARING -> "준비 중"
    RecordingState.RECORDING -> "기록 중"
    RecordingState.WAITING -> "예약 대기"
    RecordingState.FINALIZING -> "안전하게 마감 중"
    RecordingState.FAILED -> "확인 필요"
    RecordingState.STOPPED -> "기록 완료"
    else -> "기록 준비"
}
