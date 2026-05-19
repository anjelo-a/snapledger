package com.snapledger.feature.settings.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snapledger.core.profile.AccountMode
import com.snapledger.core.profile.ProfileRepository
import com.snapledger.core.profile.toProfileSession
import com.snapledger.core.sync.ReceiptSyncWorker
import com.snapledger.feature.review.domain.LocalFirstReviewRepository
import com.snapledger.feature.review.data.ReviewLocalDatabase
import com.snapledger.feature.account.data.GoogleIdentityClient
import com.snapledger.feature.settings.data.AccountDeletionService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.work.WorkManager

data class SettingsUiState(
    val userName: String = "",
    val email: String? = null,
    val accountMode: AccountMode = AccountMode.LOCAL,
    val isAboutDevelopersExpanded: Boolean = false,
    val isSaving: Boolean = false,
)

class SettingsViewModel(
    private val profileRepository: ProfileRepository,
    private val googleIdentityClient: GoogleIdentityClient = GoogleIdentityClient(),
    private val accountDeletionService: AccountDeletionService = AccountDeletionService(),
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            profileRepository.profileFlow.collect { profile ->
                mutableUiState.update { current ->
                    current.copy(
                        userName = profile?.displayName ?: "User",
                        email = profile?.email,
                        accountMode = profile?.accountMode ?: AccountMode.LOCAL,
                        isSaving = false,
                    )
                }
            }
        }
    }

    fun toggleAboutDevelopers() {
        mutableUiState.update {
            it.copy(isAboutDevelopersExpanded = !it.isAboutDevelopersExpanded)
        }
    }

    fun updateDisplayName(newName: String) {
        val trimmedName = newName.trim()
        if (trimmedName.isBlank()) return

        viewModelScope.launch {
            mutableUiState.update { it.copy(isSaving = true) }
            profileRepository.updateDisplayName(trimmedName)
            mutableUiState.update {
                it.copy(
                    userName = trimmedName,
                    isSaving = false,
                )
            }
        }
    }

    fun logOut(context: Context) {
        viewModelScope.launch {
            mutableUiState.update { it.copy(isSaving = true) }
            googleIdentityClient.clearCredentialState(context)
            profileRepository.clearProfile()
        }
    }

    fun deleteAccount(context: Context) {
        viewModelScope.launch {
            mutableUiState.update { it.copy(isSaving = true) }
            val profile = profileRepository.profileFlow.first()
            if (profile == null) {
                mutableUiState.update { it.copy(isSaving = false) }
                return@launch
            }

            val session = profile.toProfileSession()
            if (session.cloudSyncEnabled) {
                session.syncOwnerKey?.let { ownerKey ->
                    accountDeletionService.deleteRemoteAccountData(ownerKey)
                }
                googleIdentityClient.clearCredentialState(context)
            }

            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(
                ReceiptSyncWorker.uniqueWorkName(profile.localProfileId),
            )
            LocalFirstReviewRepository.removeInstance(profile.localProfileId)
            ReviewLocalDatabase.deleteProfileDatabase(
                context = context.applicationContext,
                profileId = profile.localProfileId,
            )
            profileRepository.deleteProfile(profile.localProfileId)
        }
    }

    companion object {
        fun factory(
            profileRepository: ProfileRepository,
            googleIdentityClient: GoogleIdentityClient = GoogleIdentityClient(),
            accountDeletionService: AccountDeletionService = AccountDeletionService(),
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(
                        profileRepository = profileRepository,
                        googleIdentityClient = googleIdentityClient,
                        accountDeletionService = accountDeletionService,
                    ) as T
                }
            }
        }
    }
}