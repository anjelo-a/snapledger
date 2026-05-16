package com.snapledger.feature.account.data

import android.content.Context
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.snapledger.BuildConfig
import com.snapledger.core.profile.GoogleProfileCandidate
import org.json.JSONObject

sealed interface GoogleSignInResult {
    data class Success(val candidate: GoogleProfileCandidate) : GoogleSignInResult
    data class Failure(val message: String) : GoogleSignInResult
}

class GoogleIdentityClient(
    private val credentialManagerFactory: (Context) -> CredentialManager = CredentialManager::create,
    private val serverClientId: String = BuildConfig.GOOGLE_SIGN_IN_SERVER_CLIENT_ID,
) {
    suspend fun signIn(context: Context): GoogleSignInResult {
        if (serverClientId.isBlank()) {
            return GoogleSignInResult.Failure(
                "Google Sign-In is not configured yet. Add SNAPLEDGER_GOOGLE_WEB_CLIENT_ID to local.properties.",
            )
        }

        return try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val response = credentialManagerFactory(context).getCredential(
                context = context,
                request = request,
            )
            val credential = response.credential
            if (
                credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val claims = googleCredential.idToken.readJwtClaims()
                GoogleSignInResult.Success(
                    GoogleProfileCandidate(
                        googleSubject = claims?.optString("sub").orEmpty()
                            .ifBlank { googleCredential.id },
                        email = claims?.optString("email").orEmpty()
                            .ifBlank { googleCredential.id },
                        displayName = googleCredential.displayName
                            ?: claims?.optString("name")?.takeIf(String::isNotBlank),
                        photoUrl = googleCredential.profilePictureUri?.toString()
                            ?: claims?.optString("picture")?.takeIf(String::isNotBlank),
                    ),
                )
            } else {
                GoogleSignInResult.Failure("Google did not return a usable account.")
            }
        } catch (error: Exception) {
            GoogleSignInResult.Failure(error.message ?: "Google Sign-In was cancelled.")
        }
    }

    suspend fun clearCredentialState(context: Context) {
        runCatching {
            credentialManagerFactory(context).clearCredentialState(ClearCredentialStateRequest())
        }
    }
}

private fun String.readJwtClaims(): JSONObject? {
    val payload = split(".").getOrNull(1) ?: return null
    return runCatching {
        val decoded = Base64.decode(
            payload,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        JSONObject(String(decoded, Charsets.UTF_8))
    }.getOrNull()
}
