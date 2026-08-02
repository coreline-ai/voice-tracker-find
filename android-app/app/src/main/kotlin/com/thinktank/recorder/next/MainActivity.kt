package com.thinktank.recorder.next

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.thinktank.recorder.next.ui.NotesViewModel
import com.thinktank.recorder.next.ui.CloudAccountsViewModel
import com.thinktank.recorder.next.ui.RecordingViewModel
import com.thinktank.recorder.next.ui.SettingsViewModel
import com.thinktank.recorder.next.ui.ThinkTankApp
import com.thinktank.recorder.ondevice.ui.OnDeviceViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val recordingViewModel by viewModels<RecordingViewModel>()
    private val notesViewModel by viewModels<NotesViewModel>()
    private val settingsViewModel by viewModels<SettingsViewModel>()
    private val cloudAccountsViewModel by viewModels<CloudAccountsViewModel>()
    private val onDeviceViewModel by viewModels<OnDeviceViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialRoute = intent.destinationRoute()
        setContent {
            ThinkTankApp(
                recordingViewModel = recordingViewModel,
                notesViewModel = notesViewModel,
                settingsViewModel = settingsViewModel,
                cloudAccountsViewModel = cloudAccountsViewModel,
                onDeviceViewModel = onDeviceViewModel,
                initialRoute = initialRoute,
            )
        }
    }

    private fun Intent?.destinationRoute(): String =
        this?.data?.host?.takeIf { it in setOf("recording", "notes", "settings", "ondevice") }
            ?: this?.getStringExtra(EXTRA_DESTINATION)
                ?.takeIf { it in setOf("recording", "notes", "settings", "ondevice") }
            ?: "recording"

    companion object {
        const val EXTRA_DESTINATION = "destination"
    }
}
