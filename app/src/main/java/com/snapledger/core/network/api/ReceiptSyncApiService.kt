package com.snapledger.core.network.api

import com.snapledger.core.network.NetworkConfig
import com.snapledger.core.network.dto.ReceiptSyncPullResponseDto
import com.snapledger.core.network.dto.ReceiptSyncPushRequestDto
import com.snapledger.core.network.dto.ReceiptSyncPushResponseDto
import com.squareup.moshi.Moshi
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface ReceiptSyncApiService {
    @POST("v1/sync/push")
    suspend fun push(
        @Body request: ReceiptSyncPushRequestDto,
    ): ReceiptSyncPushResponseDto

    @GET("v1/sync/pull")
    suspend fun pull(
        @Query("cursor") cursor: String,
    ): ReceiptSyncPullResponseDto

    companion object {
        fun create(
            baseUrl: String = NetworkConfig.safeBackendBaseUrl,
            moshi: Moshi = Moshi.Builder().build(),
        ): ReceiptSyncApiService {
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(ReceiptSyncApiService::class.java)
        }
    }
}
