package com.snapledger.feature.settings.data

import com.snapledger.core.network.NetworkConfig
import com.snapledger.core.network.SnapLedgerHttpClient
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.DELETE
import retrofit2.http.Header

interface SyncAccountApiService {
    @DELETE("v1/sync/account")
    suspend fun deleteAccount(
        @Header("x-sync-owner") ownerKey: String,
    ): SyncAccountDeleteResponseDto

    companion object {
        fun create(
            baseUrl: String = NetworkConfig.safeBackendBaseUrl,
            moshi: Moshi = Moshi.Builder().build(),
        ): SyncAccountApiService {
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(SnapLedgerHttpClient.builder().build())
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(SyncAccountApiService::class.java)
        }
    }
}

class AccountDeletionService(
    private val apiService: SyncAccountApiService = SyncAccountApiService.create(),
) {
    suspend fun deleteRemoteAccountData(ownerKey: String) {
        apiService.deleteAccount(ownerKey)
    }
}

@JsonClass(generateAdapter = true)
data class SyncAccountDeleteResponseDto(
    @Json(name = "deleted_expenses")
    val deletedExpenses: Int,
    @Json(name = "deleted_logs")
    val deletedLogs: Int,
)
