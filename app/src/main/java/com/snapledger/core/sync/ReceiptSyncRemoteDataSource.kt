package com.snapledger.core.sync

import com.snapledger.core.network.api.ReceiptSyncApiService
import com.snapledger.core.network.mapper.toDomain
import com.snapledger.core.network.mapper.toDto

class ReceiptSyncRemoteDataSource(
    private val apiService: ReceiptSyncApiService,
) {
    suspend fun push(request: ReceiptSyncPushRequest): ReceiptSyncPushResponse {
        return apiService.push(request.toDto()).toDomain()
    }

    suspend fun pull(cursor: String = INITIAL_RECEIPT_SYNC_CURSOR): ReceiptSyncPullResponse {
        return apiService.pull(cursor).toDomain()
    }

    companion object {
        fun create(
            apiService: ReceiptSyncApiService = ReceiptSyncApiService.create(),
        ): ReceiptSyncRemoteDataSource {
            return ReceiptSyncRemoteDataSource(apiService = apiService)
        }
    }
}
