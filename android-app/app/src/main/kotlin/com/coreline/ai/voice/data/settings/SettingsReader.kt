package com.coreline.ai.voice.data.settings

/** Narrow settings dependency used by repositories so sync behavior can be tested without Android storage. */
interface SettingsReader {
    suspend fun current(): UserSettings
}
