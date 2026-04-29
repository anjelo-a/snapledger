package com.snapledger.feature.review.domain

interface ReviewRepository {
    fun loadPlaceholderDraft(): ReviewUiState

    // TODO(phase-2): Add editable receipt review draft mapping from parser output.
    fun prepareEditableDraft()

    // TODO(phase-2): Add local save boundary after explicit user review confirmation.
    fun saveReviewedReceipt()
}

class PlaceholderReviewRepository : ReviewRepository {
    override fun loadPlaceholderDraft(): ReviewUiState = ReviewUiState()

    override fun prepareEditableDraft() = Unit

    override fun saveReviewedReceipt() = Unit
}
