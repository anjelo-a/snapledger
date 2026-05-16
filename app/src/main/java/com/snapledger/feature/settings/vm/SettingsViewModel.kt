package com.snapledger.feature.settings.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snapledger.core.profile.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val userName: String = "",
    val isDeveloperToolsExpanded: Boolean = false,
    val isSaving: Boolean = false
)

class SettingsViewModel(
    private val profileRepository: ProfileRepository
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = mutableUiState.asStateFlow()

    init {
        // Load the initial profile data when the settings screen opens.
        // ADAPT THIS: Change 'getProfileFlow' to whatever your repository uses to expose the current profile.
        viewModelScope.launch {
            /* Example if your repo exposes a flow:
            profileRepository.getProfileFlow().collect { profile ->
                mutableUiState.update { it.copy(userName = profile?.displayName ?: "User") }
            }
            */

            // Temporary fallback until you link your exact repo method:
            mutableUiState.update { it.copy(userName = "Loaded From Repo") }
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

            // ADAPT THIS: Call your repository to update the name in local storage/database
            profileRepository.updateDisplayName(trimmedName)

            mutableUiState.update {
                it.copy(
                    userName = trimmedName,
                    isSaving = false
                )
            }
        }
    }

    companion object {
        fun factory(
            profileRepository: ProfileRepository
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SettingsViewModel(
                        profileRepository = profileRepository
                    ) as T
                }
            }
        }
    }
}