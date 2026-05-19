package com.snapledger.core.profile

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.snapledger.feature.scan.vm.MainDispatcherRule
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreProfileRepositoryTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `creates local profile with generated id and display name`() = runTest {
        val repository = repository(
            idFactory = { "profile-1" },
            clockMillis = { 100L },
        )

        val profile = repository.createLocalProfile(" Mina ")

        assertEquals("profile-1", profile.localProfileId)
        assertEquals(AccountMode.LOCAL, profile.accountMode)
        assertEquals("Mina", profile.displayName)
        assertEquals(100L, profile.createdAtMillis)
        assertNull(profile.googleSubject)
        assertEquals(profile, repository.profileFlow.first())
    }

    @Test
    fun `saves google metadata without a ledger migration requirement`() = runTest {
        val repository = repository(
            idFactory = { "profile-google" },
            clockMillis = { 200L },
        )

        val profile = repository.createGoogleProfile(
            candidate = GoogleProfileCandidate(
                googleSubject = "google-subject-1",
                email = "mina@example.com",
                displayName = "Mina Google",
                photoUrl = "https://example.com/photo.png",
            ),
            displayName = "Mina",
        )

        assertEquals("profile-google", profile.localProfileId)
        assertEquals(AccountMode.GOOGLE, profile.accountMode)
        assertEquals("google-subject-1", profile.googleSubject)
        assertEquals("mina@example.com", profile.email)
        assertEquals("https://example.com/photo.png", profile.photoUrl)
        assertEquals(profile, repository.profileFlow.first())
    }

    @Test
    fun `editing display name preserves local profile id`() = runTest {
        val repository = repository(
            idFactory = { "stable-profile" },
            clockMillis = { 300L },
        )
        repository.createLocalProfile("Mina")

        repository.updateDisplayName("Angel")

        val profile = repository.profileFlow.first()
        assertEquals("stable-profile", profile?.localProfileId)
        assertEquals("Angel", profile?.displayName)
        assertEquals(300L, profile?.createdAtMillis)
    }

    @Test
    fun `google sign out downgrades to local and keeps profile id`() = runTest {
        val repository = repository(
            idFactory = { "profile-keep" },
            clockMillis = { 400L },
        )
        repository.createGoogleProfile(
            candidate = GoogleProfileCandidate(
                googleSubject = "google-subject-1",
                email = "mina@example.com",
                displayName = "Mina Google",
                photoUrl = "https://example.com/photo.png",
            ),
            displayName = "Mina",
        )

        repository.signOutGoogle()

        val profile = repository.profileFlow.first()
        assertEquals("profile-keep", profile?.localProfileId)
        assertEquals(AccountMode.LOCAL, profile?.accountMode)
        assertEquals("Mina", profile?.displayName)
        assertNull(profile?.googleSubject)
        assertNull(profile?.email)
        assertNull(profile?.photoUrl)
    }

    @Test
    fun `same google subject reuses saved profile id instead of creating duplicate`() = runTest {
        var generatedIds = 0
        val repository = repository(
            idFactory = {
                generatedIds += 1
                "profile-$generatedIds"
            },
            clockMillis = { 450L },
        )
        repository.createGoogleProfile(
            candidate = GoogleProfileCandidate(
                googleSubject = "google-subject-1",
                email = "mina@example.com",
                displayName = "Mina One",
                photoUrl = null,
            ),
            displayName = "Mina",
        )
        repository.clearProfile()

        val secondProfile = repository.createGoogleProfile(
            candidate = GoogleProfileCandidate(
                googleSubject = "google-subject-1",
                email = "mina+updated@example.com",
                displayName = "Mina Updated",
                photoUrl = "https://example.com/new-photo.png",
            ),
            displayName = "Mina Updated",
        )

        assertEquals("profile-1", secondProfile.localProfileId)
        assertEquals("mina+updated@example.com", secondProfile.email)
        assertEquals(1, repository.savedProfilesFlow.first().size)
    }

    @Test
    fun `clear profile removes local account and returns setup state`() = runTest {
        val repository = repository(
            idFactory = { "profile-local" },
            clockMillis = { 500L },
        )
        repository.createLocalProfile("Mina")

        repository.clearProfile()

        assertNull(repository.profileFlow.first())
    }

    @Test
    fun `logged out local account remains available in saved profiles`() = runTest {
        val repository = repository(
            idFactory = { "profile-local" },
            clockMillis = { 600L },
        )
        repository.createLocalProfile("Mina")

        repository.clearProfile()

        val savedProfiles = repository.savedProfilesFlow.first()
        assertEquals(1, savedProfiles.size)
        assertEquals("profile-local", savedProfiles.first().localProfileId)
        assertEquals("Mina", savedProfiles.first().displayName)
    }

    @Test
    fun `can reactivate saved local profile after logout`() = runTest {
        val repository = repository(
            idFactory = { "profile-local" },
            clockMillis = { 700L },
        )
        repository.createLocalProfile("Mina")
        repository.clearProfile()

        val profile = repository.activateProfile("profile-local")

        assertEquals("profile-local", profile?.localProfileId)
        assertEquals(AccountMode.LOCAL, profile?.accountMode)
        assertEquals("Mina", repository.profileFlow.first()?.displayName)
    }

    private fun repository(
        idFactory: () -> String,
        clockMillis: () -> Long,
    ): DataStoreProfileRepository {
        val file = File(temporaryFolder.newFolder(), "profile.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(mainDispatcherRule.dispatcher),
            produceFile = { file },
        )
        return DataStoreProfileRepository(
            dataStore = dataStore,
            idFactory = idFactory,
            clockMillis = clockMillis,
        )
    }
}
