package com.snapledger.feature.scan.domain

data class ScanUiState(
    val title: String = "Receipt Scan",
    val status: String = "Phase 2 placeholder flow only",
    val captureStatus: String = "CameraX capture not started",
    val ocrStatus: String = "OCR extraction not started",
    val parserStatus: String = "Deterministic parser not started",
    val canContinueToReview: Boolean = true,
)
