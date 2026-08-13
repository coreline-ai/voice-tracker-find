package com.coreline.ai.voice.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.coreline.ai.voice.data.local.ChunkEntity
import com.coreline.ai.voice.data.local.ChunkState
import com.coreline.ai.voice.data.storage.AppStorageSnapshot
import com.coreline.ai.voice.ui.notes.NotesScreen
import com.coreline.ai.voice.ui.recording.RecordingScreen
import com.coreline.ai.voice.ui.theme.AirVoiceTheme
import org.junit.Rule
import org.junit.Before
import org.junit.Test

class ComposeScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun keepTestActivityVisible() {
        // This physical QA device enters its screen saver quickly. Keeping the test host visible
        // prevents Compose semantics from being detached while an assertion is running.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test
    fun recordingIdleHasOnePrimaryActionAndDiagnostics() {
        compose.setContent {
            AirVoiceTheme {
                RecordingScreen(
                    state = RecordingUiState(),
                    onStart = {},
                    onStop = {},
                    audioInputAvailable = true,
                )
            }
        }

        compose.onNodeWithContentDescription("녹음 시작").assertHasClickAction()
        compose.onNodeWithText("00:00").assertIsDisplayed()
        compose.onNodeWithText("기록 상태").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun notesEmptyStateKeepsSyncActionWithoutImageSemantics() {
        compose.setContent {
            AirVoiceTheme {
                NotesScreen(
                    state = NotesUiState(),
                    onSync = {},
                    onOpen = {},
                    onCreate = { _, _, _, _ -> },
                )
            }
        }

        compose.onNodeWithText("아직 도착한 노트가 없습니다").assertIsDisplayed()
        compose.onNodeWithContentDescription("지금 동기화").assertHasClickAction()
    }

    @Test
    fun recordingLightThemeKeepsPrimaryActionAndStatusVisible() {
        compose.setContent {
            AirVoiceTheme(darkTheme = false) {
                RecordingScreen(
                    state = RecordingUiState(),
                    onStart = {},
                    onStop = {},
                    audioInputAvailable = true,
                )
            }
        }

        compose.onNodeWithContentDescription("녹음 시작").assertHasClickAction()
        compose.onNodeWithText("기록 준비").assertIsDisplayed()
    }

    @Test
    fun recordingShowsStorageAndSafeRetryForFailedUpload() {
        val failedChunk = ChunkEntity(
            id = "failed-upload",
            sessionId = "session",
            uploadId = "upload",
            path = "/managed/failed.m4a",
            state = ChunkState.FAILED,
            createdAt = 1L,
            durationMs = 10_000L,
            lastError = "NETWORK_UNAVAILABLE",
        )
        compose.setContent {
            var retried by remember { mutableStateOf(false) }
            AirVoiceTheme {
                androidx.compose.foundation.layout.Box {
                    RecordingScreen(
                        state = RecordingUiState(
                            syncQueue = listOf(failedChunk),
                            attentionUploads = 1,
                            storage = AppStorageSnapshot(
                                recordingBytes = 4_096L,
                                localAiModelBytes = 0L,
                                localAiAudioBytes = 0L,
                                databaseBytes = 1_024L,
                                availableBytes = 2L * 1024L * 1024L * 1024L,
                                totalBytes = 8L * 1024L * 1024L * 1024L,
                            ),
                        ),
                        onStart = {},
                        onStop = {},
                        onRetryUpload = { retried = it == "failed-upload" },
                        audioInputAvailable = true,
                    )
                    if (retried) androidx.compose.material3.Text("재시도 요청됨")
                }
            }
        }

        compose.onNodeWithText("기기 저장공간").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("업로드 실패").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("원본 확인 후 재시도")
            .performScrollTo()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithText("재시도 요청됨").assertExists()
    }
}
