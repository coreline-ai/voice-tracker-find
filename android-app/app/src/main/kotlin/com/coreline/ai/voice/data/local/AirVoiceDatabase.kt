package com.coreline.ai.voice.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        RecordingSessionEntity::class,
        ChunkEntity::class,
        UploadAttemptEntity::class,
        NoteEntity::class,
        NoteConflictEntity::class,
        SyncCursorEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AirVoiceDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun notesDao(): NotesDao
    abstract fun syncDao(): SyncDao
}

