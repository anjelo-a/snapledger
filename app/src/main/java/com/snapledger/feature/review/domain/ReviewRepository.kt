package com.snapledger.feature.review.domain

import com.snapledger.feature.scan.domain.ParsedReceiptCandidate
import java.time.LocalDate
import java.time.format.DateTimeParseException

interface ReviewRepository {
    fun loadDraft(): ReviewUiState

    fun storeParsedCandidate(candidate: ParsedReceiptCandidate?)

    fun saveReviewedReceipt(uiState: ReviewUiState)
}

class InMemoryReviewRepository private constructor() : ReviewRepository {
    private var latestCandidate: ParsedReceiptCandidate? = null

    override fun loadDraft(): ReviewUiState {
        return latestCandidate?.toReviewUiState() ?: emptyReviewUiState()
    }

    override fun storeParsedCandidate(candidate: ParsedReceiptCandidate?) {
        latestCandidate = candidate
    }

    override fun saveReviewedReceipt(uiState: ReviewUiState) = Unit

    companion object {
        val instance: InMemoryReviewRepository = InMemoryReviewRepository()
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
        parseAmountToMinor(uiState.totalAmount.value) == null -> "Total amount must be a positive number with up to 2 decimals."
        else -> null
    }

    return uiState.copy(
        merchant = uiState.merchant.copy(errorMessage = merchantError),
        expenseDate = uiState.expenseDate.copy(errorMessage = expenseDateError),
        totalAmount = uiState.totalAmount.copy(errorMessage = totalAmountError),
        saveEnabled = merchantError == null && expenseDateError == null && totalAmountError == null,
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
