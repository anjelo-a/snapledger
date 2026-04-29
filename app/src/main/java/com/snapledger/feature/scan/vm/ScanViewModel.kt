package com.snapledger.feature.scan.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.snapledger.feature.scan.domain.PlaceholderScanRepository
import com.snapledger.feature.scan.domain.ScanUiState

class ScanViewModel : ViewModel() {
    private val repository = PlaceholderScanRepository()

    var uiState: ScanUiState by mutableStateOf(repository.loadPlaceholderState())
        private set

    fun onCaptureRequested() {
        repository.requestCapture()
        uiState = uiState.copy(
            captureStatus = "Capture TODO boundary reached",
        )
    }

    fun onOcrRequested() {
        repository.requestOcrExtraction()
        uiState = uiState.copy(
            ocrStatus = "OCR TODO boundary reached",
        )
    }

    fun onParseRequested() {
        repository.requestDeterministicParse()
        uiState = uiState.copy(
            parserStatus = "Deterministic parser TODO boundary reached",
        )
    }
}
