package com.coreline.ai.voice.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecorderController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun start() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, RecorderService::class.java).setAction(RecorderService.ACTION_START),
        )
    }

    fun stop() {
        context.startService(
            Intent(context, RecorderService::class.java).setAction(RecorderService.ACTION_STOP),
        )
    }
}

