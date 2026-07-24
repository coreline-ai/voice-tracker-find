package com.thinktank.recorder.next.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.thinktank.recorder.next.BuildConfig
import com.thinktank.recorder.next.R
import com.thinktank.recorder.next.ui.SettingsUiState
import com.thinktank.recorder.next.ui.common.SectionLabel

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onSaveServer: (String, String, String, Boolean) -> Unit,
    onChunkMinutes: (Int) -> Unit,
    onSchedule: (Boolean, Int, Int) -> Unit,
    onAutoSync: (Boolean, Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var serverUrl by remember { mutableStateOf(state.settings.serverUrl) }
    var userId by remember { mutableStateOf(state.settings.userId) }
    var token by remember { mutableStateOf(state.settings.token) }
    var chunk by remember { mutableFloatStateOf(state.settings.chunkMinutes.toFloat()) }
    var scheduleEnabled by remember { mutableStateOf(state.settings.scheduleEnabled) }
    var startText by remember { mutableStateOf(formatTime(state.settings.scheduleStartMinutes)) }
    var endText by remember { mutableStateOf(formatTime(state.settings.scheduleEndMinutes)) }

    LaunchedEffect(state.settings.serverUrl, state.settings.userId) {
        serverUrl = state.settings.serverUrl
        userId = state.settings.userId
        if (token.isBlank()) token = state.settings.token
    }
    LaunchedEffect(state.settings.chunkMinutes) {
        chunk = state.settings.chunkMinutes.toFloat()
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            "설정",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            "기록은 기기에, 연결 정보는 안전하게 보관합니다",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))
        SectionLabel("녹음")
        SettingHeader("청크 길이", "${chunk.toInt()}분")
        Slider(
            value = chunk,
            onValueChange = { chunk = ((it / 5).toInt() * 5).coerceIn(5, 120).toFloat() },
            onValueChangeFinished = { onChunkMinutes(chunk.toInt()) },
            valueRange = 5f..120f,
            steps = 22,
        )
        Text(
            "완료된 청크만 업로드합니다. 기본값은 20분입니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))
        SectionLabel("시간 창")
        SwitchRow(
            title = "실행 중 예약 적용",
            body = "앱에서 시작한 녹음 서비스가 살아 있을 때만 적용됩니다.",
            checked = scheduleEnabled,
            onChecked = {
                scheduleEnabled = it
                onSchedule(
                    it,
                    parseTime(startText) ?: state.settings.scheduleStartMinutes,
                    parseTime(endText) ?: state.settings.scheduleEndMinutes,
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = startText,
                onValueChange = { startText = it.take(5) },
                label = { Text("시작 HH:mm") },
                enabled = scheduleEnabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = endText,
                onValueChange = { endText = it.take(5) },
                label = { Text("종료 HH:mm") },
                enabled = scheduleEnabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedButton(
            onClick = {
                val start = parseTime(startText)
                val end = parseTime(endText)
                if (start != null && end != null) onSchedule(scheduleEnabled, start, end)
            },
            enabled = parseTime(startText) != null && parseTime(endText) != null,
            modifier = Modifier.padding(top = 10.dp),
        ) { Text("시간 저장") }

        Spacer(Modifier.height(28.dp))
        SectionLabel("동기화")
        SwitchRow(
            title = "자동 동기화",
            body = "조건이 충족되면 시스템이 주기적으로 실행합니다. 정확한 시각은 보장하지 않습니다.",
            checked = state.settings.autoSync,
            onChecked = { onAutoSync(it, state.settings.wifiOnly) },
        )
        SwitchRow(
            title = "Wi-Fi 우선",
            body = "자동 오디오 업로드는 요금제 네트워크에서 대기합니다.",
            checked = state.settings.wifiOnly,
            enabled = state.settings.autoSync,
            onChecked = { onAutoSync(state.settings.autoSync, it) },
        )

        Spacer(Modifier.height(28.dp))
        SectionLabel("서버")
        if (serverUrl.isBlank()) {
            Image(
                painter = painterResource(R.drawable.empty_sync_bridge),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text(if (BuildConfig.DEBUG) "서버 주소" else "HTTPS 서버 주소") },
                placeholder = { Text("https://recorder.example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = userId,
                onValueChange = { userId = it },
                label = { Text("사용자 ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Bearer token") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onSaveServer(serverUrl, userId, token, false) },
                    enabled = !state.testing,
                ) { Text("저장") }
                Button(
                    onClick = { onSaveServer(serverUrl, userId, token, true) },
                    enabled = !state.testing,
                ) { Text(if (state.testing) "확인 중" else "연결 확인") }
            }
            state.connectionMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if ("연결되었습니다" in it) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if ("실패" in it || "확인" in it || "오류" in it) {
                    Image(
                        painter = painterResource(R.drawable.error_server_offline),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("앱 정보")
        SettingHeader("버전", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        state.apkInfo?.let { remote ->
            val updateAvailable = remote.versionCode > BuildConfig.VERSION_CODE
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (updateAvailable) "새 버전 ${remote.versionName} 사용 가능" else "현재 최신 버전입니다",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (updateAvailable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                )
                Text(
                    "서버 versionCode ${remote.versionCode} · ${formatBytes(remote.size)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (remote.releaseNotes.isNotBlank()) {
                    Text(remote.releaseNotes, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "앱은 APK를 자동 다운로드하거나 설치하지 않습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(
            onClick = onCheckUpdate,
            enabled = !state.checkingVersion,
            modifier = Modifier.padding(top = 10.dp),
        ) {
            Text(if (state.checkingVersion) "버전 확인 중" else "서버 버전 확인")
        }
        Text(
            "부팅 후 자동 마이크 시작, exact alarm watchdog, 전체 저장소 접근은 사용하지 않습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun SettingHeader(title: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChecked)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

private fun formatTime(minutes: Int): String =
    "%02d:%02d".format(minutes / 60, minutes % 60)

private fun parseTime(value: String): Int? {
    val parts = value.split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}
