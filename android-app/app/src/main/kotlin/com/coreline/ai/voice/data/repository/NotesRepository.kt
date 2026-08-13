package com.coreline.ai.voice.data.repository

import com.coreline.ai.voice.data.local.NoteConflictEntity
import com.coreline.ai.voice.data.local.NoteEntity
import com.coreline.ai.voice.data.local.NoteSyncState
import com.coreline.ai.voice.data.local.NotesDao
import com.coreline.ai.voice.data.remote.ApiException
import com.coreline.ai.voice.data.remote.NotesRemoteGateway
import com.coreline.ai.voice.data.remote.RemoteNote
import com.coreline.ai.voice.data.settings.SettingsReader
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface NotesSyncResult {
    data class Success(val count: Int) : NotesSyncResult
    data class Retryable(val reason: String) : NotesSyncResult
    data class Failed(val reason: String) : NotesSyncResult
}

@Singleton
class NotesRepository @Inject constructor(
    private val dao: NotesDao,
    private val api: NotesRemoteGateway,
    private val preferences: SettingsReader,
) {
    val notes: Flow<List<NoteEntity>> = dao.observeAll()
    private val syncMutex = Mutex()

    fun observe(id: String): Flow<NoteEntity?> = dao.observe(id)

    suspend fun create(folder: String, name: String, content: String): Result<NoteEntity> =
        runCatching {
            val settings = preferences.current()
            require(settings.isServerConfigured) { "서버 설정이 필요합니다" }
            val remote = api.createNote(settings, folder, normalizeName(name), content)
            remote.toEntity().also { dao.upsert(it) }
        }

    suspend fun save(id: String, content: String): Result<NoteEntity> = runCatching {
        val existing = requireNotNull(dao.get(id))
        val dirty = existing.copy(
            content = content,
            syncState = NoteSyncState.DIRTY,
            baseContent = existing.baseContent ?: existing.content,
            lastError = null,
        )
        dao.upsert(dirty)
        pushOne(dirty)
    }

    suspend fun archive(id: String): Result<Unit> = runCatching {
        val existing = requireNotNull(dao.get(id))
        val pending = existing.copy(syncState = NoteSyncState.PENDING_DELETE)
        dao.upsert(pending)
        val settings = preferences.current()
        when (val outcome = archivePending(settings, pending)) {
            is ArchiveOutcome.Completed -> Unit
            is ArchiveOutcome.Retryable -> throw outcome.error
            is ArchiveOutcome.Failed -> throw outcome.error
        }
    }

    suspend fun syncAll(): NotesSyncResult = syncMutex.withLock {
        val settings = preferences.current()
        if (!settings.isServerConfigured) return NotesSyncResult.Failed("서버 설정이 필요합니다")
        var deferredRetry: Exception? = null
        var deferredFailure: ApiException? = null
        return try {
            dao.localChanges().forEach { note ->
                when (note.syncState) {
                    NoteSyncState.DIRTY, NoteSyncState.FAILED -> pushOne(note)
                    NoteSyncState.PENDING_DELETE -> {
                        when (val outcome = archivePending(settings, note)) {
                            is ArchiveOutcome.Completed -> Unit
                            is ArchiveOutcome.Retryable -> {
                                if (deferredRetry == null) deferredRetry = outcome.error
                            }
                            is ArchiveOutcome.Failed -> {
                                if (deferredFailure == null) deferredFailure = outcome.error
                            }
                        }
                    }
                }
            }

            val remote = api.listNotes(settings)
            val localChanges = dao.localChanges().associateBy { it.serverId }
            remote.forEach { serverNote ->
                val local = localChanges[serverNote.id]
                if (local == null) dao.upsert(serverNote.toEntity())
                else if (local.revision != serverNote.revision) {
                    dao.insertConflict(
                        NoteConflictEntity(
                            noteId = local.serverId,
                            localContent = local.content,
                            serverContent = serverNote.content,
                            serverRevision = serverNote.revision,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    dao.upsert(
                        local.copy(
                            syncState = NoteSyncState.CONFLICT,
                            revision = serverNote.revision,
                            baseContent = serverNote.content,
                            lastError = "서버에 더 새로운 편집이 있습니다",
                        ),
                    )
                }
            }
            val remoteIds = remote.map(RemoteNote::id)
            if (remoteIds.isEmpty()) dao.deleteAllSynced() else dao.deleteSyncedMissing(remoteIds)
            when {
                deferredFailure != null -> {
                    val error = requireNotNull(deferredFailure)
                    NotesSyncResult.Failed("${error.code}: ${error.message}")
                }
                deferredRetry != null -> {
                    val error = requireNotNull(deferredRetry)
                    NotesSyncResult.Retryable(
                        (error as? ApiException)?.code ?: error.message ?: "ARCHIVE_RETRY",
                    )
                }
                else -> NotesSyncResult.Success(remote.size)
            }
        } catch (error: ApiException) {
            if (error.status == 408 || error.status == 429 || error.status >= 500) {
                NotesSyncResult.Retryable(error.code)
            } else {
                NotesSyncResult.Failed("${error.code}: ${error.message}")
            }
        } catch (error: Exception) {
            NotesSyncResult.Retryable(error.message ?: "NETWORK_ERROR")
        }
    }

    private suspend fun pushOne(note: NoteEntity): NoteEntity {
        val settings = preferences.current()
        return try {
            val updated = api.updateNote(settings, note.toRemote(), note.content).toEntity()
            dao.upsert(updated)
            updated
        } catch (conflict: ApiException) {
            if (conflict.status != 412) throw conflict
            val server = api.getNote(settings, note.serverId)
            dao.insertConflict(
                NoteConflictEntity(
                    noteId = note.serverId,
                    localContent = note.content,
                    serverContent = server.content,
                    serverRevision = server.revision,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            val conflicted = note.copy(
                revision = server.revision,
                syncState = NoteSyncState.CONFLICT,
                baseContent = server.content,
                lastError = "편집 충돌: 로컬 내용을 보존했습니다",
            )
            dao.upsert(conflicted)
            conflicted
        } catch (error: Exception) {
            val failed = note.copy(
                syncState = NoteSyncState.FAILED,
                lastError = error.message ?: "SAVE_FAILED",
            )
            dao.upsert(failed)
            throw error
        }
    }

    /**
     * A server-side 404 means the desired archived state is already true.  Other failures leave
     * the note pending so that the next worker run retries an archive rather than issuing an
     * accidental update.  syncAll can then continue with independent notes and the remote list.
     */
    private suspend fun archivePending(
        settings: com.coreline.ai.voice.data.settings.UserSettings,
        note: NoteEntity,
    ): ArchiveOutcome = try {
        api.archiveNote(settings, note.toRemote())
        dao.deleteById(note.serverId)
        ArchiveOutcome.Completed
    } catch (error: ApiException) {
        if (error.status == 404) {
            dao.deleteById(note.serverId)
            ArchiveOutcome.Completed
        } else {
            dao.upsert(
                note.copy(
                    syncState = NoteSyncState.PENDING_DELETE,
                    lastError = error.code,
                ),
            )
            if (error.status == 408 || error.status == 429 || error.status >= 500) {
                ArchiveOutcome.Retryable(error)
            } else {
                ArchiveOutcome.Failed(error)
            }
        }
    } catch (error: Exception) {
        dao.upsert(
            note.copy(
                syncState = NoteSyncState.PENDING_DELETE,
                lastError = error.message ?: "ARCHIVE_FAILED",
            ),
        )
        ArchiveOutcome.Retryable(error)
    }

    private sealed interface ArchiveOutcome {
        data object Completed : ArchiveOutcome
        data class Retryable(val error: Exception) : ArchiveOutcome
        data class Failed(val error: ApiException) : ArchiveOutcome
    }

    private fun normalizeName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "노트 이름이 필요합니다" }
        require('/' !in trimmed && '\\' !in trimmed && ".." !in trimmed) {
            "사용할 수 없는 노트 이름입니다"
        }
        return if (trimmed.endsWith(".md")) trimmed else "$trimmed.md"
    }

    private fun RemoteNote.toEntity() = NoteEntity(
        serverId = id,
        folder = folder,
        name = name,
        content = content,
        revision = revision,
        updatedAt = updatedAt,
        syncState = NoteSyncState.SYNCED,
        baseContent = content,
    )

    private fun NoteEntity.toRemote() = RemoteNote(
        id = serverId,
        folder = folder,
        name = name,
        content = content,
        revision = revision,
        updatedAt = updatedAt.ifBlank { Instant.EPOCH.toString() },
    )
}
