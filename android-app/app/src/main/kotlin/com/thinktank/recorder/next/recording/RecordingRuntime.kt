package com.thinktank.recorder.next.recording

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class RecordingRuntime @Inject constructor() {
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()
    private val _commandError = MutableStateFlow<String?>(null)
    val commandError: StateFlow<String?> = _commandError.asStateFlow()

    fun updateAmplitude(raw: Int) {
        _amplitude.value = (raw.coerceAtLeast(0) / 32767f).coerceIn(0f, 1f)
    }

    fun reset() {
        _amplitude.value = 0f
    }

    fun reportCommandError(message: String) {
        _commandError.value = message
    }

    fun clearCommandError() {
        _commandError.value = null
    }
}
