package com.snapledger.feature.scan.vm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snapledger.feature.scan.domain.CameraCaptureRepository
import com.snapledger.feature.scan.domain.CameraPermissionState
import com.snapledger.feature.scan.domain.CapturedImageMetadata
import com.snapledger.feature.scan.domain.OcrExtractionPhase
import com.snapledger.feature.scan.domain.OcrUiState
import com.snapledger.feature.scan.domain.PendingCapture
import com.snapledger.feature.scan.domain.ScanCapturePhase
import com.snapledger.feature.scan.domain.ScanRepository
import com.snapledger.feature.scan.domain.ScanUiState
import com.snapledger.feature.scan.ocr.MlKitReceiptOcrService
import com.snapledger.feature.scan.ocr.ReceiptOcrResult
import com.snapledger.feature.scan.ocr.ReceiptOcrService
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScanViewModel(
    private val repository: ScanRepository = CameraCaptureRepository(),
    private val ocrService: ReceiptOcrService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    var uiState: ScanUiState by mutableStateOf(repository.loadInitialState())
        private set

    fun onPermissionUpdated(
        granted: Boolean,
        canRequestPermissionAgain: Boolean,
    ) {
        uiState = if (granted) {
            uiState.copy(
                status = "Camera preview starting",
                captureStatus = "Binding the back camera preview",
                permissionState = CameraPermissionState.Granted,
                capturePhase = ScanCapturePhase.PreviewLoading,
                cameraErrorMessage = null,
                cameraSessionId = uiState.cameraSessionId + 1,
            )
        } else {
            uiState.copy(
                status = "Camera permission required",
                captureStatus = if (canRequestPermissionAgain) {
                    "Camera access was denied. Grant permission to continue scanning."
                } else {
                    "Camera access is blocked. Enable the permission in system settings to continue."
                },
                permissionState = if (canRequestPermissionAgain) {
                    CameraPermissionState.Denied
                } else {
                    CameraPermissionState.PermanentlyDenied
                },
                capturePhase = ScanCapturePhase.AwaitingPermission,
                cameraErrorMessage = null,
            )
        }
    }

    fun onCameraPreviewReady() {
        if (uiState.permissionState != CameraPermissionState.Granted) return
        if (uiState.capturePhase == ScanCapturePhase.CaptureSucceeded) return

        uiState = uiState.copy(
            status = "Camera ready",
            captureStatus = "Frame the receipt and capture when ready",
            capturePhase = ScanCapturePhase.PreviewReady,
            cameraErrorMessage = null,
        )
    }

    fun onCameraFailure(message: String) {
        uiState = uiState.copy(
            status = "Camera unavailable",
            captureStatus = "Camera setup failed. Retry to rebind the preview.",
            capturePhase = ScanCapturePhase.CameraFailure,
            ocr = OcrUiState(),
            cameraErrorMessage = message,
            capturedImage = null,
        )
    }

    fun prepareCapture(cacheDirectory: File): PendingCapture? {
        if (!uiState.canCapture) return null

        val pendingCapture = repository.createPendingCapture(
            cacheDirectory = cacheDirectory,
            timestampMillis = System.currentTimeMillis(),
        )
        uiState = uiState.copy(
            status = "Capturing receipt",
            captureStatus = "Taking photo with CameraX",
            capturePhase = ScanCapturePhase.Capturing,
            cameraErrorMessage = null,
        )
        return pendingCapture
    }

    fun onCaptureSucceeded(
        outputPath: String,
        savedUri: String?,
    ) {
        val metadata = repository.readCapturedImageMetadata(
            outputPath = outputPath,
            savedUri = savedUri,
        )
        applyCapturedImage(metadata)
    }

    fun onCaptureFailed(message: String) {
        uiState = uiState.copy(
            status = "Capture failed",
            captureStatus = "CameraX could not capture the image. Retry to continue.",
            capturePhase = ScanCapturePhase.CameraFailure,
            ocr = OcrUiState(),
            cameraErrorMessage = message,
            capturedImage = null,
        )
    }

    fun onRetryCapture() {
        val nextPhase = if (uiState.permissionState == CameraPermissionState.Granted) {
            ScanCapturePhase.PreviewLoading
        } else {
            ScanCapturePhase.AwaitingPermission
        }
        val nextStatus = if (uiState.permissionState == CameraPermissionState.Granted) {
            "Camera preview restarting"
        } else {
            "Waiting for camera permission"
        }
        val nextCaptureStatus = if (uiState.permissionState == CameraPermissionState.Granted) {
            "Rebinding the camera preview for another attempt"
        } else {
            "Grant camera access to start the Phase 2 capture flow"
        }

        uiState = uiState.copy(
            status = nextStatus,
            captureStatus = nextCaptureStatus,
            capturePhase = nextPhase,
            capturedImage = null,
            ocr = OcrUiState(),
            cameraErrorMessage = null,
            cameraSessionId = uiState.cameraSessionId + 1,
        )
    }

    fun onOcrRequested() {
        val capturedImage = uiState.capturedImage ?: run {
            uiState = uiState.copy(
                ocr = OcrUiState(
                    phase = OcrExtractionPhase.Failure,
                    status = "OCR unavailable",
                    errorMessage = "Capture a receipt image before running OCR.",
                ),
            )
            return
        }

        uiState = uiState.copy(
            ocr = OcrUiState(
                phase = OcrExtractionPhase.Running,
                status = "Extracting text with ML Kit",
            ),
        )

        viewModelScope.launch {
            val sourcePath = capturedImage.absolutePath
            val result = withContext(ioDispatcher) {
                ocrService.extractReceiptText(capturedImage)
            }
            if (uiState.capturedImage?.absolutePath != sourcePath) return@launch

            uiState = uiState.copy(
                ocr = when (result) {
                    is ReceiptOcrResult.Success -> {
                        val isPartial = result.warningMessages.isNotEmpty()
                        OcrUiState(
                            phase = if (isPartial) {
                                OcrExtractionPhase.Partial
                            } else {
                                OcrExtractionPhase.Success
                            },
                            status = if (isPartial) {
                                "OCR completed with warnings"
                            } else {
                                "OCR completed successfully"
                            },
                            lines = result.lines,
                            metadata = result.metadata,
                            warningMessages = result.warningMessages,
                        )
                    }

                    is ReceiptOcrResult.Empty -> {
                        OcrUiState(
                            phase = OcrExtractionPhase.Empty,
                            status = "OCR found no usable lines",
                            metadata = result.metadata,
                            warningMessages = result.warningMessages,
                            errorMessage = result.message,
                        )
                    }

                    is ReceiptOcrResult.Failure -> {
                        OcrUiState(
                            phase = OcrExtractionPhase.Failure,
                            status = "OCR failed",
                            errorMessage = result.message,
                        )
                    }
                },
            )
        }
    }

    fun onParseRequested() {
        uiState = uiState.copy(
            parserStatus = "Deterministic parser remains intentionally out of scope for this step",
        )
    }

    private fun applyCapturedImage(metadata: CapturedImageMetadata) {
        uiState = uiState.copy(
            status = "Capture complete",
            captureStatus = "Captured image metadata is ready for the next scan step",
            capturePhase = ScanCapturePhase.CaptureSucceeded,
            capturedImage = metadata,
            ocr = OcrUiState(
                status = "Run OCR after capturing a receipt image",
            ),
            cameraErrorMessage = null,
        )
    }

    companion object {
        fun factory(applicationContext: Context): ViewModelProvider.Factory {
            val appContext = applicationContext.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ScanViewModel::class.java)) {
                        return ScanViewModel(
                            repository = CameraCaptureRepository(),
                            ocrService = MlKitReceiptOcrService(appContext),
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}
