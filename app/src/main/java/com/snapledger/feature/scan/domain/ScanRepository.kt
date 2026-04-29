package com.snapledger.feature.scan.domain

interface ScanRepository {
    fun loadPlaceholderState(): ScanUiState

    // TODO(phase-2): Add CameraX capture orchestration once the capture pipeline starts.
    fun requestCapture()

    // TODO(phase-2): Add OCR extraction boundary for ML Kit text recognition results.
    fun requestOcrExtraction()

    // TODO(phase-2): Add deterministic parser handoff after OCR text normalization.
    fun requestDeterministicParse()
}

class PlaceholderScanRepository : ScanRepository {
    override fun loadPlaceholderState(): ScanUiState = ScanUiState()

    override fun requestCapture() = Unit

    override fun requestOcrExtraction() = Unit

    override fun requestDeterministicParse() = Unit
}
