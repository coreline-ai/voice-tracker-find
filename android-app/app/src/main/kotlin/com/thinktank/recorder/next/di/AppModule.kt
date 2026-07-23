package com.thinktank.recorder.next.di

import android.content.Context
import androidx.room.Room
import com.thinktank.recorder.next.data.local.NotesDao
import com.thinktank.recorder.next.data.local.RecordingDao
import com.thinktank.recorder.next.data.local.SyncDao
import com.thinktank.recorder.next.data.local.ThinkTankDatabase
import com.thinktank.recorder.next.data.remote.NotesRemoteGateway
import com.thinktank.recorder.next.data.remote.ReceiverApi
import com.thinktank.recorder.next.data.settings.AppPreferences
import com.thinktank.recorder.next.data.settings.SettingsReader
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ThinkTankDatabase =
        Room.databaseBuilder(
            context,
            ThinkTankDatabase::class.java,
            "thinktank-recorder.db",
        ).build()

    @Provides
    fun recordingDao(database: ThinkTankDatabase): RecordingDao = database.recordingDao()

    @Provides
    fun notesDao(database: ThinkTankDatabase): NotesDao = database.notesDao()

    @Provides
    fun syncDao(database: ThinkTankDatabase): SyncDao = database.syncDao()

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .callTimeout(12, TimeUnit.MINUTES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindings {
    @Binds
    abstract fun notesRemoteGateway(api: ReceiverApi): NotesRemoteGateway

    @Binds
    abstract fun settingsReader(preferences: AppPreferences): SettingsReader
}
