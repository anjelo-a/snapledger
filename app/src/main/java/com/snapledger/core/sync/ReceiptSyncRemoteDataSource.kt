package com.snapledger.core.sync

import com.snapledger.core.network.api.ReceiptSyncApiService
import com.snapledger.core.network.mapper.toDomain
import com.snapledger.core.network.mapper.toDto

class ReceiptSyncRemoteDataSource(
    private val apiService: ReceiptSyncApiService,
    private val ownerKey: String?,
) {
    suspend fun push(request: ReceiptSyncPushRequest): ReceiptSyncPushResponse {
        return apiService.push(ownerKey = ownerKey, request = request.toDto()).toDomain()
    }

    suspend fun pull(cursor: String = INITIAL_RECEIPT_SYNC_CURSOR): ReceiptSyncPullResponse {
        return apiService.pull(ownerKey = ownerKey, cursor = cursor).toDomain()
    }

    companion object {
        fun create(
            ownerKey: String?,
            apiService: ReceiptSyncApiService = ReceiptSyncApiService.create(),
        ): ReceiptSyncRemoteDataSource {
            return ReceiptSyncRemoteDataSource(
                apiService = apiService,
                ownerKey = ownerKey,
            )
        }
    }
}
