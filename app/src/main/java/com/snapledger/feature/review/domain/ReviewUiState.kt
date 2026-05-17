package com.snapledger.feature.review.domain

data class ReviewEditableFieldState(
    val label: String,
    val value: String,
    val errorMessage: String? = null,
)

data class ReviewItemFieldState(
    val id: Int,
    val description: String,
    val amount: String,
)

data class ReviewUiState(
    val title: String = "Receipt Review",
    val subtitle: String = "Review and edit parsed receipt fields before saving",
    val merchant: ReviewEditableFieldState = ReviewEditableFieldState(
        label = "Merchant",
        value = "",
    ),
    val expenseDate: ReviewEditableFieldState = ReviewEditableFieldState(
        label = "Expense date",
        value = "",
    ),
    val totalAmount: ReviewEditableFieldState = ReviewEditableFieldState(
        label = "Total amount",
        value = "",
    ),
    val category: String = "Food",
    val items: List<ReviewItemFieldState> = emptyList(),
    val warnings: List<String> = emptyList(),
    val saveEnabled: Boolean = false,
    val isSaving: Boolean = false,
    val saveStatusMessage: String = "Saving is still intentionally deferred in Phase 2.",
)
