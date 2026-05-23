package com.snapledger.feature.account.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapledger.core.profile.AccountMode
import com.snapledger.core.profile.SavedProfileOption
import com.snapledger.feature.account.vm.AccountSetupViewModel

@Composable
fun AccountSetupRoute(
    viewModel: AccountSetupViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    AccountSetupScreen(
        displayName = state.displayName,
        isBusy = state.isBusy,
        message = state.message,
        isConfirmingGoogle = state.pendingGoogleCandidate != null,
        savedProfiles = state.savedProfiles,
        canContinue = state.canContinue,
        onDisplayNameChange = viewModel::updateDisplayName,
        onContinueLocally = viewModel::continueLocally,
        onContinueWithGoogle = { viewModel.startGoogleSignIn(context) },
        onConfirmGoogle = viewModel::confirmGoogleProfile,
        onCancelGoogle = viewModel::cancelGoogleConfirmation,
        onContinueWithSavedProfile = viewModel::continueWithSavedProfile,
        modifier = modifier,
    )
}

@Composable
private fun AccountSetupScreen(
    displayName: String,
    isBusy: Boolean,
    message: String?,
    isConfirmingGoogle: Boolean,
    savedProfiles: List<SavedProfileOption>,
    canContinue: Boolean,
    onDisplayNameChange: (String) -> Unit,
    onContinueLocally: () -> Unit,
    onContinueWithGoogle: () -> Unit,
    onConfirmGoogle: () -> Unit,
    onCancelGoogle: () -> Unit,
    onContinueWithSavedProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeGreen = Color(0xFF00A86B)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "SnapLedger",
            color = Color(0xFF1F1F1F),
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Set up a local profile to keep your ledger on this device.",
            color = Color(0xFF616161),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name") },
            singleLine = true,
            enabled = !isBusy,
            shape = RoundedCornerShape(8.dp),
        )

        if (message != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isConfirmingGoogle) {
            Button(
                onClick = onConfirmGoogle,
                enabled = canContinue,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = activeGreen),
                shape = RoundedCornerShape(8.dp),
            ) {
                BusyButtonContent(isBusy = isBusy, text = "Use Google profile")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCancelGoogle,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Choose another option")
            }
        } else {
            Button(
                onClick = onContinueLocally,
                enabled = canContinue,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = activeGreen),
                shape = RoundedCornerShape(8.dp),
            ) {
                BusyButtonContent(isBusy = isBusy, text = "Continue locally")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onContinueWithGoogle,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Continue with Google")
            }
        }

        if (savedProfiles.isNotEmpty() && !isConfirmingGoogle) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Saved profiles",
                color = Color(0xFF616161),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            savedProfiles.forEach { profile ->
                OutlinedButton(
                    onClick = { onContinueWithSavedProfile(profile.localProfileId) },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(profile.savedProfileLabel())
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Local profiles stay on this device. Google-linked profiles keep a separate ledger and sync it across devices signed in to the same Google account.",
            color = Color(0xFF757575),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp,
        )
    }
}

private fun SavedProfileOption.savedProfileLabel(): String {
    val suffix = when {
        accountMode == AccountMode.GOOGLE && !email.isNullOrBlank() -> " ($email)"
        accountMode == AccountMode.GOOGLE -> " (Google)"
        else -> " (Local)"
    }
    return "Continue as $displayName$suffix"
}

@Composable
private fun BusyButtonContent(
    isBusy: Boolean,
    text: String,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(16.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
        }
        Text(text)
    }
}
