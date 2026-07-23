package com.thinktank.recorder.next.data.repository

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thinktank.recorder.next.data.local.NoteEntity
import com.thinktank.recorder.next.data.local.NoteSyncState
import com.thinktank.recorder.next.data.local.NotesDao
import com.thinktank.recorder.next.data.local.ThinkTankDatabase
import com.thinktank.recorder.next.data.remote.ApiException
import com.thinktank.recorder.next.data.remote.NotesRemoteGateway
import com.thinktank.recorder.next.data.remote.RemoteNote
import com.thinktank.recorder.next.data.settings.SettingsReader
import com.thinktank.recorder.next.data.settings.UserSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class NotesRepositoryTest {
    private lateinit var database: ThinkTankDatabase
    private lateinit var dao: NotesDao
    private lateinit var gateway: FakeNotesGateway
    private lateinit var repository: NotesRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ThinkTankDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.notesDao()
        gateway = FakeNotesGateway()
        repository = NotesRepository(dao, gateway, TestSettingsReader)
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun archivedNoteMissingOnServerIsDeletedLocally() = runBlocking {
        dao.upsert(pendingDelete("deleted-remotely"))
        gateway.archiveFailure = ApiException(404, "NOTE_NOT_FOUND", "already archived")

        val result = repository.syncAll()

        assertEquals(NotesSyncResult.Success(0), result)
        assertNull(dao.get("deleted-remotely"))
        assertEquals(1, gateway.archiveCalls)
        assertEquals(1, gateway.listCalls)
    }

    @Test
    fun retryablePendingDeleteDoesNotBlockOtherNoteOrRemoteRefresh() = runBlocking {
        val pending = pendingDelete("delete-later")
        val dirty = note("save-now", NoteSyncState.DIRTY, content = "local edit", revision = "rev-1")
        dao.upsertAll(listOf(pending, dirty))
        gateway.archiveFailure = ApiException(503, "SERVER_BUSY", "try later")
        gateway.updatedNotes[dirty.serverId] = dirty.toRemote(content = "saved", revision = "rev-2")
        gateway.remoteNotes = listOf(gateway.updatedNotes.getValue(dirty.serverId))

        val result = repository.syncAll()

        assertEquals(NotesSyncResult.Retryable("SERVER_BUSY"), result)
        assertEquals(1, gateway.archiveCalls)
        assertEquals(1, gateway.updateCalls)
        assertEquals(1, gateway.listCalls)
        assertEquals(NoteSyncState.PENDING_DELETE, dao.get(pending.serverId)?.syncState)
        assertEquals("SERVER_BUSY", dao.get(pending.serverId)?.lastError)
        assertEquals(NoteSyncState.SYNCED, dao.get(dirty.serverId)?.syncState)
        assertEquals("saved", dao.get(dirty.serverId)?.content)
    }

    @Test
    fun manualArchiveTreatsNotFoundAsAlreadyArchived() = runBlocking {
        val note = note("manual-404", NoteSyncState.SYNCED)
        dao.upsert(note)
        gateway.archiveFailure = ApiException(404, "NOTE_NOT_FOUND", "already archived")

        val result = repository.archive(note.serverId)

        assertTrue(result.isSuccess)
        assertNull(dao.get(note.serverId))
    }

    private fun pendingDelete(id: String) = note(id, NoteSyncState.PENDING_DELETE)

    private fun note(
        id: String,
        state: String,
        content: String = "local",
        revision: String = "rev-1",
    ) = NoteEntity(
        serverId = id,
        folder = "30-ideas",
        name = "$id.md",
        content = content,
        revision = revision,
        updatedAt = "2026-07-23T00:00:00Z",
        syncState = state,
        baseContent = content,
    )

    private fun NoteEntity.toRemote(content: String = this.content, revision: String = this.revision) =
        RemoteNote(serverId, folder, name, content, revision, updatedAt)

    private object TestSettingsReader : SettingsReader {
        override suspend fun current() = UserSettings(
            serverUrl = "https://receiver.test",
            userId = "user-1",
            token = "test-token",
        )
    }

    private class FakeNotesGateway : NotesRemoteGateway {
        var archiveFailure: Exception? = null
        var remoteNotes: List<RemoteNote> = emptyList()
        val updatedNotes = mutableMapOf<String, RemoteNote>()
        var archiveCalls = 0
        var updateCalls = 0
        var listCalls = 0

        override suspend fun listNotes(settings: UserSettings): List<RemoteNote> {
            listCalls += 1
            return remoteNotes
        }

        override suspend fun getNote(settings: UserSettings, id: String): RemoteNote =
            remoteNotes.first { it.id == id }

        override suspend fun createNote(
            settings: UserSettings,
            folder: String,
            name: String,
            content: String,
        ): RemoteNote = RemoteNote("new", folder, name, content, "rev", "")

        override suspend fun updateNote(
            settings: UserSettings,
            note: RemoteNote,
            content: String,
        ): RemoteNote {
            updateCalls += 1
            return updatedNotes.getValue(note.id)
        }

        override suspend fun archiveNote(settings: UserSettings, note: RemoteNote) {
            archiveCalls += 1
            archiveFailure?.let { throw it }
        }
    }
}
