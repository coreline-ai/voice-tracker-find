package com.thinktank.recorder.next.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.thinktank.recorder.next.ui.notes.NotesScreen
import com.thinktank.recorder.next.ui.recording.RecordingScreen
import com.thinktank.recorder.next.ui.theme.ThinkTankTheme
import org.junit.Rule
import org.junit.Test

class ComposeScreensTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun recordingIdleHasOnePrimaryActionAndDiagnostics() {
        compose.setContent {
            ThinkTankTheme {
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
            ThinkTankTheme {
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
            ThinkTankTheme(darkTheme = false) {
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
}
