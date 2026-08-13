package com.coreline.ai.voice.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.coreline.ai.voice.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("airvoice_settings")

data class UserSettings(
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
    val userId: String = "user1",
    val token: String = "",
    val chunkMinutes: Int = 20,
    val scheduleEnabled: Boolean = false,
    val scheduleStartMinutes: Int = 7 * 60,
    val scheduleEndMinutes: Int = 22 * 60,
    val autoSync: Boolean = true,
    val wifiOnly: Boolean = true,
    val onboardingComplete: Boolean = false,
) {
    val isServerConfigured: Boolean
        get() = serverUrl.isNotBlank() && userId.isNotBlank() && token.isNotBlank()
}

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenCipher: TokenCipher,
) : SettingsReader {
    val settings: Flow<UserSettings> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { preferences ->
            UserSettings(
                serverUrl = preferences[SERVER_URL] ?: BuildConfig.DEFAULT_SERVER_URL,
                userId = preferences[USER_ID] ?: "user1",
                token = preferences[ENCRYPTED_TOKEN]?.let(tokenCipher::decrypt).orEmpty(),
                chunkMinutes = (preferences[CHUNK_MINUTES] ?: 20).coerceIn(5, 120),
                scheduleEnabled = preferences[SCHEDULE_ENABLED] ?: false,
                scheduleStartMinutes = preferences[SCHEDULE_START] ?: 7 * 60,
                scheduleEndMinutes = preferences[SCHEDULE_END] ?: 22 * 60,
                autoSync = preferences[AUTO_SYNC] ?: true,
                wifiOnly = preferences[WIFI_ONLY] ?: true,
                onboardingComplete = preferences[ONBOARDING_COMPLETE] ?: false,
            )
        }

    override suspend fun current(): UserSettings = settings.first()

    fun serverCandidate(url: String, userId: String, token: String): UserSettings =
        UserSettings(
            serverUrl = normalizeServerUrl(url),
            userId = userId.trim(),
            token = token.trim(),
        )

    suspend fun updateServer(url: String, userId: String, token: String) {
        val candidate = serverCandidate(url, userId, token)
        context.dataStore.edit {
            it[SERVER_URL] = candidate.serverUrl
            it[USER_ID] = candidate.userId
            it[ENCRYPTED_TOKEN] = tokenCipher.encrypt(candidate.token)
        }
    }

    suspend fun updateChunkMinutes(value: Int) {
        require(value in 5..120 && value % 5 == 0)
        context.dataStore.edit { it[CHUNK_MINUTES] = value }
    }

    suspend fun updateSchedule(enabled: Boolean, startMinutes: Int, endMinutes: Int) {
        require(startMinutes in 0..1439 && endMinutes in 0..1439)
        context.dataStore.edit {
            it[SCHEDULE_ENABLED] = enabled
            it[SCHEDULE_START] = startMinutes
            it[SCHEDULE_END] = endMinutes
        }
    }

    suspend fun updateAutoSync(enabled: Boolean, wifiOnly: Boolean) {
        context.dataStore.edit {
            it[AUTO_SYNC] = enabled
            it[WIFI_ONLY] = wifiOnly
        }
    }

    suspend fun completeOnboarding() {
        context.dataStore.edit { it[ONBOARDING_COMPLETE] = true }
    }

    suspend fun clearSensitiveSettings() {
        context.dataStore.edit { it.remove(ENCRYPTED_TOKEN) }
        tokenCipher.deleteKey()
    }

    private fun normalizeServerUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val uri = URI(trimmed)
        require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http")) {
            if (BuildConfig.DEBUG) {
                "HTTP 또는 HTTPS 서버 주소가 필요합니다"
            } else {
                "HTTPS 서버 주소가 필요합니다"
            }
        }
        require(!uri.host.isNullOrBlank()) { "올바른 서버 주소가 아닙니다" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "인증정보·쿼리·fragment가 없는 기본 주소를 입력하세요"
        }
        return trimmed
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val USER_ID = stringPreferencesKey("user_id")
        val ENCRYPTED_TOKEN = stringPreferencesKey("encrypted_token")
        val CHUNK_MINUTES = intPreferencesKey("chunk_minutes")
        val SCHEDULE_ENABLED = booleanPreferencesKey("schedule_enabled")
        val SCHEDULE_START = intPreferencesKey("schedule_start_minutes")
        val SCHEDULE_END = intPreferencesKey("schedule_end_minutes")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    }
}
