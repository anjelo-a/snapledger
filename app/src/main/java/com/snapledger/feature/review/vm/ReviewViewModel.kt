package com.snapledger.feature.review.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.snapledger.feature.review.domain.PlaceholderReviewRepository
import com.snapledger.feature.review.domain.ReviewUiState

class ReviewViewModel : ViewModel() {
    private val repository = PlaceholderReviewRepository()

    var uiState: ReviewUiState by mutableStateOf(repository.loadPlaceholderDraft())
        private set

    init {
        repository.prepareEditableDraft()
    }

    fun onSaveRequested() {
        repository.saveReviewedReceipt()
        // TODO(phase-2): Replace this no-op boundary with real local save orchestration.
    }
}
