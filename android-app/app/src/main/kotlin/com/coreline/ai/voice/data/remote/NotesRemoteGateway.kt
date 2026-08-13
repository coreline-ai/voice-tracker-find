package com.coreline.ai.voice.data.remote

import com.coreline.ai.voice.data.settings.UserSettings

/** Note-only portion of the receiver contract used by the offline note repository. */
interface NotesRemoteGateway {
    suspend fun listNotes(settings: UserSettings): List<RemoteNote>

    suspend fun getNote(settings: UserSettings, id: String): RemoteNote

    suspend fun createNote(
        settings: UserSettings,
        folder: String,
        name: String,
        content: String,
    ): RemoteNote

    suspend fun updateNote(
        settings: UserSettings,
        note: RemoteNote,
        content: String,
    ): RemoteNote

    suspend fun archiveNote(settings: UserSettings, note: RemoteNote)
}
