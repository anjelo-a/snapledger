package com.snapledger.feature.review.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.snapledger.feature.review.domain.InMemoryReviewRepository
import com.snapledger.feature.review.domain.ReviewItemFieldState
import com.snapledger.feature.review.domain.ReviewRepository
import com.snapledger.feature.review.domain.ReviewUiState
import com.snapledger.feature.review.domain.validateReviewState

class ReviewViewModel(
    private val repository: ReviewRepository = InMemoryReviewRepository.instance,
) : ViewModel() {
    var uiState: ReviewUiState by mutableStateOf(validateReviewState(repository.loadDraft()))
        private set

    fun onMerchantChanged(value: String) {
        updateState(
            uiState.copy(
                merchant = uiState.merchant.copy(value = value),
            ),
        )
    }

    fun onExpenseDateChanged(value: String) {
        updateState(
            uiState.copy(
                expenseDate = uiState.expenseDate.copy(value = value),
            ),
        )
    }

    fun onTotalAmountChanged(value: String) {
        updateState(
            uiState.copy(
                totalAmount = uiState.totalAmount.copy(value = value),
            ),
        )
    }

    fun onItemDescriptionChanged(itemId: Int, value: String) {
        updateState(
            uiState.copy(
                items = uiState.items.map { item ->
                    if (item.id == itemId) item.copy(description = value) else item
                },
            ),
        )
    }

    fun onItemAmountChanged(itemId: Int, value: String) {
        updateState(
            uiState.copy(
                items = uiState.items.map { item ->
                    if (item.id == itemId) item.copy(amount = value) else item
                },
            ),
        )
    }

    fun onAddItemRequested() {
        val nextId = (uiState.items.maxOfOrNull { it.id } ?: -1) + 1
        updateState(
            uiState.copy(
                items = uiState.items + ReviewItemFieldState(
                    id = nextId,
                    description = "",
                    amount = "",
                ),
            ),
        )
    }

    fun onRemoveItemRequested(itemId: Int) {
        updateState(
            uiState.copy(
                items = uiState.items.filterNot { it.id == itemId },
            ),
        )
    }

    fun onSaveRequested() {
        if (!uiState.saveEnabled) return
        repository.saveReviewedReceipt(uiState)
        updateState(
            uiState.copy(
                saveStatusMessage = "Save is still deferred. This screen only validates and stages edits in Phase 2.",
            ),
        )
    }

    private fun updateState(nextState: ReviewUiState) {
        uiState = validateReviewState(nextState)
    }

    companion object {
        fun factory(repository: ReviewRepository = InMemoryReviewRepository.instance): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ReviewViewModel::class.java)) {
                        return ReviewViewModel(repository) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}
