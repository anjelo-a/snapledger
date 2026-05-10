package com.snapledger.feature.review.data

import com.snapledger.core.network.NetworkConfig
import com.snapledger.feature.review.domain.LocalReceiptRecord
import com.snapledger.feature.review.domain.ReviewBackendConfirmClient
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

interface ReceiptConfirmApiService {
    @POST("v1/receipts/confirm")
    suspend fun confirm(
        @Body request: ReceiptConfirmRequestDto,
    )

    companion object {
        fun create(
            baseUrl: String = NetworkConfig.safeBackendBaseUrl,
            moshi: Moshi = Moshi.Builder().build(),
        ): ReceiptConfirmApiService {
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(ReceiptConfirmApiService::class.java)
        }
    }
}

class NetworkReceiptConfirmClient(
    private val apiService: ReceiptConfirmApiService = ReceiptConfirmApiService.create(),
) : ReviewBackendConfirmClient {
    override suspend fun confirm(record: LocalReceiptRecord) {
        apiService.confirm(record.toConfirmRequestDto())
    }
}

@JsonClass(generateAdapter = true)
data class ReceiptConfirmRequestDto(
    val source: String,
    val merchant: String,
    @Json(name = "expense_date")
    val expenseDate: String,
    @Json(name = "total_amount")
    val totalAmount: String,
    val currency: String,
    val items: List<ReceiptConfirmItemDto>,
)

@JsonClass(generateAdapter = true)
data class ReceiptConfirmItemDto(
    val name: String,
    val amount: String,
)

private fun LocalReceiptRecord.toConfirmRequestDto(): ReceiptConfirmRequestDto {
    return ReceiptConfirmRequestDto(
        source = "scan",
        merchant = merchant,
        expenseDate = expenseDate,
        totalAmount = totalAmountRaw,
        currency = "PHP",
        items = items.mapNotNull { item ->
            val amount = item.amountRaw?.trim()
            val description = item.description.trim()
            if (description.isBlank() || amount.isNullOrBlank()) {
                null
            } else {
                ReceiptConfirmItemDto(
                    name = description,
                    amount = amount,
                )
            }
        },
    )
}
