package com.thinktank.recorder.next.ui

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.thinktank.recorder.next.R
import com.thinktank.recorder.next.ui.notes.NoteDetailScreen
import com.thinktank.recorder.next.ui.notes.NotesScreen
import com.thinktank.recorder.next.ui.recording.RecordingScreen
import com.thinktank.recorder.next.ui.settings.SettingsScreen
import com.thinktank.recorder.next.ui.theme.ArchiveInk
import com.thinktank.recorder.next.ui.theme.ThinkTankTheme
import com.thinktank.recorder.ondevice.ui.OnDeviceScreen
import com.thinktank.recorder.ondevice.ui.OnDeviceViewModel

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("recording", "녹음", Icons.Default.Mic),
    Tab("notes", "노트", Icons.Default.Description),
    Tab("settings", "설정", Icons.Default.Settings),
    Tab("ondevice", "로컬 AI", Icons.Default.Memory),
)

@Composable
fun ThinkTankApp(
    recordingViewModel: RecordingViewModel,
    notesViewModel: NotesViewModel,
    settingsViewModel: SettingsViewModel,
    onDeviceViewModel: OnDeviceViewModel,
    initialRoute: String = "recording",
) {
    val recording by recordingViewModel.uiState.collectAsStateWithLifecycle()
    val notes by notesViewModel.uiState.collectAsStateWithLifecycle()
    val settings by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val onDevice by onDeviceViewModel.uiState.collectAsStateWithLifecycle()
    val darkTheme = isSystemInDarkTheme()

    ThinkTankTheme(darkTheme = darkTheme) {
        if (!settings.settings.onboardingComplete) {
            SystemBarAppearance(darkBackground = true)
            OnboardingScreen(onComplete = settingsViewModel::completeOnboarding)
            return@ThinkTankTheme
        }
        SystemBarAppearance(darkBackground = darkTheme)

        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        val destination = backStack?.destination
        val showBottomBar = tabs.any { tab ->
            destination?.hierarchy?.any { it.route == tab.route } == true
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar(
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                    ) {
                        tabs.forEach { tab ->
                            val selected = destination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo("recording") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = initialRoute.takeIf { route ->
                    tabs.any { it.route == route }
                } ?: "recording",
                modifier = Modifier.padding(padding),
            ) {
                composable("recording") {
                    RecordingScreen(
                        state = recording,
                        onStart = recordingViewModel::start,
                        onStop = recordingViewModel::stop,
                        blockedByLocalAi = onDevice.micBusy,
                    )
                }
                composable("notes") {
                    NotesScreen(
                        state = notes,
                        onSync = notesViewModel::sync,
                        onOpen = { navController.navigate("note/$it") },
                        onCreate = notesViewModel::create,
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        state = settings,
                        onSaveServer = settingsViewModel::saveServer,
                        onChunkMinutes = settingsViewModel::updateChunkMinutes,
                        onSchedule = settingsViewModel::updateSchedule,
                        onAutoSync = settingsViewModel::updateAutoSync,
                        onCheckUpdate = settingsViewModel::checkVersion,
                    )
                }
                composable("ondevice") {
                    OnDeviceScreen(
                        state = onDevice,
                        mainRecorderActive = recording.isActive,
                        onSelectStt = onDeviceViewModel::selectStt,
                        onSelectSummary = onDeviceViewModel::selectSummary,
                        onStartListening = onDeviceViewModel::startListening,
                        onStopListening = onDeviceViewModel::stopListening,
                        onCancelListening = onDeviceViewModel::cancelListening,
                        onHostStopped = onDeviceViewModel::onHostStopped,
                        onSummarize = onDeviceViewModel::summarize,
                        onRetryTranscription = onDeviceViewModel::retryTranscription,
                        onDeleteSession = onDeviceViewModel::deleteSession,
                        onDownloadModel = onDeviceViewModel::downloadModel,
                        onImportModel = onDeviceViewModel::importModel,
                        onPauseModel = onDeviceViewModel::pauseModel,
                        onDeleteModel = onDeviceViewModel::deleteModel,
                        heroImageRes = R.drawable.hero_recording_chamber,
                    )
                }
                composable("note/{noteId}") { entry ->
                    val id = entry.arguments?.getString("noteId")
                    val note = notes.notes.firstOrNull { it.serverId == id }
                    NoteDetailScreen(
                        note = note,
                        allNotes = notes.notes,
                        onBack = navController::popBackStack,
                        onOpen = { navController.navigate("note/$it") },
                        onSave = notesViewModel::save,
                        onArchive = notesViewModel::archive,
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingScreen(onComplete: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val image = if (page == 0) R.drawable.onboarding_record else R.drawable.onboarding_archive
    val title = if (page == 0) "말을 놓치지 않는 기록" else "기록이 노트로 돌아옵니다"
    val body = if (page == 0) {
        "사용자가 시작한 녹음은 화면이 잠겨도 이어지고, 완료된 파일만 안전하게 보관합니다."
    } else {
        "Wi-Fi에서 서버로 전송한 뒤 정리된 노트를 읽고 편집할 수 있습니다."
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(ArchiveInk)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Image(
            painter = painterResource(image),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(
                            ArchiveInk.copy(alpha = 0.08f),
                            ArchiveInk.copy(alpha = 0.3f),
                            ArchiveInk,
                        ),
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Text(
                "${page + 1} / 2",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                color = com.thinktank.recorder.next.ui.theme.ArchivePaper,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = com.thinktank.recorder.next.ui.theme.ArchiveFog,
            )
            Spacer(Modifier.height(26.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = {
                        if (page == 0) page = 1 else onComplete()
                    },
                ) {
                    Text(if (page == 0) "다음" else "시작하기")
                }
            }
        }
    }
}

@Composable
private fun SystemBarAppearance(darkBackground: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkBackground
            isAppearanceLightNavigationBars = !darkBackground
        }
    }
}
