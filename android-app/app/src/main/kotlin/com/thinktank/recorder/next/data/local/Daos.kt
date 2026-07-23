package com.thinktank.recorder.next.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RecordingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSession(session: RecordingSessionEntity)

    @Query("SELECT * FROM recording_sessions ORDER BY startedAt DESC LIMIT 1")
    abstract fun observeLatestSession(): Flow<RecordingSessionEntity?>

    @Query("SELECT * FROM recording_sessions WHERE id = :id")
    abstract suspend fun session(id: String): RecordingSessionEntity?

    @Query(
        """
        UPDATE recording_sessions
        SET state = :state, currentChunkId = :chunkId, stoppedAt = :stoppedAt,
            lastError = :error
        WHERE id = :sessionId
        """,
    )
    abstract suspend fun setSessionState(
        sessionId: String,
        state: String,
        chunkId: String? = null,
        stoppedAt: Long? = null,
        error: String? = null,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertChunk(chunk: ChunkEntity)

    @Update
    abstract suspend fun updateChunk(chunk: ChunkEntity)

    @Query("SELECT * FROM chunks WHERE id = :id")
    abstract suspend fun chunk(id: String): ChunkEntity?

    @Query("SELECT * FROM chunks ORDER BY createdAt DESC LIMIT 1")
    abstract fun observeLatestChunk(): Flow<ChunkEntity?>

    @Query("SELECT COUNT(*) FROM chunks WHERE state IN ('READY','RETRY','CLAIMED','UPLOADING')")
    abstract fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM chunks WHERE sessionId = :sessionId")
    abstract fun observeSessionChunkCount(sessionId: String): Flow<Int>

    @Query(
        """
        UPDATE chunks
        SET state = 'READY', claimOwner = NULL, claimedAt = NULL, leaseExpiresAt = NULL
        WHERE state IN ('CLAIMED','UPLOADING') AND leaseExpiresAt < :now
        """,
    )
    abstract suspend fun releaseExpiredClaims(now: Long): Int

    @Query(
        """
        SELECT * FROM chunks
        WHERE state IN ('READY','RETRY')
          AND (nextRetryAt IS NULL OR nextRetryAt <= :now)
        ORDER BY createdAt ASC
        LIMIT 1
        """,
    )
    abstract suspend fun nextReady(now: Long): ChunkEntity?

    @Query(
        """
        UPDATE chunks
        SET state = 'CLAIMED', claimOwner = :owner, claimedAt = :now,
            leaseExpiresAt = :leaseUntil
        WHERE id = :id AND state IN ('READY','RETRY')
        """,
    )
    abstract suspend fun compareAndClaim(
        id: String,
        owner: String,
        now: Long,
        leaseUntil: Long,
    ): Int

    @Transaction
    open suspend fun claimNextReady(
        owner: String,
        now: Long,
        leaseUntil: Long,
    ): ChunkEntity? {
        releaseExpiredClaims(now)
        repeat(8) {
            val candidate = nextReady(now) ?: return null
            if (compareAndClaim(candidate.id, owner, now, leaseUntil) == 1) {
                return chunk(candidate.id)
            }
        }
        return null
    }

    @Query(
        """
        UPDATE chunks
        SET state = :state, claimOwner = NULL, claimedAt = NULL, leaseExpiresAt = NULL,
            attempts = :attempts, nextRetryAt = :nextRetryAt, serverHash = :serverHash,
            lastError = :error
        WHERE id = :id AND claimOwner = :owner
        """,
    )
    abstract suspend fun finishClaim(
        id: String,
        owner: String,
        state: String,
        attempts: Int,
        nextRetryAt: Long? = null,
        serverHash: String? = null,
        error: String? = null,
    ): Int

    @Query(
        """
        UPDATE chunks
        SET state = 'UPLOADING'
        WHERE id = :id AND claimOwner = :owner AND state = 'CLAIMED'
        """,
    )
    abstract suspend fun markUploading(id: String, owner: String): Int

    @Insert
    abstract suspend fun insertAttempt(attempt: UploadAttemptEntity): Long

    @Query(
        """
        UPDATE upload_attempts
        SET finishedAt = :finishedAt, outcome = :outcome,
            errorCode = :errorCode, requestId = :requestId
        WHERE id = :id
        """,
    )
    abstract suspend fun finishAttempt(
        id: Long,
        finishedAt: Long,
        outcome: String,
        errorCode: String? = null,
        requestId: String? = null,
    ): Int

    @Query("SELECT * FROM upload_attempts WHERE id = :id")
    abstract suspend fun attempt(id: Long): UploadAttemptEntity?

    @Query(
        """
        SELECT * FROM chunks
        WHERE state IN ('RECORDING','FINALIZING')
        ORDER BY createdAt ASC
        """,
    )
    abstract suspend fun unfinishedChunks(): List<ChunkEntity>
}

@Dao
abstract class NotesDao {
    @Query("SELECT * FROM notes ORDER BY folder, updatedAt DESC, name")
    abstract fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE serverId = :id")
    abstract fun observe(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE serverId = :id")
    abstract suspend fun get(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE syncState != 'SYNCED'")
    abstract suspend fun localChanges(): List<NoteEntity>

    /**
     * Do not use SQLite REPLACE for notes. REPLACE deletes the old parent row,
     * which cascades and silently removes its persisted NoteConflict rows.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(note: NoteEntity)

    @Update
    protected abstract suspend fun update(note: NoteEntity): Int

    @Transaction
    open suspend fun upsert(note: NoteEntity) {
        if (update(note) == 0) insert(note)
    }

    @Transaction
    open suspend fun upsertAll(notes: List<NoteEntity>) {
        notes.forEach { note -> upsert(note) }
    }

    @Query("DELETE FROM notes WHERE serverId = :id")
    abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM notes WHERE syncState = 'SYNCED' AND serverId NOT IN (:ids)")
    abstract suspend fun deleteSyncedMissing(ids: List<String>)

    @Query("DELETE FROM notes WHERE syncState = 'SYNCED'")
    abstract suspend fun deleteAllSynced()

    @Insert
    abstract suspend fun insertConflict(conflict: NoteConflictEntity)

    @Query("SELECT * FROM note_conflicts WHERE noteId = :noteId ORDER BY createdAt DESC")
    abstract fun observeConflicts(noteId: String): Flow<List<NoteConflictEntity>>
}

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: SyncCursorEntity)

    @Query("SELECT * FROM sync_cursor WHERE serverProfileId = :profile")
    fun observe(profile: String = "default"): Flow<SyncCursorEntity?>
}
