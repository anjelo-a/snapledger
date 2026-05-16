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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapledger.core.profile.AccountMode
import com.snapledger.core.profile.UserProfile
import com.snapledger.feature.account.vm.ProfileViewModel

@Composable
fun ProfileRoute(
    viewModel: ProfileViewModel,
    modifier: Modifier = Modifier,
) {
    val profile by viewModel.profile.collectAsState()
    val context = LocalContext.current

    ProfileScreen(
        profile = profile,
        onSaveName = viewModel::updateDisplayName,
        onSignOutGoogle = { viewModel.signOutGoogle(context) },
        modifier = modifier,
    )
}

@Composable
private fun ProfileScreen(
    profile: UserProfile?,
    onSaveName: (String) -> Unit,
    onSignOutGoogle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var nameDraft by remember { mutableStateOf("") }

    LaunchedEffect(profile?.displayName) {
        nameDraft = profile?.displayName.orEmpty()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Profile",
            color = Color(0xFF1F1F1F),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Local account",
                    color = Color(0xFF1F1F1F),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (profile?.accountMode == AccountMode.GOOGLE) {
                        "Signed in with Google. Your ledger is still stored locally."
                    } else {
                        "Using a local profile. Your ledger is stored on this device."
                    },
                    color = Color(0xFF616161),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )

                ProfileRow(label = "Mode", value = profile?.accountMode?.name ?: "LOCAL")
                if (profile?.email != null) {
                    ProfileRow(label = "Email", value = profile.email)
                }
                ProfileRow(label = "Profile ID", value = profile?.localProfileId ?: "")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Display name",
                    color = Color(0xFF1F1F1F),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                )
                Button(
                    onClick = { onSaveName(nameDraft) },
                    enabled = nameDraft.isNotBlank() && nameDraft != profile?.displayName,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A86B)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Save name")
                }
            }
        }

        if (profile?.accountMode == AccountMode.GOOGLE) {
            OutlinedButton(
                onClick = onSignOutGoogle,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Sign out of Google")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProfileRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color(0xFF757575),
            fontSize = 13.sp,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value.ifBlank { "-" },
            color = Color(0xFF1F1F1F),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.65f),
        )
    }
}
