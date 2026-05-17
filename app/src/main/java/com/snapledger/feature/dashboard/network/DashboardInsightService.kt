package com.snapledger.feature.dashboard.network

import com.snapledger.core.network.NetworkConfig
import com.snapledger.core.network.SnapLedgerHttpClient
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.util.concurrent.TimeUnit
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

interface DashboardInsightApiService {
    @POST("v1/insights/generate")
    suspend fun generate(@Body request: InsightGenerateRequestDto): InsightGenerateResponseDto
}

sealed interface DashboardInsightResult {
    data class Success(val text: String, val actionTip: String?) : DashboardInsightResult
    data class Failure(val message: String) : DashboardInsightResult
}

interface DashboardInsightClient {
    suspend fun generate(period: String = "monthly"): DashboardInsightResult
}

class DashboardInsightService(
    private val api: DashboardInsightApiService = createApi(),
) : DashboardInsightClient {
    override suspend fun generate(period: String): DashboardInsightResult {
        return try {
            val response = api.generate(InsightGenerateRequestDto(period = period))
            DashboardInsightResult.Success(
                text = response.text,
                actionTip = response.actionTip,
            )
        } catch (error: Exception) {
            DashboardInsightResult.Failure(error.message ?: "Insight generation unavailable.")
        }
    }

    companion object {
        private fun createApi(
            baseUrl: String = NetworkConfig.safeBackendBaseUrl,
            moshi: Moshi = Moshi.Builder().build(),
        ): DashboardInsightApiService {
            val httpClient = SnapLedgerHttpClient.builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(12, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(httpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(DashboardInsightApiService::class.java)
        }
    }
}

@JsonClass(generateAdapter = true)
data class InsightGenerateRequestDto(
    val period: String,
)

@JsonClass(generateAdapter = true)
data class InsightGenerateResponseDto(
    val text: String,
    @Json(name = "action_tip")
    val actionTip: String? = null,
)
