package com.snapledger.feature.account.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snapledger.core.profile.ProfileRepository
import com.snapledger.core.profile.UserProfile
import com.snapledger.feature.account.data.GoogleIdentityClient
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val profileRepository: ProfileRepository,
    private val googleIdentityClient: GoogleIdentityClient = GoogleIdentityClient(),
) : ViewModel() {
    val profile: StateFlow<UserProfile?> = profileRepository.profileFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    fun updateDisplayName(displayName: String) {
        viewModelScope.launch {
            profileRepository.updateDisplayName(displayName)
        }
    }

    fun signOutGoogle(context: Context) {
        viewModelScope.launch {
            googleIdentityClient.clearCredentialState(context)
            profileRepository.signOutGoogle()
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
                    return ProfileViewModel(
                        profileRepository = profileRepository,
                        googleIdentityClient = googleIdentityClient,
                    ) as T
                }
            }
        }
    }
}
