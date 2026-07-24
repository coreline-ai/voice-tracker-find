package com.thinktank.recorder.next.data.repository

import com.thinktank.recorder.next.data.local.ChunkEntity
import com.thinktank.recorder.next.data.local.RecordingDao
import com.thinktank.recorder.next.data.local.RecordingSessionEntity
import com.thinktank.recorder.next.recording.RecordingRuntime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class RecordingRepository @Inject constructor(
    private val dao: RecordingDao,
    runtime: RecordingRuntime,
) {
    val latestSession: Flow<RecordingSessionEntity?> = dao.observeLatestSession()
    val latestChunk: Flow<ChunkEntity?> = dao.observeLatestChunk()
    val recentChunks: Flow<List<ChunkEntity>> = dao.observeRecentChunks(MAX_RECENT_CHUNKS)
    val pendingUploads: Flow<Int> = dao.observePendingCount()
    val amplitude: StateFlow<Float> = runtime.amplitude

    private companion object {
        const val MAX_RECENT_CHUNKS = 5
    }
}
