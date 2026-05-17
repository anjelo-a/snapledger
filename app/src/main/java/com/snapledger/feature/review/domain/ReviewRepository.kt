package com.snapledger.feature.review.domain

import android.content.Context
import com.snapledger.core.ledger.DataStoreLedgerRepository
import com.snapledger.core.ledger.LedgerRepository
import com.snapledger.core.ledger.LedgerTransactionType
import com.snapledger.feature.review.data.ReviewLocalDatabase
import com.snapledger.feature.review.data.RoomReviewAtomicSaveStore
import com.snapledger.core.sync.WorkManagerReviewSyncDispatcher
import com.snapledger.feature.scan.domain.ParsedReceiptCandidate
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

interface ReviewRepository {
    fun loadDraft(): ReviewUiState

    fun storeParsedCandidate(candidate: ParsedReceiptCandidate?)

    suspend fun saveReviewedReceipt(uiState: ReviewUiState): ReviewSaveResult
}

sealed interface ReviewSaveResult {
    data class Success(
        val receiptId: String,
        val syncQueueId: String,
        val dispatchedSyncAttempt: Boolean,
        val syncDispatchError: String? = null,
    ) : ReviewSaveResult

    data class ValidationFailed(
        val uiState: ReviewUiState,
    ) : ReviewSaveResult
}

data class LocalReceiptRecord(
    val receiptId: String,
    val merchant: String,
    val expenseDate: String,
    val totalAmountRaw: String,
    val totalAmountMinor: Long,
    val savedAtMillis: Long,
    val items: List<LocalReceiptItemRecord>,
)

data class LocalReceiptItemRecord(
    val position: Int,
    val description: String,
    val amountRaw: String?,
    val amountMinor: Long?,
)

data class ReceiptSyncQueueRecord(
    val queueId: String,
    val idempotencyKey: String = queueId,
    val receiptId: String,
    val operation: String = OPERATION_CREATE,
    val payloadSnapshot: String,
    val status: String = STATUS_PENDING,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val queuedAtMillis: Long,
    val nextRetryAtMillis: Long? = null,
) {
    companion object {
        const val OPERATION_CREATE = "create"
        const val OPERATION_UPDATE = "update"
        const val OPERATION_DELETE = "delete"

        const val STATUS_PENDING = "pending"
        const val STATUS_IN_FLIGHT = "in_flight"
        const val STATUS_SYNCED = "synced"
        const val STATUS_FAILED = "failed"
        const val STATUS_TERMINAL_FAILED = "terminal_failed"
    }
}

interface ReviewLocalReceiptStore {
    suspend fun saveReceipt(record: LocalReceiptRecord)
}

interface ReviewSyncQueueStore {
    suspend fun enqueue(record: ReceiptSyncQueueRecord)
}

interface ReviewAtomicSaveStore {
    suspend fun saveReceiptAndQueue(
        receiptRecord: LocalReceiptRecord,
        syncRecord: ReceiptSyncQueueRecord,
    )
}

interface ReviewSyncDispatcher {
    suspend fun dispatch(record: ReceiptSyncQueueRecord)
}

class LocalFirstReviewRepository(
    private val atomicSaveStore: ReviewAtomicSaveStore,
    private val syncDispatcher: ReviewSyncDispatcher,
    private val ledgerRepository: LedgerRepository? = null,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ReviewRepository {
    private var latestCandidate: ParsedReceiptCandidate? = null
    private var latestDraft: ReviewUiState? = null

    override fun loadDraft(): ReviewUiState {
        return latestDraft ?: latestCandidate?.toReviewUiState() ?: emptyReviewUiState()
    }

    override fun storeParsedCandidate(candidate: ParsedReceiptCandidate?) {
        latestCandidate = candidate
        latestDraft = candidate?.toReviewUiState()
    }

    override suspend fun saveReviewedReceipt(uiState: ReviewUiState): ReviewSaveResult {
        val validatedState = validateReviewState(uiState)
        if (!validatedState.saveEnabled) {
            return ReviewSaveResult.ValidationFailed(validatedState)
        }

        val now = clock()
        val receiptRecord = validatedState.toLocalReceiptRecord(
            receiptId = idGenerator(),
            savedAtMillis = now,
        )
        val syncRecord = ReceiptSyncQueueRecord(
            queueId = idGenerator(),
            receiptId = receiptRecord.receiptId,
            payloadSnapshot = receiptRecord.toSyncPayloadSnapshot(),
            queuedAtMillis = now,
        )

        atomicSaveStore.saveReceiptAndQueue(
            receiptRecord = receiptRecord,
            syncRecord = syncRecord,
        )
        ledgerRepository?.saveTransaction(
            type = LedgerTransactionType.EXPENSE,
            amount = receiptRecord.totalAmountMinor.toDouble() / 100.0,
            merchant = receiptRecord.merchant,
            date = receiptRecord.expenseDate,
            note = null,
            category = validatedState.category,
        )

        return try {
            syncDispatcher.dispatch(syncRecord)
            latestDraft = validateReviewState(
                validatedState.copy(
                    isSaving = false,
                    saveStatusMessage = buildString {
                        append("Saved locally as ")
                        append(receiptRecord.receiptId)
                        append(". Sync metadata queued as ")
                        append(syncRecord.queueId)
                        append(".")
                    },
                ),
            )
            ReviewSaveResult.Success(
                receiptId = receiptRecord.receiptId,
                syncQueueId = syncRecord.queueId,
                dispatchedSyncAttempt = true,
            )
        } catch (error: Exception) {
            latestDraft = validateReviewState(
                validatedState.copy(
                    isSaving = false,
                    saveStatusMessage = buildString {
                        append("Saved locally as ")
                        append(receiptRecord.receiptId)
                        append(". Sync metadata queued as ")
                        append(syncRecord.queueId)
                        append("; background dispatch failed: ")
                        append(error.message ?: "Sync dispatch failed after local save.")
                    },
                ),
            )
            ReviewSaveResult.Success(
                receiptId = receiptRecord.receiptId,
                syncQueueId = syncRecord.queueId,
                dispatchedSyncAttempt = false,
                syncDispatchError = error.message ?: "Sync dispatch failed after local save.",
            )
        }
    }

    companion object {
        @Volatile
        private var instance: LocalFirstReviewRepository? = null

        fun getInstance(applicationContext: Context): LocalFirstReviewRepository {
            return instance ?: synchronized(this) {
                instance ?: buildRepository(applicationContext.applicationContext).also {
                    instance = it
                }
            }
        }

        private fun buildRepository(applicationContext: Context): LocalFirstReviewRepository {
            val database = ReviewLocalDatabase.getInstance(applicationContext)
            return LocalFirstReviewRepository(
                atomicSaveStore = RoomReviewAtomicSaveStore(database),
                syncDispatcher = WorkManagerReviewSyncDispatcher(applicationContext),
                ledgerRepository = DataStoreLedgerRepository.getInstance(applicationContext),
            )
        }
    }
}

fun validateReviewState(uiState: ReviewUiState): ReviewUiState {
    val merchantError = if (uiState.merchant.value.isBlank()) {
        "Merchant is required."
    } else {
        null
    }
    val expenseDateError = if (uiState.expenseDate.value.isBlank()) {
        "Expense date is required."
    } else if (!isIsoDate(uiState.expenseDate.value)) {
        "Expense date must use YYYY-MM-DD."
    } else {
        null
    }
    val totalAmountError = when {
        uiState.totalAmount.value.isBlank() -> "Total amount is required."
        parseAmountToMinor(uiState.totalAmount.value) == null ->
            "Total amount must be a positive number with up to 2 decimals."
        else -> null
    }

    return uiState.copy(
        merchant = uiState.merchant.copy(errorMessage = merchantError),
        expenseDate = uiState.expenseDate.copy(errorMessage = expenseDateError),
        totalAmount = uiState.totalAmount.copy(errorMessage = totalAmountError),
        saveEnabled = merchantError == null &&
            expenseDateError == null &&
            totalAmountError == null,
    )
}

private fun ParsedReceiptCandidate.toReviewUiState(): ReviewUiState {
    val uiState = ReviewUiState(
        merchant = ReviewEditableFieldState(
            label = "Merchant",
            value = merchant.orEmpty(),
        ),
        expenseDate = ReviewEditableFieldState(
            label = "Expense date",
            value = expenseDate.orEmpty(),
        ),
        totalAmount = ReviewEditableFieldState(
            label = "Total amount",
            value = totalAmount?.rawText.orEmpty(),
        ),
        items = items.mapIndexed { index, item ->
            ReviewItemFieldState(
                id = index,
                description = item.description,
                amount = item.amount?.rawText.orEmpty(),
            )
        },
        warnings = warnings,
        saveStatusMessage = "Review parsed fields and save locally when ready.",
    )
    return validateReviewState(uiState)
}

private fun emptyReviewUiState(): ReviewUiState {
    return validateReviewState(
        ReviewUiState(
            warnings = listOf(
                "No parsed receipt candidate is available yet.",
                "Run OCR and the deterministic parser before reviewing fields.",
            ),
        ),
    )
}

private fun ReviewUiState.toLocalReceiptRecord(
    receiptId: String,
    savedAtMillis: Long,
): LocalReceiptRecord {
    return LocalReceiptRecord(
        receiptId = receiptId,
        merchant = merchant.value.trim(),
        expenseDate = expenseDate.value.trim(),
        totalAmountRaw = totalAmount.value.trim(),
        totalAmountMinor = requireNotNull(parseAmountToMinor(totalAmount.value)),
        savedAtMillis = savedAtMillis,
        items = items.mapIndexed { index, item ->
            LocalReceiptItemRecord(
                position = index,
                description = item.description.trim(),
                amountRaw = item.amount.trim().takeIf { it.isNotBlank() },
                amountMinor = item.amount
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let(::parseAmountToMinor),
            )
        },
    )
}

private fun LocalReceiptRecord.toSyncPayloadSnapshot(): String {
    val itemPayload = items.joinToString(separator = ",") { item ->
        buildString {
            append("{")
            append("\"name\":\"")
            append(item.description.toJsonString())
            append("\"")
            item.amountRaw?.let { amount ->
                append(",\"amount\":\"")
                append(amount.toJsonString())
                append("\"")
            }
            append("}")
        }
    }

    return buildString {
        append("{")
        append("\"id\":\"")
        append(receiptId.toJsonString())
        append("\",")
        append("\"source\":\"scan\",")
        append("\"merchant\":\"")
        append(merchant.toJsonString())
        append("\",")
        append("\"expense_date\":\"")
        append(expenseDate.toJsonString())
        append("\",")
        append("\"total_amount\":\"")
        append(totalAmountRaw.toJsonString())
        append("\",")
        append("\"currency\":\"PHP\",")
        append("\"items\":[")
        append(itemPayload)
        append("]")
        append("}")
    }
}

private fun String.toJsonString(): String {
    return buildString(length) {
        for (character in this@toJsonString) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
}

private fun isIsoDate(value: String): Boolean {
    return try {
        LocalDate.parse(value)
        true
    } catch (_: DateTimeParseException) {
        false
    }
}

private fun parseAmountToMinor(value: String): Long? {
    val normalized = value
        .replace("$", "")
        .replace("₱", "")
        .replace(",", "")
        .trim()
    val amount = normalized.toBigDecimalOrNull()
        ?.takeIf { it > java.math.BigDecimal.ZERO }
        ?: return null
    return try {
        amount.movePointRight(2).longValueExact()
    } catch (_: ArithmeticException) {
        null
    }
}
