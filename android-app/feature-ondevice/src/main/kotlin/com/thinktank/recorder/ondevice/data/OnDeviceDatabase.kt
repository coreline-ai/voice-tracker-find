package com.thinktank.recorder.ondevice.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [OnDeviceSessionEntity::class],
    version = 3,
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
    }
}
