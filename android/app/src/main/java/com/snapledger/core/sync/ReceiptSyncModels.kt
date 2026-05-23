package com.snapledger.core.sync

const val RECEIPT_SYNC_ENTITY = "expense"
const val INITIAL_RECEIPT_SYNC_CURSOR = "0"

enum class ReceiptSyncPushOperation {
    Create,
    Update,
    Delete,
}

enum class ReceiptSyncPullOperation {
    Upsert,
    Delete,
}

data class ReceiptSyncExpenseItem(
    val name: String,
    val amount: String,
)

sealed interface ReceiptSyncMutationPayload {
    val id: String
}

data class ReceiptSyncUpsertPayload(
    override val id: String,
    val source: String,
    val merchant: String,
    val expenseDate: String,
    val totalAmount: String,
    val currency: String = "PHP",
    val categoryId: String? = null,
    val notes: String? = null,
    val items: List<ReceiptSyncExpenseItem> = emptyList(),
) : ReceiptSyncMutationPayload

data class ReceiptSyncDeletePayload(
    override val id: String,
) : ReceiptSyncMutationPayload

data class ReceiptSyncPushMutation(
    val idempotencyKey: String,
    val operation: ReceiptSyncPushOperation,
    val occurredAt: String,
    val payload: ReceiptSyncMutationPayload,
)

data class ReceiptSyncPushRequest(
    val mutations: List<ReceiptSyncPushMutation>,
)

data class ReceiptSyncPushResult(
    val idempotencyKey: String,
    val entity: String,
    val operation: ReceiptSyncPushOperation,
    val status: String,
    val code: String? = null,
    val message: String? = null,
    val entityId: String? = null,
)

data class ReceiptSyncPushResponse(
    val accepted: Int,
    val rejected: Int,
    val results: List<ReceiptSyncPushResult>,
)

data class ReceiptSyncPullChange(
    val entity: String,
    val operation: ReceiptSyncPullOperation,
    val id: String,
    val updatedAt: String,
    val payload: ReceiptSyncUpsertPayload? = null,
)

data class ReceiptSyncPullResponse(
    val cursor: String,
    val hasMore: Boolean,
    val changes: List<ReceiptSyncPullChange>,
)
