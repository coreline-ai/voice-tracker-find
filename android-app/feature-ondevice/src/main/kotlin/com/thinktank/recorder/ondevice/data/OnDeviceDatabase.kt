package com.thinktank.recorder.ondevice.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        OnDeviceSessionEntity::class,
        OnDeviceSummaryBatchEntity::class,
        OnDeviceSummaryRunEntity::class,
        OnDeviceProcessingJobEntity::class,
        OnDeviceTranscriptSegmentEntity::class,
        OnDeviceSummaryNodeEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class OnDeviceDatabase : RoomDatabase() {
    abstract fun sessionDao(): OnDeviceSessionDao
    abstract fun longProcessingDao(): OnDeviceLongProcessingDao

    companion object {
        @Volatile
        private var instance: OnDeviceDatabase? = null

        fun get(context: Context): OnDeviceDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OnDeviceDatabase::class.java,
                    "ondevice.db",
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                    )
                    .build()
                    .also { instance = it }
            }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN audioPath TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summarySourceHash TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryGeneratedAt INTEGER DEFAULT NULL",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN operationToken TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN failureStage TEXT DEFAULT NULL",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'LIVE_MIC'",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN sourceChunkId TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN sourceDisplayName TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN sourceDurationMs INTEGER DEFAULT NULL",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN requestedSummaryEngine TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryFallbackReason TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryPolicyVersion INTEGER DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryPromptVersion INTEGER DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryModelVersion TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryValidationStatus TEXT DEFAULT NULL",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN requestedSummaryModelId TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN actualSummaryModelId TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryRuntimeType TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryGenerationProfile TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryViolationCodes TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryDurationMs INTEGER DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryInputChars INTEGER DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryOutputChars INTEGER DEFAULT NULL",
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN sttInputDurationMs INTEGER DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN sttProcessedThroughMs INTEGER DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN sttSegmentCount INTEGER DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN sttRecognizedSegmentCount INTEGER DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN sttRetryCount INTEGER DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN sttMeaningfulChars INTEGER DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN sttCharsPerSecond REAL DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN sttQualityStatus TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN sttSegmentDiagnostics TEXT DEFAULT NULL",
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN sttCoverageStatus TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN sttRecognitionQualityStatus TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN sttRecognitionDiagnostics TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN selectedSummaryRunId TEXT DEFAULT NULL",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ondevice_summary_batches (
                        id TEXT NOT NULL,
                        sessionId TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        sourceHash TEXT NOT NULL,
                        inputHash TEXT NOT NULL,
                        inputBuilderVersion INTEGER NOT NULL,
                        inputPayload TEXT NOT NULL,
                        requestedEngines TEXT NOT NULL,
                        selectedRunId TEXT,
                        error TEXT,
                        operationToken TEXT,
                        dataPolicy TEXT NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(sessionId) REFERENCES ondevice_sessions(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ondevice_summary_runs (
                        id TEXT NOT NULL,
                        batchId TEXT NOT NULL,
                        sessionId TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        orderIndex INTEGER NOT NULL,
                        requestedEngine TEXT NOT NULL,
                        attemptedEngine TEXT NOT NULL,
                        state TEXT NOT NULL,
                        failureStage TEXT,
                        failureCode TEXT,
                        violationCodes TEXT,
                        title TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        actionItems TEXT NOT NULL,
                        evidenceIds TEXT NOT NULL,
                        rawOutput TEXT,
                        rawOutputLength INTEGER,
                        rawOutputHash TEXT,
                        rawOutputTruncated INTEGER NOT NULL,
                        requestedModelId TEXT,
                        modelId TEXT,
                        modelVersion TEXT,
                        runtimeType TEXT,
                        generationProfile TEXT,
                        policyVersion INTEGER,
                        promptVersion INTEGER,
                        validationStatus TEXT,
                        durationMs INTEGER,
                        inputChars INTEGER,
                        outputChars INTEGER,
                        startedAt INTEGER,
                        completedAt INTEGER,
                        fallbackForRunId TEXT,
                        operationToken TEXT,
                        dataPolicy TEXT NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(batchId) REFERENCES ondevice_summary_batches(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ondevice_summary_batches_sessionId " +
                        "ON ondevice_summary_batches(sessionId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ondevice_summary_batches_createdAt " +
                        "ON ondevice_summary_batches(createdAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ondevice_summary_runs_batchId " +
                        "ON ondevice_summary_runs(batchId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ondevice_summary_runs_sessionId " +
                        "ON ondevice_summary_runs(sessionId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ondevice_summary_runs_createdAt " +
                        "ON ondevice_summary_runs(createdAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ondevice_summary_runs_state " +
                        "ON ondevice_summary_runs(state)",
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN activeProcessingJobId TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryRootNodeId TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN processingVersion INTEGER DEFAULT NULL",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ondevice_processing_jobs (
                        id TEXT NOT NULL,
                        sessionId TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        stage TEXT NOT NULL,
                        sourceFingerprint TEXT NOT NULL,
                        sourceDurationMs INTEGER NOT NULL,
                        sourceSizeBytes INTEGER NOT NULL,
                        sourceSnapshotPath TEXT NOT NULL,
                        pcmPath TEXT NOT NULL,
                        serviceToken TEXT,
                        completedSttSegments INTEGER NOT NULL,
                        totalSttSegments INTEGER NOT NULL,
                        completedSummaryNodes INTEGER NOT NULL,
                        totalSummaryNodes INTEGER NOT NULL,
                        currentSummaryLevel INTEGER NOT NULL,
                        rootNodeId TEXT,
                        pauseRequested INTEGER NOT NULL,
                        cancelRequested INTEGER NOT NULL,
                        retryCount INTEGER NOT NULL,
                        failureCode TEXT,
                        error TEXT,
                        checkpointVersion INTEGER NOT NULL,
                        dataPolicy TEXT NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(sessionId) REFERENCES ondevice_sessions(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ondevice_transcript_segments (
                        id TEXT NOT NULL,
                        jobId TEXT NOT NULL,
                        sessionId TEXT NOT NULL,
                        passType TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        startMs INTEGER NOT NULL,
                        endMs INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        textHash TEXT NOT NULL,
                        sourceRangeHash TEXT NOT NULL,
                        meaningfulChars INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(jobId) REFERENCES ondevice_processing_jobs(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ondevice_summary_nodes (
                        id TEXT NOT NULL,
                        jobId TEXT NOT NULL,
                        sessionId TEXT NOT NULL,
                        level INTEGER NOT NULL,
                        ordinal INTEGER NOT NULL,
                        nodeType TEXT NOT NULL,
                        state TEXT NOT NULL,
                        sourceStartMs INTEGER NOT NULL,
                        sourceEndMs INTEGER NOT NULL,
                        leafStartOrdinal INTEGER NOT NULL,
                        leafEndOrdinal INTEGER NOT NULL,
                        childNodeIds TEXT NOT NULL,
                        inputPayload TEXT NOT NULL,
                        inputHash TEXT NOT NULL,
                        sourceHash TEXT NOT NULL,
                        title TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        evidenceChildIds TEXT NOT NULL,
                        outputHash TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        failureCode TEXT,
                        violationCodes TEXT,
                        modelVersion TEXT,
                        runtimeType TEXT,
                        generationProfile TEXT,
                        durationMs INTEGER,
                        startedAt INTEGER,
                        completedAt INTEGER,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        dataPolicy TEXT NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(jobId) REFERENCES ondevice_processing_jobs(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ondevice_processing_jobs_sessionId " +
                        "ON ondevice_processing_jobs(sessionId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ondevice_processing_jobs_state " +
                        "ON ondevice_processing_jobs(state)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ondevice_processing_jobs_updatedAt " +
                        "ON ondevice_processing_jobs(updatedAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ondevice_transcript_segments_jobId " +
                        "ON ondevice_transcript_segments(jobId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ondevice_transcript_segments_sessionId " +
                        "ON ondevice_transcript_segments(sessionId)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_ondevice_transcript_segments_jobId_passType_ordinal " +
                        "ON ondevice_transcript_segments(jobId, passType, ordinal)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_ondevice_transcript_segments_jobId_startMs_endMs " +
                        "ON ondevice_transcript_segments(jobId, startMs, endMs)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ondevice_summary_nodes_jobId " +
                        "ON ondevice_summary_nodes(jobId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ondevice_summary_nodes_sessionId " +
                        "ON ondevice_summary_nodes(sessionId)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_ondevice_summary_nodes_jobId_level_ordinal_inputHash " +
                        "ON ondevice_summary_nodes(jobId, level, ordinal, inputHash)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ondevice_summary_nodes_jobId_state " +
                        "ON ondevice_summary_nodes(jobId, state)",
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryProviderId TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryProviderRequestId TEXT DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryInputTokens INTEGER DEFAULT NULL",
                )
                db.execSQL(
                    "ALTER TABLE ondevice_sessions ADD COLUMN summaryOutputTokens INTEGER DEFAULT NULL",
                )
            }
        }
    }
}
