package com.snapledger.feature.review.domain

data class ReviewFieldState(
    val label: String,
    val value: String,
)

data class ReviewUiState(
    val title: String = "Receipt Review",
    val subtitle: String = "Phase 2 placeholder review state only",
    val merchant: ReviewFieldState = ReviewFieldState(
        label = "Merchant",
        value = "Pending deterministic parser output",
    ),
    val expenseDate: ReviewFieldState = ReviewFieldState(
        label = "Expense date",
        value = "Pending OCR and parser",
    ),
    val totalAmount: ReviewFieldState = ReviewFieldState(
        label = "Total amount",
        value = "Pending OCR and parser",
    ),
    val items: List<ReviewFieldState> = listOf(
        ReviewFieldState(
            label = "Items",
            value = "No parsed line items yet",
        )
    ),
    val warnings: List<String> = listOf(
        "Review editing is intentionally not implemented in this skeleton.",
        "Local save is intentionally not implemented in this skeleton.",
    ),
    val saveEnabled: Boolean = false,
)
