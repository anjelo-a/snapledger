package com.snapledger.feature.review.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snapledger.feature.review.domain.ReviewItemFieldState
import com.snapledger.feature.review.domain.ReviewRepository
import com.snapledger.feature.review.domain.ReviewSaveResult
import com.snapledger.feature.review.domain.ReviewUiState
import com.snapledger.feature.review.domain.validateReviewState
import kotlinx.coroutines.launch

class ReviewViewModel(
    private val repository: ReviewRepository,
) : ViewModel() {
    var uiState: ReviewUiState by mutableStateOf(validateReviewState(repository.loadDraft()))
        private set
    var shouldCloseAfterSave: Boolean by mutableStateOf(false)
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

    fun onCategoryChanged(value: String) {
        updateState(
            uiState.copy(category = value),
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
        val draftToSave = uiState
        updateState(
            uiState.copy(
                isSaving = true,
                saveStatusMessage = "Saving receipt locally and queueing sync metadata...",
            ),
        )
        viewModelScope.launch {
            val result = repository.saveReviewedReceipt(draftToSave)
            uiState = when (result) {
                is ReviewSaveResult.Success -> {
                    shouldCloseAfterSave = true
                    validateReviewState(
                        uiState.copy(
                            isSaving = false,
                            saveStatusMessage = buildString {
                                append("Saved locally as ${result.receiptId}. ")
                                if (result.syncDispatchError == null) {
                                    append("Sync metadata queued as ${result.syncQueueId}.")
                                } else {
                                    append("Sync metadata queued as ${result.syncQueueId}; background dispatch failed: ${result.syncDispatchError}")
                                }
                            },
                        ),
                    )
                }

                is ReviewSaveResult.ValidationFailed -> {
                    shouldCloseAfterSave = false
                    result.uiState.copy(
                        isSaving = false,
                        saveStatusMessage = "Local save was skipped because required review fields are invalid.",
                    )
                }
            }
        }
    }

    fun onSaveCloseHandled() {
        shouldCloseAfterSave = false
    }

    private fun updateState(nextState: ReviewUiState) {
        uiState = validateReviewState(nextState)
    }

    companion object {
        fun factory(repository: ReviewRepository): ViewModelProvider.Factory {
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
