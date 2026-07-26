package com.thinktank.recorder.ondevice.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.thinktank.recorder.ondevice.api.SummaryEngineType
import com.thinktank.recorder.ondevice.api.MainRecordingSource
import com.thinktank.recorder.ondevice.api.SttCaptureProfile
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
        composeRule.onNodeWithText("시스템 온디바이스 STT").assertIsDisplayed()
        composeRule.onNodeWithTag("ondevice-scroll").performScrollToIndex(3)
        composeRule.onNodeWithText("말하기 시작").assertIsDisplayed()
        composeRule.onAllNodesWithText("Moonshine 한국어 STT").assertCountEquals(0)
        composeRule.onNode(hasScrollAction()).performScrollToIndex(6)
        composeRule.onNodeWithText("로컬 모델 관리").assertIsDisplayed()
        composeRule.onNodeWithText("Qwen 로컬 AI 요약").assertIsDisplayed()
        composeRule.onNodeWithText("SenseVoice 한국어 파일 STT").assertIsDisplayed()
    }

    @Test
    fun rendersNativeProcessingStateAndCancelAction() {
        composeRule.setContent {
            MaterialTheme {
                TestScreen(
                    OnDeviceUiState(
                        selectedSummary = SummaryEngineType.QWEN_LOCAL,
                        processing = true,
                        processingLabel = "Qwen 로컬 요약 중",
                        processingProgress = 0.42f,
                    ),
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToIndex(3)
        composeRule.onNodeWithText("Qwen 로컬 요약 중").assertIsDisplayed()
        composeRule.onNodeWithText("처리 취소").assertIsDisplayed()
    }

    @Test
    fun doesNotOfferRetryForLegacyMoonshineAudioSession() {
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
                                sttEngine = "MOONSHINE_LOCAL",
                                summaryEngine = SummaryEngineType.EXTRACTIVE_KOTLIN.name,
                                audioPath = "/data/user/0/app/files/ondevice/recordings/retry.wav",
                                failureStage = OnDeviceFailureStage.TRANSCRIBE.name,
                            ),
                        ),
                    ),
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToIndex(8)
        composeRule.onAllNodesWithText("전사 다시 시도").assertCountEquals(0)
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

    @Test
    fun messageCanBeDismissedWithoutHidingThePrimaryAction() {
        var clearCount = 0
        composeRule.setContent {
            MaterialTheme {
                TestScreen(
                    state = OnDeviceUiState(
                        systemSttAvailable = true,
                        message = "인식된 음성이 없습니다",
                    ),
                    onClearMessage = { clearCount += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("ondevice-scroll").performScrollToIndex(3)
        composeRule.onNodeWithText("말하기 시작").assertIsDisplayed()
        composeRule.onNodeWithTag("ondevice-scroll").performScrollToIndex(5)
        composeRule.onNodeWithContentDescription("알림 닫기").performClick()
        composeRule.runOnIdle { assertEquals(1, clearCount) }
    }

    @Test
    fun showsContinuousTranscriptControlsAndActualSummaryEngine() {
        composeRule.setContent {
            MaterialTheme {
                TestScreen(
                    OnDeviceUiState(
                        listening = true,
                        selectedSttProfile = SttCaptureProfile.BALANCED,
                        liveTranscript = "첫 번째 확정 문장\n두 번째 확정 문장",
                        partialTranscript = "현재 말하고 있는 문장",
                        message = "듣는 중 · 멈추면 자동으로 다음 문장을 준비합니다.",
                        sessions = listOf(
                            OnDeviceSessionEntity(
                                id = "summary-engine",
                                createdAt = 1,
                                updatedAt = 1,
                                state = OnDeviceSessionState.COMPLETE.name,
                                sttEngine = "ANDROID_ON_DEVICE",
                                summaryEngine = SummaryEngineType.QWEN_LOCAL.name,
                                transcript = "완료된 전사",
                                summary = "요약 결과",
                            ),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("연속 전사 기준").assertIsDisplayed()
        composeRule.onNodeWithText("보통 문장 단위").assertIsDisplayed()
        composeRule.onNodeWithTag("ondevice-scroll").performScrollToIndex(3)
        composeRule.onNodeWithText("현재 인식 중").assertIsDisplayed()
        composeRule.onNodeWithText("현재 말하고 있는 문장").assertIsDisplayed()
        composeRule.onNodeWithTag("ondevice-scroll").performScrollToIndex(9)
        composeRule.onNodeWithText("처리 방식 · Qwen 로컬 AI").assertIsDisplayed()
    }

    @Test
    fun opensScrollableFullTranscriptFromSessionPreview() {
        val fullTranscript = List(30) { index -> "전체 전사 문장 ${index + 1}" }.joinToString("\n")
        composeRule.setContent {
            MaterialTheme {
                TestScreen(
                    OnDeviceUiState(
                        sessions = listOf(
                            OnDeviceSessionEntity(
                                id = "full-transcript",
                                createdAt = 1,
                                updatedAt = 1,
                                state = OnDeviceSessionState.COMPLETE.name,
                                sttEngine = "SENSEVOICE_LOCAL_FILE",
                                summaryEngine = SummaryEngineType.QWEN_LOCAL.name,
                                transcript = fullTranscript,
                            ),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("ondevice-scroll").performScrollToIndex(9)
        composeRule.onNodeWithText("전체 전사 보기").performClick()
        composeRule.onNodeWithTag("full-transcript-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("전체 전사 문장 1", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("full-transcript-scroll").assert(hasScrollAction())
    }

    @Test
    fun rendersFirstTabRecordingImportWithSenseVoiceInstallGate() {
        composeRule.setContent {
            MaterialTheme {
                TestScreen(
                    OnDeviceUiState(
                        mainRecordingSources = listOf(
                            MainRecordingSource(
                                id = "chunk-1",
                                createdAt = 1,
                                durationMs = 20_000,
                                sizeBytes = 80_000,
                                sha256 = "a".repeat(64),
                                extension = "m4a",
                                storageState = "READY",
                            ),
                        ),
                        selectedMainRecordingId = "chunk-1",
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("ondevice-scroll").performScrollToIndex(4)
        composeRule.onNodeWithText("1번 탭 녹음 분석").assertIsDisplayed()
        composeRule.onNodeWithText("다른 녹음 선택").assertIsDisplayed()
        composeRule.onNodeWithText("PCM 변환 후 텍스트 추출").assertIsDisplayed()
        composeRule.onNodeWithText("SenseVoice 한국어 파일 STT를 Wi-Fi로 설치", substring = true)
            .assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun TestScreen(
        state: OnDeviceUiState,
        onHostStopped: () -> Unit = {},
        onClearMessage: () -> Unit = {},
    ) {
        OnDeviceScreen(
            state = state,
            mainRecorderActive = false,
            onSelectMainRecording = {},
            onTranscribeSelectedRecording = {},
            onSelectSttProfile = {},
            onSelectSummary = {},
            onStartListening = {},
            onStopListening = {},
            onCancelListening = {},
            onHostStopped = onHostStopped,
            onClearMessage = onClearMessage,
            onSummarize = {},
            onDeleteSession = {},
            onDownloadModel = {},
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
