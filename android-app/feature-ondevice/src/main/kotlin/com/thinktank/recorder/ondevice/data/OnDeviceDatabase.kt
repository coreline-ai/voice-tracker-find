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
    ],
    version = 8,
    exportSchema = true,
)
abstract class OnDeviceDatabase : RoomDatabase() {
    abstract fun sessionDao(): OnDeviceSessionDao

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
    }
}
