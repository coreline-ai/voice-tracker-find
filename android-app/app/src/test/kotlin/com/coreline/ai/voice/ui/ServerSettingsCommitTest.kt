package com.coreline.ai.voice.ui

import com.coreline.ai.voice.data.settings.UserSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSettingsCommitTest {
    private val candidate = UserSettings(
        serverUrl = "https://staging.example.com",
        userId = "user1",
        token = "secret",
    )

    @Test
    fun failedAuthenticatedProbeDoesNotCommitCandidate() = runTest {
        var committed: UserSettings? = null

        runCatching {
            validateAndCommitServer(
                candidate = candidate,
                test = true,
                health = { true },
                authenticatedProbe = { error("401") },
                commit = { committed = it },
            )
        }

        assertNull(committed)
    }

    @Test
    fun successfulHealthAndAuthenticatedProbeCommitsCandidate() = runTest {
        var committed: UserSettings? = null

        validateAndCommitServer(
            candidate = candidate,
            test = true,
            health = { true },
            authenticatedProbe = {},
            commit = { committed = it },
        )

        assertEquals(candidate, committed)
    }
}
