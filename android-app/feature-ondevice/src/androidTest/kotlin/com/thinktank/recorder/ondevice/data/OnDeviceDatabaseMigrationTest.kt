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

    private companion object {
        const val DATABASE_NAME = "ondevice-migration-test"
    }
}
