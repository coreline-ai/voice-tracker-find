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

    private companion object {
        const val DATABASE_NAME = "ondevice-migration-test"
        const val DATABASE_NAME_V5 = "ondevice-migration-v5-test"
    }
}
