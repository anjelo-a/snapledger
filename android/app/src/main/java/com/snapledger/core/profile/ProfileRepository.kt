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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface ProfileRepository {
    val profileFlow: Flow<UserProfile?>
    val savedProfilesFlow: Flow<List<SavedProfileOption>>

    suspend fun createLocalProfile(displayName: String): UserProfile

    suspend fun createGoogleProfile(
        candidate: GoogleProfileCandidate,
        displayName: String,
    ): UserProfile

    suspend fun updateDisplayName(displayName: String)

    suspend fun signOutGoogle()

    suspend fun clearProfile()

    suspend fun activateProfile(localProfileId: String): UserProfile?

    suspend fun getProfile(localProfileId: String): UserProfile?

    suspend fun deleteProfile(localProfileId: String)
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

    override val savedProfilesFlow: Flow<List<SavedProfileOption>> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences -> preferences.toSavedProfiles() }

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
        val profile = dataStore.data.first().toStoredProfiles()
            .firstOrNull { it.googleSubject == candidate.googleSubject }
            ?.copy(
                accountMode = AccountMode.GOOGLE,
                displayName = displayName.cleanDisplayName(),
                googleSubject = candidate.googleSubject,
                email = candidate.email,
                photoUrl = candidate.photoUrl,
            )
            ?: UserProfile(
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
            val currentProfile = preferences.toProfileOrNull()
            if (currentProfile != null) {
                val downgraded = currentProfile.copy(
                    accountMode = AccountMode.LOCAL,
                    googleSubject = null,
                    email = null,
                    photoUrl = null,
                )
                preferences.writeCurrentProfile(downgraded)
                preferences.writeSavedProfiles(
                    preferences.toStoredProfiles().upsert(downgraded),
                )
            }
        }
    }

    override suspend fun clearProfile() {
        dataStore.edit { preferences ->
            preferences.remove(CURRENT_PROFILE_ID)
            preferences.remove(LOCAL_PROFILE_ID)
            preferences.remove(ACCOUNT_MODE)
            preferences.remove(DISPLAY_NAME)
            preferences.remove(GOOGLE_SUBJECT)
            preferences.remove(EMAIL)
            preferences.remove(PHOTO_URL)
            preferences.remove(CREATED_AT_MILLIS)
        }
    }

    override suspend fun activateProfile(localProfileId: String): UserProfile? {
        var activatedProfile: UserProfile? = null
        dataStore.edit { preferences ->
            activatedProfile = preferences.toStoredProfiles()
                .firstOrNull { it.localProfileId == localProfileId }
            activatedProfile?.let { preferences.writeCurrentProfile(it) }
        }
        return activatedProfile
    }

    override suspend fun getProfile(localProfileId: String): UserProfile? {
        return dataStore.data.first()
            .toStoredProfiles()
            .firstOrNull { it.localProfileId == localProfileId }
    }

    override suspend fun deleteProfile(localProfileId: String) {
        dataStore.edit { preferences ->
            val remainingProfiles = preferences.toStoredProfiles()
                .filterNot { it.localProfileId == localProfileId }
            val currentProfile = preferences.toProfileOrNull()
            if (currentProfile?.localProfileId == localProfileId) {
                preferences.remove(CURRENT_PROFILE_ID)
                preferences.remove(LOCAL_PROFILE_ID)
                preferences.remove(ACCOUNT_MODE)
                preferences.remove(DISPLAY_NAME)
                preferences.remove(GOOGLE_SUBJECT)
                preferences.remove(EMAIL)
                preferences.remove(PHOTO_URL)
                preferences.remove(CREATED_AT_MILLIS)
            }
            if (remainingProfiles.isEmpty()) {
                preferences.remove(SAVED_PROFILES)
            } else {
                preferences.writeSavedProfiles(remainingProfiles)
            }
        }
    }

    private fun Preferences.toProfileOrNull(): UserProfile? {
        val localProfileId = this[CURRENT_PROFILE_ID] ?: this[LOCAL_PROFILE_ID] ?: return null
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
            preferences.writeCurrentProfile(profile)
            preferences.writeSavedProfiles(
                preferences.toStoredProfiles().upsert(profile),
            )
        }
    }

    private fun MutablePreferences.writeCurrentProfile(profile: UserProfile) {
        this[CURRENT_PROFILE_ID] = profile.localProfileId
        this[LOCAL_PROFILE_ID] = profile.localProfileId
        this[ACCOUNT_MODE] = profile.accountMode.name
        this[DISPLAY_NAME] = profile.displayName
        this[CREATED_AT_MILLIS] = profile.createdAtMillis
        writeOptional(GOOGLE_SUBJECT, profile.googleSubject)
        writeOptional(EMAIL, profile.email)
        writeOptional(PHOTO_URL, profile.photoUrl)
    }

    private fun Preferences.toSavedProfiles(): List<SavedProfileOption> {
        return toStoredProfiles().map { profile ->
            SavedProfileOption(
                localProfileId = profile.localProfileId,
                accountMode = profile.accountMode,
                displayName = profile.displayName,
                email = profile.email,
            )
        }
    }

    private fun Preferences.toStoredProfiles(): List<UserProfile> {
        val encodedProfiles = this[SAVED_PROFILES] ?: return toProfileOrNull()?.let(::listOf).orEmpty()
        return runCatching {
            encodedProfiles
                .split(PROFILE_SEPARATOR)
                .filter { it.isNotBlank() }
                .mapNotNull { encodedProfile -> encodedProfile.decodeProfileOrNull() }
        }.getOrElse {
            toProfileOrNull()?.let(::listOf).orEmpty()
        }
    }

    private fun MutablePreferences.writeSavedProfiles(profiles: List<UserProfile>) {
        this[SAVED_PROFILES] = profiles.joinToString(PROFILE_SEPARATOR) { it.encodeProfile() }
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
        private val CURRENT_PROFILE_ID = stringPreferencesKey("current_profile_id")
        private val ACCOUNT_MODE = stringPreferencesKey("account_mode")
        private val DISPLAY_NAME = stringPreferencesKey("display_name")
        private val GOOGLE_SUBJECT = stringPreferencesKey("google_subject")
        private val EMAIL = stringPreferencesKey("email")
        private val PHOTO_URL = stringPreferencesKey("photo_url")
        private val CREATED_AT_MILLIS = longPreferencesKey("created_at_millis")
        private val SAVED_PROFILES = stringPreferencesKey("saved_profiles")
        private const val PROFILE_SEPARATOR = "\u001E"
        private const val FIELD_SEPARATOR = "\u001F"
    }
}

private fun String.cleanDisplayName(): String {
    return trim().ifBlank { "User" }.take(80)
}

private fun List<UserProfile>.upsert(profile: UserProfile): List<UserProfile> {
    return listOf(profile) + filterNot { it.localProfileId == profile.localProfileId }
}

private fun UserProfile.encodeProfile(): String {
    return listOf(
        localProfileId,
        accountMode.name,
        displayName,
        googleSubject.orEmpty(),
        email.orEmpty(),
        photoUrl.orEmpty(),
        createdAtMillis.toString(),
    ).joinToString("\u001F") { it.escapeProfileField() }
}

private fun String.decodeProfileOrNull(): UserProfile? {
    val fields = split("\u001F").map { it.unescapeProfileField() }
    if (fields.size != 7) return null
    val localProfileId = fields[0].takeIf { it.isNotBlank() } ?: return null
    val accountMode = runCatching { AccountMode.valueOf(fields[1]) }.getOrDefault(AccountMode.LOCAL)
    val displayName = fields[2].takeIf { it.isNotBlank() } ?: return null
    val createdAtMillis = fields[6].toLongOrNull() ?: return null
    return UserProfile(
        localProfileId = localProfileId,
        accountMode = accountMode,
        displayName = displayName,
        googleSubject = fields[3].ifBlank { null },
        email = fields[4].ifBlank { null },
        photoUrl = fields[5].ifBlank { null },
        createdAtMillis = createdAtMillis,
    )
}

private fun String.escapeProfileField(): String {
    return buildString(length) {
        this@escapeProfileField.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '\u001E' -> append("\\u001E")
                '\u001F' -> append("\\u001F")
                else -> append(char)
            }
        }
    }
}

private fun String.unescapeProfileField(): String {
    return this
        .replace("\\u001F", "\u001F")
        .replace("\\u001E", "\u001E")
        .replace("\\\\", "\\")
}
