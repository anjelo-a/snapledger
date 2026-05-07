package com.snapledger.feature.scan.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.snapledger.core.network.NetworkConfig
import com.snapledger.feature.scan.domain.CapturedImageMetadata
import com.snapledger.feature.scan.domain.ParsedMoneyCandidate
import com.snapledger.feature.scan.domain.ParsedReceiptCandidate
import com.snapledger.feature.scan.domain.ParsedReceiptItemCandidate
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.File
import java.math.BigDecimal
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

interface ReceiptProcessApiService {
    @POST("v1/receipts/process")
    suspend fun process(@Body request: ReceiptProcessRequestDto): ReceiptProcessResponseDto
}

sealed interface RemoteProcessResult {
    data class Success(val candidate: ParsedReceiptCandidate) : RemoteProcessResult
    data class RateLimited(val message: String) : RemoteProcessResult
    data class Failure(val message: String) : RemoteProcessResult
}

interface ReceiptProcessClient {
    suspend fun processReceipt(capturedImage: CapturedImageMetadata): RemoteProcessResult
}

class ReceiptProcessService(
    private val context: Context,
    private val api: ReceiptProcessApiService = createApi(),
) : ReceiptProcessClient {
    override suspend fun processReceipt(capturedImage: CapturedImageMetadata): RemoteProcessResult {
        if (!isNetworkAvailable()) {
            return RemoteProcessResult.Failure("offline")
        }
        return try {
            val imageBytes = File(capturedImage.absolutePath).readBytes()
            val payload = ReceiptProcessRequestDto(
                imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP),
            )
            val response = api.process(payload)
            RemoteProcessResult.Success(response.toDomain())
        } catch (error: HttpException) {
            if (error.code() == 429) {
                return RemoteProcessResult.RateLimited(
                    "Scan service is busy right now. Please wait a bit and try again.",
                )
            }
            RemoteProcessResult.Failure(error.message ?: "Receipt processing failed.")
        } catch (error: Exception) {
            RemoteProcessResult.Failure(error.message ?: "Receipt processing failed.")
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private fun createApi(
            baseUrl: String = NetworkConfig.safeBackendBaseUrl,
            moshi: Moshi = Moshi.Builder().build(),
        ): ReceiptProcessApiService {
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(ReceiptProcessApiService::class.java)
        }
    }
}

@JsonClass(generateAdapter = true)
data class ReceiptProcessRequestDto(
    @Json(name = "image_base64")
    val imageBase64: String? = null,
)

@JsonClass(generateAdapter = true)
data class ReceiptProcessResponseDto(
    val merchant: String? = null,
    @Json(name = "expense_date")
    val expenseDate: String? = null,
    @Json(name = "total_amount")
    val totalAmount: String? = null,
    val items: List<ReceiptProcessItemDto> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ReceiptProcessItemDto(
    val name: String,
    val amount: String,
)

private fun ReceiptProcessResponseDto.toDomain(): ParsedReceiptCandidate {
    return ParsedReceiptCandidate(
        merchant = merchant,
        expenseDate = expenseDate,
        totalAmount = totalAmount?.let { raw ->
            val minor = try {
                BigDecimal(raw).movePointRight(2).longValueExact()
            } catch (_: Exception) {
                null
            }
            minor?.let { ParsedMoneyCandidate(rawText = raw, amountMinor = it) }
        },
        items = items.mapNotNull { item ->
            val minor = try {
                BigDecimal(item.amount).movePointRight(2).longValueExact()
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null
            ParsedReceiptItemCandidate(
                description = item.name,
                amount = ParsedMoneyCandidate(rawText = item.amount, amountMinor = minor),
            )
        },
        warnings = warnings,
    )
}
