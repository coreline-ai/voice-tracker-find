package com.coreline.ai.voice.di

import android.content.Context
import androidx.room.Room
import com.coreline.ai.voice.data.local.NotesDao
import com.coreline.ai.voice.data.local.RecordingDao
import com.coreline.ai.voice.data.local.SyncDao
import com.coreline.ai.voice.data.local.AirVoiceDatabase
import com.coreline.ai.voice.data.remote.NotesRemoteGateway
import com.coreline.ai.voice.data.remote.ReceiverApi
import com.coreline.ai.voice.data.repository.MainRecordingSourceGatewayImpl
import com.coreline.ai.voice.data.settings.AppPreferences
import com.coreline.ai.voice.data.settings.SettingsReader
import com.coreline.ai.voice.ondevice.api.MainRecordingSourceGateway
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
    fun database(@ApplicationContext context: Context): AirVoiceDatabase =
        Room.databaseBuilder(
            context,
            AirVoiceDatabase::class.java,
            "airvoice.db",
        ).build()

    @Provides
    fun recordingDao(database: AirVoiceDatabase): RecordingDao = database.recordingDao()

    @Provides
    fun notesDao(database: AirVoiceDatabase): NotesDao = database.notesDao()

    @Provides
    fun syncDao(database: AirVoiceDatabase): SyncDao = database.syncDao()

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

    @Binds
    abstract fun mainRecordingSourceGateway(
        gateway: MainRecordingSourceGatewayImpl,
    ): MainRecordingSourceGateway
}
