package com.snapledger.feature.account.data

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
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
                "Google Sign-In is not configured yet. Add SNAPLEDGER_GOOGLE_WEB_CLIENT_ID to root or app/local.properties.",
            )
        }
        val activity = context.findActivity()
            ?: return GoogleSignInResult.Failure(
                "Google Sign-In requires an active app screen. Reopen SnapLedger and try again.",
            )

        return try {
            requestGoogleCredential(activity = activity, useExplicitGoogleSignIn = true)
        } catch (_: GetCredentialCancellationException) {
            GoogleSignInResult.Failure("Google Sign-In was cancelled.")
        } catch (_: NoCredentialException) {
            runCatching {
                requestGoogleCredential(activity = activity, useExplicitGoogleSignIn = false)
            }.getOrElse { fallbackError ->
                fallbackError.toGoogleSignInFailure()
            }
        } catch (error: GetCredentialException) {
            error.toGoogleSignInFailure()
        } catch (error: Exception) {
            error.toGoogleSignInFailure()
        }
    }

    suspend fun clearCredentialState(context: Context) {
        runCatching {
            credentialManagerFactory(context).clearCredentialState(ClearCredentialStateRequest())
        }
    }

    private suspend fun requestGoogleCredential(
        activity: Activity,
        useExplicitGoogleSignIn: Boolean,
    ): GoogleSignInResult {
        val option = if (useExplicitGoogleSignIn) {
            GetSignInWithGoogleOption.Builder(serverClientId).build()
        } else {
            GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .build()
        }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        val response = credentialManagerFactory(activity).getCredential(
            context = activity,
            request = request,
        )
        val credential = response.credential
        if (
            credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val claims = googleCredential.idToken.readJwtClaims()
            return GoogleSignInResult.Success(
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
        }
        return GoogleSignInResult.Failure("Google did not return a usable account.")
    }
}

private fun Throwable.toGoogleSignInFailure(): GoogleSignInResult.Failure {
    val message = when (this) {
        is NoCredentialException ->
            "No Google credentials are available on this device. Make sure a Google account is signed in and Google Play services are up to date."
        is GetCredentialCancellationException ->
            "Google Sign-In was cancelled."
        is GetCredentialException -> {
            when {
                this.message?.contains("developer console", ignoreCase = true) == true ->
                    "Google Sign-In is misconfigured in Google Cloud. Recheck the web client ID, Android package name, and SHA fingerprints."
                this.message?.contains("provider", ignoreCase = true) == true ->
                    "Google Sign-In provider is unavailable right now. Update Google Play services and try again."
                else ->
                    this.message ?: "Google Sign-In could not start. Check your Google client ID and device setup."
            }
        }
        else -> this.message ?: "Google Sign-In could not complete. Please try again."
    }
    return GoogleSignInResult.Failure(message)
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
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
