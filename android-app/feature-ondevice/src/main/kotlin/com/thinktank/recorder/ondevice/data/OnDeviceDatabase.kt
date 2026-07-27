package com.thinktank.recorder.ondevice.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [OnDeviceSessionEntity::class],
    version = 6,
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
    }
}
