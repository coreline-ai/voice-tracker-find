package com.thinktank.recorder.next

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.thinktank.recorder.next.ui.NotesViewModel
import com.thinktank.recorder.next.ui.RecordingViewModel
import com.thinktank.recorder.next.ui.SettingsViewModel
import com.thinktank.recorder.next.ui.ThinkTankApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val recordingViewModel by viewModels<RecordingViewModel>()
    private val notesViewModel by viewModels<NotesViewModel>()
    private val settingsViewModel by viewModels<SettingsViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialRoute = intent.destinationRoute()
        setContent {
            ThinkTankApp(
                recordingViewModel = recordingViewModel,
                notesViewModel = notesViewModel,
                settingsViewModel = settingsViewModel,
                initialRoute = initialRoute,
            )
        }
    }

    private fun Intent?.destinationRoute(): String =
        this?.data?.host?.takeIf { it in setOf("recording", "notes", "settings") }
            ?: this?.getStringExtra(EXTRA_DESTINATION)
                ?.takeIf { it in setOf("recording", "notes", "settings") }
            ?: "recording"

    companion object {
        const val EXTRA_DESTINATION = "destination"
    }
}
