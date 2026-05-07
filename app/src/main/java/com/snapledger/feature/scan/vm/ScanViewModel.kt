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
import com.snapledger.feature.scan.domain.OcrUiState
import com.snapledger.feature.scan.domain.ParserPhase
import com.snapledger.feature.scan.domain.ParserUiState
import com.snapledger.feature.scan.domain.PendingCapture
import com.snapledger.feature.scan.domain.ScanCapturePhase
import com.snapledger.feature.scan.domain.ScanRepository
import com.snapledger.feature.scan.domain.ScanUiState
import com.snapledger.feature.review.domain.LocalFirstReviewRepository
import com.snapledger.feature.review.domain.ReviewRepository
import com.snapledger.feature.scan.network.ReceiptProcessService
import com.snapledger.feature.scan.network.ReceiptProcessClient
import com.snapledger.feature.scan.network.RemoteProcessResult
import com.snapledger.feature.scan.parser.DeterministicReceiptParserService
import com.snapledger.feature.scan.parser.ReceiptParserService
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScanViewModel(
    private val repository: ScanRepository = CameraCaptureRepository(),
    private val parserService: ReceiptParserService,
    private val processService: ReceiptProcessClient,
    private val reviewRepository: ReviewRepository,
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
        reviewRepository.storeParsedCandidate(null)
        uiState = uiState.copy(
            status = "Camera unavailable",
            captureStatus = "Camera setup failed. Retry to rebind the preview.",
            capturePhase = ScanCapturePhase.CameraFailure,
            ocr = OcrUiState(),
            parser = ParserUiState(),
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
        reviewRepository.storeParsedCandidate(null)
        uiState = uiState.copy(
            status = "Capture failed",
            captureStatus = "CameraX could not capture the image. Retry to continue.",
            capturePhase = ScanCapturePhase.CameraFailure,
            ocr = OcrUiState(),
            parser = ParserUiState(),
            cameraErrorMessage = message,
            capturedImage = null,
        )
    }

    fun onRetryCapture() {
        reviewRepository.storeParsedCandidate(null)
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
            parser = ParserUiState(),
            cameraErrorMessage = null,
            cameraSessionId = uiState.cameraSessionId + 1,
        )
    }

    fun onOcrRequested() {
        val capturedImage = uiState.capturedImage ?: run {
            uiState = uiState.copy(
                parser = ParserUiState(
                    phase = ParserPhase.Failure,
                    status = "Processing unavailable",
                    errorMessage = "Capture a receipt image before processing.",
                ),
            )
            return
        }

        reviewRepository.storeParsedCandidate(null)
        uiState = uiState.copy(
            parser = ParserUiState(
                phase = ParserPhase.Running,
                status = "Processing receipt",
            ),
        )

        viewModelScope.launch {
            val sourcePath = capturedImage.absolutePath
            val remoteResult = withContext(ioDispatcher) { processService.processReceipt(capturedImage) }
            if (uiState.capturedImage?.absolutePath != sourcePath) return@launch

            when (remoteResult) {
                is RemoteProcessResult.Success -> {
                    reviewRepository.storeParsedCandidate(remoteResult.candidate)
                    val isComplete = remoteResult.candidate.merchant != null &&
                        remoteResult.candidate.expenseDate != null &&
                        remoteResult.candidate.totalAmount != null &&
                        remoteResult.candidate.warnings.isEmpty()
                    uiState = uiState.copy(
                        parser = ParserUiState(
                            phase = if (isComplete) ParserPhase.Success else ParserPhase.Partial,
                            status = if (isComplete) "Receipt processed successfully" else "Receipt processed with warnings",
                            candidate = remoteResult.candidate,
                        ),
                    )
                }

                is RemoteProcessResult.RateLimited -> {
                    uiState = uiState.copy(
                        parser = ParserUiState(
                            phase = ParserPhase.Failure,
                            status = "Processing delayed",
                            errorMessage = remoteResult.message,
                        ),
                    )
                }

                is RemoteProcessResult.Failure -> {
                    uiState = uiState.copy(
                        parser = ParserUiState(
                            phase = ParserPhase.Failure,
                            status = "Processing failed",
                            errorMessage = remoteResult.message,
                        ),
                    )
                }
            }
        }
    }

    fun onParseRequested() {
        val ocrLines = uiState.ocr.lines
        if (ocrLines.isEmpty()) {
            uiState = uiState.copy(
                parser = ParserUiState(
                    phase = ParserPhase.Failure,
                    status = "Parser unavailable",
                    errorMessage = "Run OCR successfully before starting deterministic parsing.",
                ),
            )
            return
        }

        uiState = uiState.copy(
            parser = ParserUiState(
                phase = ParserPhase.Running,
                status = "Running deterministic receipt parser",
            ),
        )

        viewModelScope.launch {
            val currentSourcePath = uiState.capturedImage?.absolutePath
            val candidate = withContext(ioDispatcher) {
                parserService.parse(ocrLines)
            }
            if (uiState.capturedImage?.absolutePath != currentSourcePath) return@launch

            val isComplete = candidate.merchant != null &&
                candidate.expenseDate != null &&
                candidate.totalAmount != null &&
                candidate.warnings.isEmpty()
            reviewRepository.storeParsedCandidate(candidate)

            uiState = uiState.copy(
                parser = ParserUiState(
                    phase = if (isComplete) ParserPhase.Success else ParserPhase.Partial,
                    status = if (isComplete) {
                        "Deterministic parser completed successfully"
                    } else {
                        "Deterministic parser completed with warnings"
                    },
                    candidate = candidate,
                ),
            )
        }
    }

    private fun applyCapturedImage(metadata: CapturedImageMetadata) {
        reviewRepository.storeParsedCandidate(null)
        uiState = uiState.copy(
            status = "Capture complete",
            captureStatus = "Captured image metadata is ready for the next scan step",
            capturePhase = ScanCapturePhase.CaptureSucceeded,
            capturedImage = metadata,
            ocr = OcrUiState(
                status = "Run OCR after capturing a receipt image",
            ),
            parser = ParserUiState(
                status = "Run the deterministic parser after OCR completes",
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
                            parserService = DeterministicReceiptParserService(),
                            processService = ReceiptProcessService(appContext),
                            reviewRepository = LocalFirstReviewRepository.getInstance(appContext),
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }

    // toggles the camera on and off
    fun toggleCameraActiveState() {
        uiState = uiState.copy(
            isCameraActive = !uiState.isCameraActive,
            cameraErrorMessage = if (uiState.isCameraActive) null else uiState.cameraErrorMessage
        )
    }
}
