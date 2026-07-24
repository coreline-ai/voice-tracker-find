package com.thinktank.recorder.next.data.local

import android.content.Context
import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class RecordingDaoTest {
    private lateinit var database: ThinkTankDatabase
    private lateinit var dao: RecordingDao
    private lateinit var notesDao: NotesDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ThinkTankDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.recordingDao()
        notesDao = database.notesDao()
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun concurrentClaimHasExactlyOneOwner() = runBlocking {
        val session = RecordingSessionEntity(
            id = "session",
            state = RecordingState.STOPPED,
            startedAt = 1,
        )
        dao.upsertSession(session)
        dao.insertChunk(
            ChunkEntity(
                id = "chunk",
                sessionId = session.id,
                uploadId = UUID.randomUUID().toString(),
                path = "/tmp/chunk.m4a",
                state = ChunkState.READY,
                createdAt = 2,
                sha256 = "a".repeat(64),
            ),
        )

        val now = System.currentTimeMillis()
        val claims = listOf("worker-a", "worker-b").map { owner ->
            async { dao.claimNextReady(owner, now, now + 60_000) }
        }.awaitAll()

        assertEquals(1, claims.count { it != null })
        assertEquals("chunk", claims.filterNotNull().single().id)
    }

    @Test
    fun expiredLeaseReturnsChunkToReadyQueue() = runBlocking {
        dao.upsertSession(
            RecordingSessionEntity("session", RecordingState.STOPPED, startedAt = 1),
        )
        dao.insertChunk(
            ChunkEntity(
                id = "chunk",
                sessionId = "session",
                uploadId = "upload",
                path = "/tmp/chunk.m4a",
                state = ChunkState.READY,
                createdAt = 2,
                sha256 = "b".repeat(64),
            ),
        )
        val first = dao.claimNextReady("dead-worker", 100, 200)
        assertNotNull(first)
        assertNull(dao.claimNextReady("early-worker", 150, 300))
        val reclaimed = dao.claimNextReady("new-worker", 201, 400)
        assertEquals("chunk", reclaimed?.id)
        assertEquals("new-worker", reclaimed?.claimOwner)
    }

    @Test
    fun uploadAttemptIsClosedWithOutcomeAndRequestId() = runBlocking {
        dao.upsertSession(
            RecordingSessionEntity("session", RecordingState.STOPPED, startedAt = 1),
        )
        dao.insertChunk(
            ChunkEntity(
                id = "chunk",
                sessionId = "session",
                uploadId = "upload",
                path = "/tmp/chunk.m4a",
                state = ChunkState.READY,
                createdAt = 2,
                sha256 = "c".repeat(64),
            ),
        )

        val attemptId = dao.insertAttempt(
            UploadAttemptEntity(
                chunkId = "chunk",
                startedAt = 100,
                outcome = "STARTED",
            ),
        )
        assertEquals(
            1,
            dao.finishAttempt(
                id = attemptId,
                finishedAt = 200,
                outcome = "SUCCEEDED",
                requestId = "request-1",
            ),
        )

        val attempt = requireNotNull(dao.attempt(attemptId))
        assertEquals(200L, attempt.finishedAt)
        assertEquals("SUCCEEDED", attempt.outcome)
        assertEquals("request-1", attempt.requestId)
    }

    @Test
    fun uploadedRecordingRemainsInLocalHistoryAfterSync() = runBlocking {
        dao.upsertSession(
            RecordingSessionEntity("session", RecordingState.STOPPED, startedAt = 1),
        )
        dao.insertChunk(
            ChunkEntity(
                id = "uploaded",
                sessionId = "session",
                uploadId = "uploaded-upload",
                path = "/tmp/uploaded.m4a",
                state = ChunkState.UPLOADED,
                createdAt = 2,
                sha256 = "d".repeat(64),
            ),
        )
        dao.insertChunk(
            ChunkEntity(
                id = "recording",
                sessionId = "session",
                uploadId = "recording-upload",
                path = "/tmp/recording.m4a",
                state = ChunkState.RECORDING,
                createdAt = 3,
            ),
        )

        val history = dao.observeRecentChunks(5).first()

        assertEquals(listOf("uploaded"), history.map { it.id })
    }

    @Test
    fun updatingNoteDoesNotDeleteItsPersistedConflict() = runBlocking {
        val original = NoteEntity(
            serverId = "note",
            folder = "30-ideas",
            name = "note.md",
            content = "before",
            revision = "a".repeat(64),
            updatedAt = "2026-07-23T00:00:00Z",
            syncState = NoteSyncState.CONFLICT,
            baseContent = "server copy",
        )
        notesDao.upsert(original)
        notesDao.insertConflict(
            NoteConflictEntity(
                noteId = original.serverId,
                localContent = original.content,
                serverContent = "server copy",
                serverRevision = "b".repeat(64),
                createdAt = 1,
            ),
        )

        notesDao.upsertAll(listOf(original.copy(lastError = "conflict remains visible")))

        val conflicts = notesDao.observeConflicts(original.serverId).first()
        assertEquals(1, conflicts.size)
        assertEquals("server copy", conflicts.single().serverContent)
        assertEquals("conflict remains visible", notesDao.get(original.serverId)?.lastError)
    }
}
