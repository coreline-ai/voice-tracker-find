package com.coreline.ai.voice.data.repository

import com.coreline.ai.voice.ondevice.api.MainRecordingSource
import com.coreline.ai.voice.ondevice.api.MainRecordingSourceGateway
import com.coreline.ai.voice.ondevice.api.PreparedMainRecordingSource
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class MainRecordingSourceGatewayImpl @Inject constructor(
    private val recordings: RecordingRepository,
) : MainRecordingSourceGateway {
    override val sources: Flow<List<MainRecordingSource>> = recordings.onDeviceAnalysisCandidates.map { chunks ->
        chunks.map { chunk ->
            MainRecordingSource(
                id = chunk.id,
                createdAt = chunk.createdAt,
                durationMs = requireNotNull(chunk.durationMs),
                sizeBytes = requireNotNull(chunk.sizeBytes),
                sha256 = requireNotNull(chunk.sha256),
                extension = chunk.path.substringAfterLast('.', missingDelimiterValue = "").lowercase(),
                storageState = chunk.state,
            )
        }
    }

    override suspend fun prepareSnapshot(
        sourceId: String,
        destination: File,
    ): PreparedMainRecordingSource {
        val chunk = recordings.copyVerifiedChunkForOnDeviceAnalysis(sourceId, destination)
        return PreparedMainRecordingSource(
            source = MainRecordingSource(
                id = chunk.id,
                createdAt = chunk.createdAt,
                durationMs = requireNotNull(chunk.durationMs),
                sizeBytes = requireNotNull(chunk.sizeBytes),
                sha256 = requireNotNull(chunk.sha256),
                extension = chunk.path.substringAfterLast('.', missingDelimiterValue = "").lowercase(),
                storageState = chunk.state,
            ),
            snapshotFile = destination,
        )
    }
}
