package com.snapledger.feature.dashboard.network

import com.snapledger.core.network.NetworkConfig
import com.snapledger.core.network.SnapLedgerHttpClient
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import retrofit2.HttpException
import java.util.concurrent.TimeUnit
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

interface DashboardInsightApiService {
    @POST("v1/insights/generate")
    suspend fun generate(@Body request: InsightGenerateRequestDto): InsightGenerateResponseDto

    @POST("v1/insights/chat")
    suspend fun chat(@Body request: InsightChatRequestDto): InsightChatResponseDto
}

sealed interface DashboardInsightResult {
    data class Success(val text: String, val actionTip: String?) : DashboardInsightResult
    data class Failure(val message: String) : DashboardInsightResult
}

sealed interface DashboardInsightChatResult {
    data class Success(
        val status: String,
        val answer: String,
        val actionTip: String?,
        val questionLabel: String,
        val promptSource: String,
        val warnings: List<String>,
    ) : DashboardInsightChatResult

    data class Failure(val message: String) : DashboardInsightChatResult
}

interface DashboardInsightClient {
    suspend fun generate(period: String = "monthly"): DashboardInsightResult
    suspend fun chat(
        period: String = "monthly",
        templateKey: String? = null,
        question: String? = null,
    ): DashboardInsightChatResult
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

    override suspend fun chat(
        period: String,
        templateKey: String?,
        question: String?,
    ): DashboardInsightChatResult {
        return try {
            val response = api.chat(
                InsightChatRequestDto(
                    period = period,
                    templateKey = templateKey,
                    question = question,
                )
            )
            DashboardInsightChatResult.Success(
                status = response.status,
                answer = response.result.answer,
                actionTip = response.result.actionTip,
                questionLabel = response.result.questionLabel,
                promptSource = response.result.promptSource,
                warnings = response.warnings,
            )
        } catch (error: HttpException) {
            if (error.code() == 404) {
                fallbackFromLegacyBackend(
                    period = period,
                    templateKey = templateKey,
                    question = question,
                )
            } else {
                DashboardInsightChatResult.Failure(error.message ?: "AI insights are unavailable.")
            }
        } catch (error: Exception) {
            DashboardInsightChatResult.Failure(error.message ?: "AI insights are unavailable.")
        }
    }

    private suspend fun fallbackFromLegacyBackend(
        period: String,
        templateKey: String?,
        question: String?,
    ): DashboardInsightChatResult {
        val normalizedQuestion = question?.trim().orEmpty()
        if (looksLikeBlockedBudgetMutation(normalizedQuestion)) {
            return DashboardInsightChatResult.Success(
                status = "blocked",
                answer = "I can explain your current spending and budget status, but I can't set, change, or bypass budgets from AI insights.",
                actionTip = "Open the Budget screen to make budget changes directly.",
                questionLabel = "Blocked request",
                promptSource = "guardrail",
                warnings = listOf("budget_guardrail_enforced", "legacy_backend_chat_unavailable"),
            )
        }

        return when (val generated = generate(period)) {
            is DashboardInsightResult.Success -> DashboardInsightChatResult.Success(
                status = "partial",
                answer = generated.text,
                actionTip = listOfNotNull(
                    generated.actionTip,
                    "Chat follow-ups need the newer backend deployment.",
                ).joinToString(" "),
                questionLabel = templateKey?.replace('_', ' ') ?: "Current insight",
                promptSource = if (templateKey != null) "template" else "custom",
                warnings = listOf("legacy_backend_chat_unavailable"),
            )

            is DashboardInsightResult.Failure -> DashboardInsightChatResult.Failure(
                generated.message
            )
        }
    }

    private fun looksLikeBlockedBudgetMutation(question: String): Boolean {
        if (question.isBlank()) return false
        val blockedTerms = listOf(
            "ignore previous",
            "system prompt",
            "developer prompt",
            "api key",
            "token",
            "secret",
            "bypass",
            "override",
            "delete",
            "update",
            "edit",
            "change",
            "set",
            "increase",
            "decrease",
            "move",
            "transfer",
            "reallocate",
        )
        return blockedTerms.any { question.contains(it, ignoreCase = true) }
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

@JsonClass(generateAdapter = true)
data class InsightChatRequestDto(
    val period: String,
    @Json(name = "template_key")
    val templateKey: String? = null,
    val question: String? = null,
)

@JsonClass(generateAdapter = true)
data class InsightChatResponseDto(
    @Json(name = "schema_version")
    val schemaVersion: String,
    @Json(name = "agent_name")
    val agentName: String,
    @Json(name = "task_id")
    val taskId: String,
    val status: String,
    val result: InsightChatResultDto,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class InsightChatResultDto(
    val answer: String,
    @Json(name = "action_tip")
    val actionTip: String? = null,
    @Json(name = "question_label")
    val questionLabel: String,
    @Json(name = "prompt_source")
    val promptSource: String,
    @Json(name = "suggested_template_keys")
    val suggestedTemplateKeys: List<String> = emptyList(),
)
