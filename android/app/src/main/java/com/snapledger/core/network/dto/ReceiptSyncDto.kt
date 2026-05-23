package com.snapledger.core.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ReceiptSyncPushRequestDto(
    val mutations: List<ReceiptSyncPushMutationDto>,
)

@JsonClass(generateAdapter = true)
data class ReceiptSyncPushMutationDto(
    @Json(name = "idempotency_key")
    val idempotencyKey: String,
    val entity: String,
    val operation: String,
    @Json(name = "occurred_at")
    val occurredAt: String,
    val payload: Map<String, Any?>,
)

@JsonClass(generateAdapter = true)
data class ReceiptSyncPushResponseDto(
    val accepted: Int,
    val rejected: Int,
    val results: List<ReceiptSyncPushResultDto>,
)

@JsonClass(generateAdapter = true)
data class ReceiptSyncPushResultDto(
    @Json(name = "idempotency_key")
    val idempotencyKey: String,
    val entity: String,
    val operation: String,
    val status: String,
    val code: String? = null,
    val message: String? = null,
    @Json(name = "entity_id")
    val entityId: String? = null,
)

@JsonClass(generateAdapter = true)
data class ReceiptSyncPullResponseDto(
    val cursor: String,
    @Json(name = "has_more")
    val hasMore: Boolean,
    val changes: List<ReceiptSyncPullChangeDto>,
)

@JsonClass(generateAdapter = true)
data class ReceiptSyncPullChangeDto(
    val entity: String,
    val operation: String,
    val id: String,
    @Json(name = "updated_at")
    val updatedAt: String,
    val payload: ReceiptSyncUpsertPayloadDto? = null,
)

@JsonClass(generateAdapter = true)
data class ReceiptSyncUpsertPayloadDto(
    val source: String,
    val merchant: String,
    @Json(name = "expense_date")
    val expenseDate: String,
    @Json(name = "total_amount")
    val totalAmount: String,
    val currency: String,
    @Json(name = "category_id")
    val categoryId: String? = null,
    val notes: String? = null,
    val items: List<ReceiptSyncExpenseItemDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ReceiptSyncExpenseItemDto(
    val name: String,
    val amount: String,
)
