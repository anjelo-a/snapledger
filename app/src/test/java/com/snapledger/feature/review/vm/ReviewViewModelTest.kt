package com.snapledger.feature.review.vm

import com.snapledger.feature.review.domain.ReviewRepository
import com.snapledger.feature.review.domain.ReviewUiState
import com.snapledger.feature.review.domain.validateReviewState
import com.snapledger.feature.scan.domain.ParsedMoneyCandidate
import com.snapledger.feature.scan.domain.ParsedReceiptCandidate
import com.snapledger.feature.scan.domain.ParsedReceiptItemCandidate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewViewModelTest {
    @Test
    fun `valid required fields enable save`() {
        val viewModel = ReviewViewModel(
            repository = FakeReviewRepository(validDraft()),
        )

        assertTrue(viewModel.uiState.saveEnabled)
    }

    @Test
    fun `missing merchant disables save`() {
        val viewModel = ReviewViewModel(
            repository = FakeReviewRepository(validDraft(merchant = "")),
        )

        assertFalse(viewModel.uiState.saveEnabled)
        assertTrue(viewModel.uiState.merchant.errorMessage != null)
    }

    @Test
    fun `missing date disables save`() {
        val viewModel = ReviewViewModel(
            repository = FakeReviewRepository(validDraft(expenseDate = "")),
        )

        assertFalse(viewModel.uiState.saveEnabled)
        assertTrue(viewModel.uiState.expenseDate.errorMessage != null)
    }

    @Test
    fun `invalid total disables save`() {
        val viewModel = ReviewViewModel(
            repository = FakeReviewRepository(validDraft(totalAmount = "abc")),
        )

        assertFalse(viewModel.uiState.saveEnabled)
        assertTrue(viewModel.uiState.totalAmount.errorMessage != null)
    }

    @Test
    fun `empty items still allows save when required fields are valid`() {
        val viewModel = ReviewViewModel(
            repository = FakeReviewRepository(validDraft(includeItems = false)),
        )

        assertTrue(viewModel.uiState.items.isEmpty())
        assertTrue(viewModel.uiState.saveEnabled)
    }
}

private class FakeReviewRepository(
    private val initialState: ReviewUiState,
) : ReviewRepository {
    override fun loadDraft(): ReviewUiState = initialState

    override fun storeParsedCandidate(candidate: ParsedReceiptCandidate?) = Unit

    override fun saveReviewedReceipt(uiState: ReviewUiState) = Unit
}

private fun validDraft(
    merchant: String = "Bean Barn",
    expenseDate: String = "2026-04-29",
    totalAmount: String = "12.50",
    includeItems: Boolean = true,
): ReviewUiState {
    return validateReviewState(
        ReviewUiState(
            merchant = com.snapledger.feature.review.domain.ReviewEditableFieldState(
                label = "Merchant",
                value = merchant,
            ),
            expenseDate = com.snapledger.feature.review.domain.ReviewEditableFieldState(
                label = "Expense date",
                value = expenseDate,
            ),
            totalAmount = com.snapledger.feature.review.domain.ReviewEditableFieldState(
                label = "Total amount",
                value = totalAmount,
            ),
            items = if (includeItems) {
                listOf(
                    com.snapledger.feature.review.domain.ReviewItemFieldState(
                        id = 0,
                        description = "Coffee",
                        amount = ParsedMoneyCandidate(rawText = "12.50", amountMinor = 1250).rawText,
                    ),
                    com.snapledger.feature.review.domain.ReviewItemFieldState(
                        id = 1,
                        description = ParsedReceiptItemCandidate(
                            description = "Muffin",
                            amount = null,
                        ).description,
                        amount = "",
                    ),
                )
            } else {
                emptyList()
            },
        ),
    )
}
