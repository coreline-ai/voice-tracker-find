package com.thinktank.recorder.ondevice.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.thinktank.recorder.ondevice.api.MainRecordingSource
import com.thinktank.recorder.ondevice.api.OnDeviceSessionState
import com.thinktank.recorder.ondevice.api.SttEngineType
import com.thinktank.recorder.ondevice.data.OnDeviceSessionEntity
import com.thinktank.recorder.ondevice.modelpack.ModelCatalog
import com.thinktank.recorder.ondevice.modelpack.ModelDeleteScope
import com.thinktank.recorder.ondevice.modelpack.ModelId
import org.junit.Rule
import org.junit.Test

class OnDeviceScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersSttOnlyChoicesAndModelManager() {
        render(
            OnDeviceUiState(
                systemSttAvailable = true,
                nativeAiAvailable = true,
                models = ModelCatalog.models.map {
                    ModelUiState(descriptor = it, status = ModelUiStatus.NOT_INSTALLED)
                },
            ),
        )

        composeRule.onNodeWithText("음성 인식").assertIsDisplayed()
        composeRule.onNodeWithTag("ondevice-scroll").performScrollToIndex(5)
        composeRule.onNodeWithText("로컬 모델 관리").assertIsDisplayed()
        composeRule.onNodeWithText("Gemma 3 1B").assertIsDisplayed()
        composeRule.onNodeWithText("기본 요약 모델").assertIsDisplayed()
        composeRule.onNodeWithText("SenseVoice 한국어 파일 STT").assertIsDisplayed()
        composeRule.onAllNodesWithText("요약 방식").assertCountEquals(0)
        composeRule.onAllNodesWithText("Qwen 로컬 AI 요약").assertCountEquals(0)
        composeRule.onAllNodesWithText("3개 모델 비교").assertCountEquals(0)
    }

    @Test
    fun legacySummaryFieldsAreNotRendered() {
        val transcript = "사용자가 확인해야 하는 전사 원문입니다."
        render(
            OnDeviceUiState(
                sessions = listOf(
                    OnDeviceSessionEntity(
                        id = "legacy",
                        createdAt = 1,
                        updatedAt = 1,
                        state = OnDeviceSessionState.COMPLETE.name,
                        sttEngine = SttEngineType.SENSEVOICE_LOCAL_FILE.name,
                        summaryEngine = "QWEN_LOCAL",
                        transcript = transcript,
                        title = "이전 요약 제목",
                        summary = "이전 요약 본문",
                        actionItems = "이전 할 일",
                    ),
                ),
            ),
        )

        composeRule.onNodeWithTag("ondevice-scroll").performScrollToIndex(7)
        composeRule.onNodeWithText("로컬 전사 기록").assertIsDisplayed()
        composeRule.onNodeWithText("전사 원문 · ${transcript.length}자").assertIsDisplayed()
        composeRule.onAllNodesWithText("이전 요약 제목").assertCountEquals(0)
        composeRule.onAllNodesWithText("이전 요약 본문").assertCountEquals(0)
        composeRule.onAllNodesWithText("이전 할 일").assertCountEquals(0)
    }

    @Test
    fun fullTranscriptOpensFromSessionCard() {
        val transcript = (1..80).joinToString(" ") { "전사$it" }
        render(
            OnDeviceUiState(
                sessions = listOf(
                    OnDeviceSessionEntity(
                        id = "transcript",
                        createdAt = 1,
                        updatedAt = 1,
                        state = OnDeviceSessionState.COMPLETE.name,
                        sttEngine = SttEngineType.SENSEVOICE_LOCAL_FILE.name,
                        summaryEngine = "NONE",
                        transcript = transcript,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithTag("ondevice-scroll").performScrollToIndex(7)
        composeRule.onNodeWithText("전체 전사 보기").performClick()
        composeRule.onNodeWithTag("full-transcript-sheet").assertIsDisplayed()
        composeRule.onNodeWithText("전체 전사").assertIsDisplayed()
        composeRule.onNodeWithText(transcript).assertIsDisplayed()
    }

    @Test
    fun modelDeleteRequiresExplicitScope() {
        var deleteScope: ModelDeleteScope? = null
        val model = ModelCatalog.get(ModelId.SENSEVOICE_STT_KO)
        render(
            state = OnDeviceUiState(
                nativeAiAvailable = true,
                models = listOf(
                    ModelUiState(
                        descriptor = model,
                        status = ModelUiStatus.READY,
                        installedBytes = 100,
                        retainedArtifactBytes = 100,
                    ),
                ),
            ),
            onDeleteModel = { _, scope -> deleteScope = scope },
        )

        composeRule.onNodeWithTag("ondevice-scroll").performScrollToIndex(5)
        composeRule.onNodeWithText("삭제").performClick()
        composeRule.onNodeWithText("적용본만 삭제").performClick()
        composeRule.runOnIdle {
            check(deleteScope == ModelDeleteScope.INSTALLED_ONLY)
        }
    }

    @Test
    fun rendersFirstTabRecordingImportWithSenseVoiceInstallGate() {
        render(
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

        composeRule.onNodeWithTag("ondevice-scroll").performScrollToIndex(4)
        composeRule.onNodeWithText("1번 탭 녹음 분석").assertIsDisplayed()
        composeRule.onNodeWithText("다른 녹음 선택").assertIsDisplayed()
        composeRule.onNodeWithText("PCM 변환 후 텍스트 추출").assertIsDisplayed()
        composeRule.onNodeWithText(
            "SenseVoice 한국어 파일 STT를 Wi-Fi로 설치",
            substring = true,
        ).assertIsDisplayed()
    }

    private fun render(
        state: OnDeviceUiState,
        onDeleteModel: (ModelId, ModelDeleteScope) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            MaterialTheme {
                OnDeviceScreen(
                    state = state,
                    mainRecorderActive = false,
                    onSelectMainRecording = {},
                    onTranscribeSelectedRecording = {},
                    onSelectSttProfile = {},
                    onStartListening = {},
                    onStopListening = {},
                    onCancelListening = {},
                    onHostStopped = {},
                    onClearMessage = {},
                    onSummarize = {},
                    onDeleteSession = {},
                    onDownloadModel = {},
                    onImportModel = { _, _ -> },
                    onPauseModel = {},
                    onRestoreModel = {},
                    onDeleteModel = onDeleteModel,
                )
            }
        }
    }
}
