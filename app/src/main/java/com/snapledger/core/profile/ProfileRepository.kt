package com.snapledger.core.profile

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

interface ProfileRepository {
    val profileFlow: Flow<UserProfile?>

    suspend fun createLocalProfile(displayName: String): UserProfile

    suspend fun createGoogleProfile(
        candidate: GoogleProfileCandidate,
        displayName: String,
    ): UserProfile

    suspend fun updateDisplayName(displayName: String)

    suspend fun signOutGoogle()
}

private val Context.snapLedgerProfileDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "snapledger_profile",
)

class DataStoreProfileRepository(
    private val dataStore: DataStore<Preferences>,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : ProfileRepository {
    override val profileFlow: Flow<UserProfile?> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences -> preferences.toProfileOrNull() }

    override suspend fun createLocalProfile(displayName: String): UserProfile {
        val profile = UserProfile(
            localProfileId = idFactory(),
            accountMode = AccountMode.LOCAL,
            displayName = displayName.cleanDisplayName(),
            googleSubject = null,
            email = null,
            photoUrl = null,
            createdAtMillis = clockMillis(),
        )
        dataStore.writeProfile(profile)
        return profile
    }

    override suspend fun createGoogleProfile(
        candidate: GoogleProfileCandidate,
        displayName: String,
    ): UserProfile {
        val profile = UserProfile(
            localProfileId = idFactory(),
            accountMode = AccountMode.GOOGLE,
            displayName = displayName.cleanDisplayName(),
            googleSubject = candidate.googleSubject,
            email = candidate.email,
            photoUrl = candidate.photoUrl,
            createdAtMillis = clockMillis(),
        )
        dataStore.writeProfile(profile)
        return profile
    }

    override suspend fun updateDisplayName(displayName: String) {
        dataStore.edit { preferences ->
            preferences[DISPLAY_NAME] = displayName.cleanDisplayName()
        }
    }

    override suspend fun signOutGoogle() {
        dataStore.edit { preferences ->
            preferences[ACCOUNT_MODE] = AccountMode.LOCAL.name
            preferences.remove(GOOGLE_SUBJECT)
            preferences.remove(EMAIL)
            preferences.remove(PHOTO_URL)
        }
    }

    private fun Preferences.toProfileOrNull(): UserProfile? {
        val localProfileId = this[LOCAL_PROFILE_ID] ?: return null
        val displayName = this[DISPLAY_NAME] ?: return null
        val createdAtMillis = this[CREATED_AT_MILLIS] ?: return null
        val accountMode = runCatching {
            AccountMode.valueOf(this[ACCOUNT_MODE] ?: AccountMode.LOCAL.name)
        }.getOrDefault(AccountMode.LOCAL)

        return UserProfile(
            localProfileId = localProfileId,
            accountMode = accountMode,
            displayName = displayName,
            googleSubject = this[GOOGLE_SUBJECT],
            email = this[EMAIL],
            photoUrl = this[PHOTO_URL],
            createdAtMillis = createdAtMillis,
        )
    }

    private suspend fun DataStore<Preferences>.writeProfile(profile: UserProfile) {
        edit { preferences ->
            preferences[LOCAL_PROFILE_ID] = profile.localProfileId
            preferences[ACCOUNT_MODE] = profile.accountMode.name
            preferences[DISPLAY_NAME] = profile.displayName
            preferences[CREATED_AT_MILLIS] = profile.createdAtMillis
            preferences.writeOptional(GOOGLE_SUBJECT, profile.googleSubject)
            preferences.writeOptional(EMAIL, profile.email)
            preferences.writeOptional(PHOTO_URL, profile.photoUrl)
        }
    }

    private fun MutablePreferences.writeOptional(
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value.isNullOrBlank()) {
            remove(key)
        } else {
            this[key] = value
        }
    }

    companion object {
        fun getInstance(context: Context): DataStoreProfileRepository {
            return DataStoreProfileRepository(context.snapLedgerProfileDataStore)
        }

        private val LOCAL_PROFILE_ID = stringPreferencesKey("local_profile_id")
        private val ACCOUNT_MODE = stringPreferencesKey("account_mode")
        private val DISPLAY_NAME = stringPreferencesKey("display_name")
        private val GOOGLE_SUBJECT = stringPreferencesKey("google_subject")
        private val EMAIL = stringPreferencesKey("email")
        private val PHOTO_URL = stringPreferencesKey("photo_url")
        private val CREATED_AT_MILLIS = longPreferencesKey("created_at_millis")
    }
}

private fun String.cleanDisplayName(): String {
    return trim().ifBlank { "User" }.take(80)
}
