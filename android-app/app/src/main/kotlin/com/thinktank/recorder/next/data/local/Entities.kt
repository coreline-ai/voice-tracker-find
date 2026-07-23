package com.thinktank.recorder.next.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object RecordingState {
    const val IDLE = "IDLE"
    const val PREPARING = "PREPARING"
    const val WAITING = "WAITING"
    const val RECORDING = "RECORDING"
    const val FINALIZING = "FINALIZING"
    const val STOPPED = "STOPPED"
    const val FAILED = "FAILED"
}

object ChunkState {
    const val RECORDING = "RECORDING"
    const val FINALIZING = "FINALIZING"
    const val READY = "READY"
    const val CLAIMED = "CLAIMED"
    const val UPLOADING = "UPLOADING"
    const val UPLOADED = "UPLOADED"
    const val RETRY = "RETRY"
    const val CONFLICT = "CONFLICT"
    const val QUARANTINED = "QUARANTINED"
    const val FAILED = "FAILED"
}

object NoteSyncState {
    const val SYNCED = "SYNCED"
    const val DIRTY = "DIRTY"
    const val SAVING = "SAVING"
    const val CONFLICT = "CONFLICT"
    const val PENDING_DELETE = "PENDING_DELETE"
    const val FAILED = "FAILED"
}

@Entity(tableName = "recording_sessions")
data class RecordingSessionEntity(
    @PrimaryKey val id: String,
    val state: String,
    val startedAt: Long,
    val stoppedAt: Long? = null,
    val currentChunkId: String? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index(value = ["uploadId"], unique = true),
        Index(value = ["path"], unique = true),
        Index(value = ["state", "createdAt"]),
    ],
)
data class ChunkEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val uploadId: String,
    val path: String,
    val state: String,
    val createdAt: Long,
    val finalizedAt: Long? = null,
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
    val sha256: String? = null,
    val claimOwner: String? = null,
    val claimedAt: Long? = null,
    val leaseExpiresAt: Long? = null,
    val attempts: Int = 0,
    val nextRetryAt: Long? = null,
    val serverHash: String? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "upload_attempts",
    foreignKeys = [
        ForeignKey(
            entity = ChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chunkId")],
)
data class UploadAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chunkId: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val outcome: String,
    val errorCode: String? = null,
    val requestId: String? = null,
)

@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["serverId"], unique = true),
        Index(value = ["folder", "name"], unique = true),
    ],
)
data class NoteEntity(
    @PrimaryKey val serverId: String,
    val folder: String,
    val name: String,
    val content: String,
    val revision: String,
    val updatedAt: String,
    val syncState: String = NoteSyncState.SYNCED,
    val baseContent: String? = null,
    val lastError: String? = null,
)

@Entity(
    tableName = "note_conflicts",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["serverId"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("noteId")],
)
data class NoteConflictEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: String,
    val localContent: String,
    val serverContent: String,
    val serverRevision: String,
    val createdAt: Long,
)

@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey val serverProfileId: String,
    val lastNotesSyncAt: Long? = null,
    val lastUploadAt: Long? = null,
    val lastError: String? = null,
)

