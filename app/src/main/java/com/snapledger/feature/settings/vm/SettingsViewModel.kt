package com.snapledger.feature.settings.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snapledger.core.profile.AccountMode
import com.snapledger.core.profile.ProfileRepository
import com.snapledger.feature.account.data.GoogleIdentityClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val userName: String = "",
    val email: String? = null,
    val accountMode: AccountMode = AccountMode.LOCAL,
    val isDeveloperToolsExpanded: Boolean = false,
    val isSaving: Boolean = false,
)

class SettingsViewModel(
    private val profileRepository: ProfileRepository,
    private val googleIdentityClient: GoogleIdentityClient = GoogleIdentityClient(),
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

    fun toggleDeveloperTools() {
        mutableUiState.update {
            it.copy(isDeveloperToolsExpanded = !it.isDeveloperToolsExpanded)
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

    companion object {
        fun factory(
            profileRepository: ProfileRepository,
            googleIdentityClient: GoogleIdentityClient = GoogleIdentityClient(),
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(
                        profileRepository = profileRepository,
                        googleIdentityClient = googleIdentityClient,
                    ) as T
                }
            }
        }
    }
}
