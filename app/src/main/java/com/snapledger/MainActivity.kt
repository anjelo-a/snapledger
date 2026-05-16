package com.snapledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.snapledger.core.profile.DataStoreProfileRepository
import com.snapledger.core.profile.ProfileRepository
import com.snapledger.core.profile.UserProfile
import com.snapledger.feature.account.ui.AccountSetupRoute
import com.snapledger.feature.account.vm.AccountSetupViewModel
import com.snapledger.ui.AppHomeScreen
import kotlinx.coroutines.launch
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                scrim = AndroidColor.TRANSPARENT,
                darkScrim = AndroidColor.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                scrim = AndroidColor.TRANSPARENT,
                darkScrim = AndroidColor.TRANSPARENT
            )
        )

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val profileRepository = remember {
                        DataStoreProfileRepository.getInstance(applicationContext)
                    }
                    SnapLedgerAppRoot(
                        profileRepository = profileRepository,
                        onDisplayNameChange = { displayName ->
                            lifecycleScope.launch {
                                profileRepository.updateDisplayName(displayName)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SnapLedgerAppRoot(
    profileRepository: ProfileRepository,
    onDisplayNameChange: (String) -> Unit,
) {
    val gateState = remember { mutableStateOf<ProfileGateState>(ProfileGateState.Loading) }
    val navController = rememberNavController()

    LaunchedEffect(profileRepository) {
        profileRepository.profileFlow.collect { profile ->
            gateState.value = if (profile == null) {
                ProfileGateState.Missing
            } else {
                ProfileGateState.Ready(profile)
            }
        }
    }

    when (val state = gateState.value) {
        ProfileGateState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        ProfileGateState.Missing -> {
            val accountSetupViewModel: AccountSetupViewModel = viewModel(
                factory = AccountSetupViewModel.factory(profileRepository),
            )
            AccountSetupRoute(viewModel = accountSetupViewModel)
        }

        is ProfileGateState.Ready -> {
            AppHomeScreen(
                navController = navController,
                profile = state.profile,
                onDisplayNameChange = onDisplayNameChange,
            )
        }
    }
}

private sealed interface ProfileGateState {
    data object Loading : ProfileGateState
    data object Missing : ProfileGateState
    data class Ready(val profile: UserProfile) : ProfileGateState
}
