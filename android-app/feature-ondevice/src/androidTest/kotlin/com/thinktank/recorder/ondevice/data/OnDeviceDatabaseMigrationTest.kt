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
    fun migration2To3AddsOperationColumnsWithoutChangingExistingRows() {
        helper.createDatabase(DATABASE_NAME, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO ondevice_sessions (
                    id, createdAt, updatedAt, state, sttEngine, summaryEngine,
                    transcript, title, summary, actionItems, audioPath,
                    summarySourceHash, summaryGeneratedAt, error, dataPolicy
                ) VALUES (
                    'legacy', 1, 1, 'TRANSCRIPT_READY', 'ANDROID_ON_DEVICE',
                    'EXTRACTIVE_KOTLIN', '원문', '', '', '', NULL, '', NULL,
                    NULL, 'LOCAL_ONLY'
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            3,
            true,
            OnDeviceDatabase.MIGRATION_2_3,
        ).use { db ->
            db.query(
                "SELECT operationToken, failureStage FROM ondevice_sessions WHERE id = 'legacy'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "ondevice-migration-test"
    }
}
