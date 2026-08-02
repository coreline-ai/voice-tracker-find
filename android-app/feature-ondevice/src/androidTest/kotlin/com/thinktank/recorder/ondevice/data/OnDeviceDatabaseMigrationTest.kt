package com.thinktank.recorder.ondevice.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OnDeviceDatabase::class.java,
    )

    @Test
    fun migration3To4AddsMainRecordingSourceColumnsWithoutChangingExistingRows() {
        helper.createDatabase(DATABASE_NAME, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO ondevice_sessions (
                    id, createdAt, updatedAt, state, sttEngine, summaryEngine,
                    transcript, title, summary, actionItems, audioPath,
                    summarySourceHash, summaryGeneratedAt, error, operationToken, failureStage, dataPolicy
                ) VALUES (
                    'legacy', 1, 1, 'TRANSCRIPT_READY', 'ANDROID_ON_DEVICE',
                    'EXTRACTIVE_KOTLIN', '원문', '', '', '', NULL, '', NULL,
                    NULL, NULL, NULL, 'LOCAL_ONLY'
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            4,
            true,
            OnDeviceDatabase.MIGRATION_3_4,
        ).use { db ->
            db.query(
                "SELECT sourceType, sourceChunkId, sourceDisplayName, sourceDurationMs " +
                    "FROM ondevice_sessions WHERE id = 'legacy'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0) == "LIVE_MIC")
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
            }
        }
    }

    @Test
    fun migration4To5AddsSummaryAuditColumnsWithoutChangingExistingRows() {
        helper.createDatabase(DATABASE_NAME_V5, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO ondevice_sessions (
                    id, createdAt, updatedAt, state, sttEngine, summaryEngine,
                    transcript, title, summary, actionItems, audioPath,
                    sourceType, sourceChunkId, sourceDisplayName, sourceDurationMs,
                    summarySourceHash, summaryGeneratedAt, error, operationToken, failureStage, dataPolicy
                ) VALUES (
                    'legacy-v4', 1, 1, 'COMPLETE', 'SENSEVOICE_LOCAL_FILE',
                    'QWEN_LOCAL', '보존할 전사', '기존 제목', '기존 요약', '', NULL,
                    'MAIN_RECORDER_CHUNK', 'chunk', 'WAV · 60초', 60000,
                    'hash', 2, NULL, NULL, NULL, 'LOCAL_ONLY'
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME_V5,
            5,
            true,
            OnDeviceDatabase.MIGRATION_4_5,
        ).use { db ->
            db.query(
                """
                SELECT transcript, summary, requestedSummaryEngine, summaryFallbackReason,
                       summaryPolicyVersion, summaryPromptVersion, summaryModelVersion,
                       summaryValidationStatus
                FROM ondevice_sessions WHERE id = 'legacy-v4'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0) == "보존할 전사")
                assertTrue(cursor.getString(1) == "기존 요약")
                for (index in 2..7) assertTrue(cursor.isNull(index))
            }
        }
    }

    @Test
    fun migration5To6AddsModelProvenanceWithoutChangingExistingRows() {
        helper.createDatabase(DATABASE_NAME_V6, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO ondevice_sessions (
                    id, createdAt, updatedAt, state, sttEngine, summaryEngine,
                    transcript, title, summary, actionItems, sourceType,
                    summarySourceHash, requestedSummaryEngine, dataPolicy
                ) VALUES (
                    'legacy-v5', 1, 1, 'COMPLETE', 'SENSEVOICE_LOCAL_FILE',
                    'QWEN_LOCAL', '보존할 전사', '제목', '보존할 요약', '',
                    'MAIN_RECORDER_CHUNK', 'hash', 'QWEN_LOCAL', 'LOCAL_ONLY'
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME_V6,
            6,
            true,
            OnDeviceDatabase.MIGRATION_5_6,
        ).use { db ->
            db.query(
                """
                SELECT transcript, summary, requestedSummaryModelId, actualSummaryModelId,
                       summaryRuntimeType, summaryGenerationProfile, summaryViolationCodes,
                       summaryDurationMs, summaryInputChars, summaryOutputChars
                FROM ondevice_sessions WHERE id = 'legacy-v5'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0) == "보존할 전사")
                assertTrue(cursor.getString(1) == "보존할 요약")
                for (index in 2..9) assertTrue(cursor.isNull(index))
            }
        }
    }

    @Test
    fun migration6To7AddsSttDiagnosticsWithoutChangingExistingRows() {
        helper.createDatabase(DATABASE_NAME_V7, 6).use { db ->
            db.execSQL(
                """
                INSERT INTO ondevice_sessions (
                    id, createdAt, updatedAt, state, sttEngine, summaryEngine,
                    transcript, title, summary, actionItems, sourceType,
                    summarySourceHash, requestedSummaryEngine, dataPolicy
                ) VALUES (
                    'legacy-v6', 1, 1, 'COMPLETE', 'SENSEVOICE_LOCAL_FILE',
                    'EXTRACTIVE_KOTLIN', '보존할 전사', '제목', '보존할 요약', '',
                    'MAIN_RECORDER_CHUNK', 'hash', 'EXTRACTIVE_KOTLIN', 'LOCAL_ONLY'
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME_V7,
            7,
            true,
            OnDeviceDatabase.MIGRATION_6_7,
        ).use { db ->
            db.query(
                """
                SELECT transcript, summary, sttInputDurationMs, sttProcessedThroughMs,
                       sttSegmentCount, sttRecognizedSegmentCount, sttRetryCount,
                       sttMeaningfulChars, sttCharsPerSecond, sttQualityStatus,
                       sttSegmentDiagnostics
                FROM ondevice_sessions WHERE id = 'legacy-v6'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0) == "보존할 전사")
                assertTrue(cursor.getString(1) == "보존할 요약")
                for (index in 2..10) assertTrue(cursor.isNull(index))
            }
        }
    }

    @Test
    fun migration7To8AddsSummaryRunsWithoutChangingExistingRows() {
        helper.createDatabase(DATABASE_NAME_V8, 7).use { db ->
            db.execSQL(
                """
                INSERT INTO ondevice_sessions (
                    id, createdAt, updatedAt, state, sttEngine, summaryEngine,
                    transcript, title, summary, actionItems, sourceType,
                    summarySourceHash, requestedSummaryEngine, sttInputDurationMs,
                    sttProcessedThroughMs, sttSegmentCount, sttRecognizedSegmentCount,
                    sttRetryCount, sttMeaningfulChars, sttCharsPerSecond,
                    sttQualityStatus, sttSegmentDiagnostics, dataPolicy
                ) VALUES (
                    'legacy-v7', 1, 1, 'COMPLETE', 'SENSEVOICE_LOCAL_FILE',
                    'EXTRACTIVE_KOTLIN', '보존할 전사', '제목', '보존할 요약', '',
                    'MAIN_RECORDER_CHUNK', 'hash', 'EXTRACTIVE_KOTLIN',
                    312128, 312128, 13, 13, 0, 1411, 4.52,
                    'COMPLETE', '0-1000:10', 'LOCAL_ONLY'
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME_V8,
            8,
            true,
            OnDeviceDatabase.MIGRATION_7_8,
        ).use { db ->
            db.query(
                """
                SELECT transcript, summary, sttInputDurationMs, sttProcessedThroughMs,
                       selectedSummaryRunId, sttCoverageStatus,
                       sttRecognitionQualityStatus, sttRecognitionDiagnostics
                FROM ondevice_sessions WHERE id = 'legacy-v7'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0) == "보존할 전사")
                assertTrue(cursor.getString(1) == "보존할 요약")
                assertTrue(cursor.getLong(2) == 312128L)
                assertTrue(cursor.getLong(3) == 312128L)
                for (index in 4..7) assertTrue(cursor.isNull(index))
            }
            db.query("SELECT COUNT(*) FROM ondevice_summary_batches").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getInt(0) == 0)
            }
            db.query("SELECT COUNT(*) FROM ondevice_summary_runs").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getInt(0) == 0)
            }
        }
    }

    @Test
    fun migration8To9AddsLongProcessingCheckpointsWithoutChangingExistingRows() {
        helper.createDatabase(DATABASE_NAME_V9, 8).use { db ->
            db.execSQL(
                """
                INSERT INTO ondevice_sessions (
                    id, createdAt, updatedAt, state, sttEngine, summaryEngine,
                    transcript, title, summary, actionItems, sourceType,
                    summarySourceHash, dataPolicy
                ) VALUES (
                    'legacy-v8', 1, 2, 'COMPLETE', 'SENSEVOICE_LOCAL_FILE',
                    'GEMMA_LOCAL', '보존할 장시간 전사', '기존 제목', '기존 요약', '',
                    'MAIN_RECORDER_CHUNK', 'source-hash', 'LOCAL_ONLY'
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME_V9,
            9,
            true,
            OnDeviceDatabase.MIGRATION_8_9,
        ).use { db ->
            db.query(
                """
                SELECT transcript, title, summary, summarySourceHash,
                       activeProcessingJobId, summaryRootNodeId, processingVersion
                FROM ondevice_sessions WHERE id = 'legacy-v8'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0) == "보존할 장시간 전사")
                assertTrue(cursor.getString(1) == "기존 제목")
                assertTrue(cursor.getString(2) == "기존 요약")
                assertTrue(cursor.getString(3) == "source-hash")
                for (index in 4..6) assertTrue(cursor.isNull(index))
            }
            listOf(
                "ondevice_processing_jobs",
                "ondevice_transcript_segments",
                "ondevice_summary_nodes",
            ).forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getInt(0) == 0)
                }
            }
        }
    }

    @Test
    fun migration9To10AddsOAuthSummaryProvenanceWithoutChangingExistingRows() {
        helper.createDatabase(DATABASE_NAME_V10, 9).use { db ->
            db.execSQL(
                """
                INSERT INTO ondevice_sessions (
                    id, createdAt, updatedAt, state, sttEngine, summaryEngine,
                    transcript, title, summary, actionItems, sourceType,
                    summarySourceHash, dataPolicy
                ) VALUES (
                    'legacy-v9', 1, 2, 'COMPLETE', 'SENSEVOICE_LOCAL_FILE',
                    'GEMMA_LOCAL', '보존할 전사', '기존 제목', '기존 요약', '',
                    'MAIN_RECORDER_CHUNK', 'source-hash', 'LOCAL_ONLY'
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME_V10,
            10,
            true,
            OnDeviceDatabase.MIGRATION_9_10,
        ).use { db ->
            db.query(
                """
                SELECT transcript, summary, summaryProviderId, summaryProviderRequestId,
                       summaryInputTokens, summaryOutputTokens
                FROM ondevice_sessions WHERE id = 'legacy-v9'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0) == "보존할 전사")
                assertTrue(cursor.getString(1) == "기존 요약")
                for (index in 2..5) assertTrue(cursor.isNull(index))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "ondevice-migration-test"
        const val DATABASE_NAME_V5 = "ondevice-migration-v5-test"
        const val DATABASE_NAME_V6 = "ondevice-migration-v6-test"
        const val DATABASE_NAME_V7 = "ondevice-migration-v7-test"
        const val DATABASE_NAME_V8 = "ondevice-migration-v8-test"
        const val DATABASE_NAME_V9 = "ondevice-migration-v9-test"
        const val DATABASE_NAME_V10 = "ondevice-migration-v10-test"
    }
}
