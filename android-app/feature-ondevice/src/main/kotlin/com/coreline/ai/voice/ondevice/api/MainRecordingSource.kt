package com.coreline.ai.voice.ondevice.api

import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * A verified, finalized recording owned by the main recorder module.
 *
 * The feature module intentionally knows no app Room entity or recorder implementation.
 */
data class MainRecordingSource(
    val id: String,
    val createdAt: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val sha256: String,
    val extension: String,
    val storageState: String,
)

data class PreparedMainRecordingSource(
    val source: MainRecordingSource,
    val snapshotFile: File,
)

/**
 * App-to-feature boundary for the first-tab recording archive.
 *
 * Implementations must verify the original and write an immutable working snapshot. The feature
 * only receives the snapshot, so deleting an on-device AI session can never delete the source
 * recording in the main recorder archive.
 */
interface MainRecordingSourceGateway {
    val sources: Flow<List<MainRecordingSource>>

    suspend fun prepareSnapshot(
        sourceId: String,
        destination: File,
    ): PreparedMainRecordingSource
}
