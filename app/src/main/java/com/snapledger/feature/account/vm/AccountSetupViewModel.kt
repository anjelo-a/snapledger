package com.snapledger.feature.account.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snapledger.core.profile.GoogleProfileCandidate
import com.snapledger.core.profile.ProfileRepository
import com.snapledger.core.profile.SavedProfileOption
import com.snapledger.feature.account.data.GoogleIdentityClient
import com.snapledger.feature.account.data.GoogleSignInResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountSetupUiState(
    val displayName: String = "",
    val pendingGoogleCandidate: GoogleProfileCandidate? = null,
    val savedProfiles: List<SavedProfileOption> = emptyList(),
    val isBusy: Boolean = false,
    val message: String? = null,
) {
    val canContinue: Boolean get() = displayName.isNotBlank() && !isBusy
}

class AccountSetupViewModel(
    private val profileRepository: ProfileRepository,
    private val googleIdentityClient: GoogleIdentityClient = GoogleIdentityClient(),
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(AccountSetupUiState())
    val uiState: StateFlow<AccountSetupUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            profileRepository.savedProfilesFlow.collect { savedProfiles ->
                mutableUiState.update { state ->
                    state.copy(savedProfiles = savedProfiles)
                }
            }
        }
    }

    fun updateDisplayName(value: String) {
        mutableUiState.update { state ->
            state.copy(displayName = value.take(80), message = null)
        }
    }

    fun continueLocally() {
        val name = uiState.value.displayName.trim()
        if (name.isBlank()) {
            mutableUiState.update { it.copy(message = "Enter a name to continue.") }
            return
        }
        viewModelScope.launch {
            mutableUiState.update { it.copy(isBusy = true, message = null) }
            profileRepository.createLocalProfile(name)
            mutableUiState.update { it.copy(isBusy = false) }
        }
    }

    fun startGoogleSignIn(context: Context) {
        viewModelScope.launch {
            mutableUiState.update { it.copy(isBusy = true, message = null) }
            when (val result = googleIdentityClient.signIn(context)) {
                is GoogleSignInResult.Success -> {
                    mutableUiState.update { state ->
                        state.copy(
                            displayName = result.candidate.displayName
                                ?: result.candidate.email?.substringBefore("@")
                                ?: state.displayName,
                            pendingGoogleCandidate = result.candidate,
                            isBusy = false,
                        )
                    }
                }

                is GoogleSignInResult.Failure -> {
                    mutableUiState.update {
                        it.copy(isBusy = false, message = result.message)
                    }
                }
            }
        }
    }

    fun confirmGoogleProfile() {
        val candidate = uiState.value.pendingGoogleCandidate ?: return
        val name = uiState.value.displayName.trim()
        if (name.isBlank()) {
            mutableUiState.update { it.copy(message = "Enter a name to continue.") }
            return
        }
        viewModelScope.launch {
            mutableUiState.update { it.copy(isBusy = true, message = null) }
            profileRepository.createGoogleProfile(candidate = candidate, displayName = name)
            mutableUiState.update { it.copy(isBusy = false) }
        }
    }

    fun cancelGoogleConfirmation() {
        mutableUiState.update {
            it.copy(pendingGoogleCandidate = null, message = null)
        }
    }

    fun continueWithSavedProfile(localProfileId: String) {
        viewModelScope.launch {
            mutableUiState.update { it.copy(isBusy = true, message = null) }
            val restored = profileRepository.activateProfile(localProfileId)
            mutableUiState.update {
                it.copy(
                    isBusy = false,
                    message = if (restored == null) "That saved profile is no longer available." else null,
                )
            }
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
                    return AccountSetupViewModel(
                        profileRepository = profileRepository,
                        googleIdentityClient = googleIdentityClient,
                    ) as T
                }
            }
        }
    }
}
