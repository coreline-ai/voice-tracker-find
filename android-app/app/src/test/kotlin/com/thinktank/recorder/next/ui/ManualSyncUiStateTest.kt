package com.thinktank.recorder.next.ui

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSyncUiStateTest {
    @Test
    fun `only a newly enqueued or running manual sync shows active progress`() {
        assertTrue(isManualSyncInProgress(WorkInfo.State.ENQUEUED, runAttemptCount = 0))
        assertTrue(isManualSyncInProgress(WorkInfo.State.RUNNING, runAttemptCount = 1))

        assertFalse(isManualSyncInProgress(WorkInfo.State.ENQUEUED, runAttemptCount = 1))
        assertFalse(isManualSyncInProgress(WorkInfo.State.BLOCKED, runAttemptCount = 0))
        assertFalse(isManualSyncInProgress(WorkInfo.State.FAILED, runAttemptCount = 1))
    }

    @Test
    fun `manual sync explains when receiver settings are absent`() {
        assertEquals(
            "서버 설정이 없습니다. 설정 탭에서 수신기 주소, 사용자 ID, Receiver token을 저장하세요.",
            manualSyncConfigurationMessage(isServerConfigured = false),
        )
        assertNull(manualSyncConfigurationMessage(isServerConfigured = true))
    }
}
