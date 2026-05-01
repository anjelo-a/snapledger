package com.snapledger.core.network.mapper

import com.snapledger.core.network.dto.ReceiptSyncExpenseItemDto
import com.snapledger.core.network.dto.ReceiptSyncPullChangeDto
import com.snapledger.core.network.dto.ReceiptSyncPullResponseDto
import com.snapledger.core.network.dto.ReceiptSyncPushMutationDto
import com.snapledger.core.network.dto.ReceiptSyncPushRequestDto
import com.snapledger.core.network.dto.ReceiptSyncPushResponseDto
import com.snapledger.core.network.dto.ReceiptSyncPushResultDto
import com.snapledger.core.network.dto.ReceiptSyncUpsertPayloadDto
import com.snapledger.core.sync.RECEIPT_SYNC_ENTITY
import com.snapledger.core.sync.ReceiptSyncDeletePayload
import com.snapledger.core.sync.ReceiptSyncExpenseItem
import com.snapledger.core.sync.ReceiptSyncMutationPayload
import com.snapledger.core.sync.ReceiptSyncPullChange
import com.snapledger.core.sync.ReceiptSyncPullOperation
import com.snapledger.core.sync.ReceiptSyncPullResponse
import com.snapledger.core.sync.ReceiptSyncPushMutation
import com.snapledger.core.sync.ReceiptSyncPushOperation
import com.snapledger.core.sync.ReceiptSyncPushRequest
import com.snapledger.core.sync.ReceiptSyncPushResponse
import com.snapledger.core.sync.ReceiptSyncPushResult
import com.snapledger.core.sync.ReceiptSyncUpsertPayload

internal fun ReceiptSyncPushRequest.toDto(): ReceiptSyncPushRequestDto {
    return ReceiptSyncPushRequestDto(
        mutations = mutations.map(ReceiptSyncPushMutation::toDto),
    )
}

internal fun ReceiptSyncPushResponseDto.toDomain(): ReceiptSyncPushResponse {
    return ReceiptSyncPushResponse(
        accepted = accepted,
        rejected = rejected,
        results = results.map(ReceiptSyncPushResultDto::toDomain),
    )
}

internal fun ReceiptSyncPullResponseDto.toDomain(): ReceiptSyncPullResponse {
    return ReceiptSyncPullResponse(
        cursor = cursor,
        hasMore = hasMore,
        changes = changes.map(ReceiptSyncPullChangeDto::toDomain),
    )
}

private fun ReceiptSyncPushMutation.toDto(): ReceiptSyncPushMutationDto {
    return ReceiptSyncPushMutationDto(
        idempotencyKey = idempotencyKey,
        entity = RECEIPT_SYNC_ENTITY,
        operation = operation.toWireValue(),
        occurredAt = occurredAt,
        payload = payload.toPayloadMap(),
    )
}

private fun ReceiptSyncMutationPayload.toPayloadMap(): Map<String, Any?> {
    return when (this) {
        is ReceiptSyncUpsertPayload -> linkedMapOf(
            "id" to id,
            "source" to source,
            "merchant" to merchant,
            "expense_date" to expenseDate,
            "total_amount" to totalAmount,
            "currency" to currency,
            "category_id" to categoryId,
            "notes" to notes,
            "items" to items.map { item ->
                linkedMapOf(
                    "name" to item.name,
                    "amount" to item.amount,
                )
            },
        )

        is ReceiptSyncDeletePayload -> linkedMapOf(
            "id" to id,
        )
    }
}

private fun ReceiptSyncPushResultDto.toDomain(): ReceiptSyncPushResult {
    return ReceiptSyncPushResult(
        idempotencyKey = idempotencyKey,
        entity = entity,
        operation = operation.toPushOperation(),
        status = status,
        code = code,
        message = message,
        entityId = entityId,
    )
}

private fun ReceiptSyncPullChangeDto.toDomain(): ReceiptSyncPullChange {
    return ReceiptSyncPullChange(
        entity = entity,
        operation = operation.toPullOperation(),
        id = id,
        updatedAt = updatedAt,
        payload = payload?.toDomain(id),
    )
}

private fun ReceiptSyncUpsertPayloadDto.toDomain(id: String): ReceiptSyncUpsertPayload {
    return ReceiptSyncUpsertPayload(
        id = id,
        source = source,
        merchant = merchant,
        expenseDate = expenseDate,
        totalAmount = totalAmount,
        currency = currency,
        categoryId = categoryId,
        notes = notes,
        items = items.map(ReceiptSyncExpenseItemDto::toDomain),
    )
}

private fun ReceiptSyncExpenseItemDto.toDomain(): ReceiptSyncExpenseItem {
    return ReceiptSyncExpenseItem(
        name = name,
        amount = amount,
    )
}

private fun ReceiptSyncPushOperation.toWireValue(): String {
    return when (this) {
        ReceiptSyncPushOperation.Create -> "create"
        ReceiptSyncPushOperation.Update -> "update"
        ReceiptSyncPushOperation.Delete -> "delete"
    }
}

private fun String.toPushOperation(): ReceiptSyncPushOperation {
    return when (this) {
        "create" -> ReceiptSyncPushOperation.Create
        "update" -> ReceiptSyncPushOperation.Update
        "delete" -> ReceiptSyncPushOperation.Delete
        else -> error("Unsupported push operation: $this")
    }
}

private fun String.toPullOperation(): ReceiptSyncPullOperation {
    return when (this) {
        "upsert" -> ReceiptSyncPullOperation.Upsert
        "delete" -> ReceiptSyncPullOperation.Delete
        else -> error("Unsupported pull operation: $this")
    }
}
