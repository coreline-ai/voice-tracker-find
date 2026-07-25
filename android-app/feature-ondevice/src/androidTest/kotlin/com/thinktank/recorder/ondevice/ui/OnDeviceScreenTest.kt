package com.thinktank.recorder.ondevice.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import com.thinktank.recorder.ondevice.api.SttEngineType
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import com.thinktank.recorder.ondevice.api.OnDeviceFailureStage
import com.thinktank.recorder.ondevice.api.OnDeviceSessionState
import com.thinktank.recorder.ondevice.data.OnDeviceSessionEntity
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnDeviceScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersLocalOnlyChoicesAndModelManager() {
        composeRule.setContent {
            MaterialTheme {
                TestScreen(
                    OnDeviceUiState(
                        selectedStt = SttEngineType.ANDROID_ON_DEVICE,
                        selectedSummary = SummaryEngineType.EXTRACTIVE_KOTLIN,
                        systemSttAvailable = true,
                        models = ModelCatalog.models.map {
                            ModelUiState(descriptor = it, status = ModelUiStatus.NOT_INSTALLED)
                        },
                    ),
                )
            }
        }

        composeRule.onNodeWithText("LOCAL AI").assertIsDisplayed()
        composeRule.onNodeWithText("이 기기에서만 처리").assertIsDisplayed()
        composeRule.onNodeWithText("Moonshine 한국어 STT").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToIndex(5)
        composeRule.onNodeWithText("AI 모델 관리").assertIsDisplayed()
        composeRule.onNodeWithText("Qwen 로컬 AI 요약").assertIsDisplayed()
    }

    @Test
    fun rendersNativeProcessingStateAndCancelAction() {
        composeRule.setContent {
            MaterialTheme {
                TestScreen(
                    OnDeviceUiState(
                        selectedStt = SttEngineType.MOONSHINE_LOCAL,
                        selectedSummary = SummaryEngineType.QWEN_LOCAL,
                        processing = true,
                        processingLabel = "Moonshine 한국어 전사 중",
                        processingProgress = 0.42f,
                    ),
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToIndex(4)
        composeRule.onNodeWithText("Moonshine 한국어 전사 중").assertIsDisplayed()
        composeRule.onNodeWithText("처리 취소").assertIsDisplayed()
    }

    @Test
    fun rendersRetryForRecoverableAudioOnlySession() {
        composeRule.setContent {
            MaterialTheme {
                TestScreen(
                    OnDeviceUiState(
                        sessions = listOf(
                            OnDeviceSessionEntity(
                                id = "retry",
                                createdAt = 1,
                                updatedAt = 1,
                                state = OnDeviceSessionState.FAILED_RECOVERABLE.name,
                                sttEngine = SttEngineType.MOONSHINE_LOCAL.name,
                                summaryEngine = SummaryEngineType.EXTRACTIVE_KOTLIN.name,
                                audioPath = "/data/user/0/app/files/ondevice/recordings/retry.wav",
                                failureStage = OnDeviceFailureStage.TRANSCRIBE.name,
                            ),
                        ),
                    ),
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToIndex(7)
        composeRule.onNodeWithText("전사 다시 시도").assertIsDisplayed()
    }

    @Test
    fun hostStopIsForwardedEvenBeforeUiActiveFlagsChange() {
        val owner = TestLifecycleOwner()
        var hostStopped = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme {
                    TestScreen(
                        state = OnDeviceUiState(listening = false, processing = false),
                        onHostStopped = { hostStopped += 1 },
                    )
                }
            }
        }

        composeRule.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        composeRule.runOnIdle { assertEquals(1, hostStopped) }
    }

    @androidx.compose.runtime.Composable
    private fun TestScreen(
        state: OnDeviceUiState,
        onHostStopped: () -> Unit = {},
    ) {
        OnDeviceScreen(
            state = state,
            mainRecorderActive = false,
            onSelectStt = {},
            onSelectSummary = {},
            onStartListening = {},
            onStopListening = {},
            onCancelListening = {},
            onHostStopped = onHostStopped,
            onSummarize = {},
            onRetryTranscription = {},
            onDeleteSession = {},
            onDownloadModel = { _, _ -> },
            onImportModel = { _, _ -> },
            onPauseModel = {},
            onDeleteModel = {},
        )
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }
}
