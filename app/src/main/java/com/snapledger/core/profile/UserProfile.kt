package com.snapledger.core.profile

enum class AccountMode {
    LOCAL,
    GOOGLE,
}

data class UserProfile(
    val localProfileId: String,
    val accountMode: AccountMode,
    val displayName: String,
    val googleSubject: String?,
    val email: String?,
    val photoUrl: String?,
    val createdAtMillis: Long,
)

data class SavedProfileOption(
    val localProfileId: String,
    val accountMode: AccountMode,
    val displayName: String,
    val email: String?,
)

data class GoogleProfileCandidate(
    val googleSubject: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
)
