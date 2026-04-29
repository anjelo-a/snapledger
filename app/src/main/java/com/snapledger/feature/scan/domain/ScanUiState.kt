package com.snapledger.feature.scan.domain

enum class CameraPermissionState {
    Unknown,
    Granted,
    Denied,
    PermanentlyDenied,
}

enum class ScanCapturePhase {
    AwaitingPermission,
    PreviewLoading,
    PreviewReady,
    Capturing,
    CaptureSucceeded,
    CameraFailure,
}

data class CapturedImageMetadata(
    val fileName: String,
    val absolutePath: String,
    val contentUri: String,
    val capturedAtMillis: Long,
    val fileSizeBytes: Long,
    val widthPx: Int?,
    val heightPx: Int?,
)

data class PendingCapture(
    val outputPath: String,
)

data class ScanUiState(
    val title: String = "Receipt Scan",
    val status: String = "Waiting for camera permission",
    val captureStatus: String = "Grant camera access to start the Phase 2 capture flow",
    val ocrStatus: String = "ML Kit OCR is intentionally not implemented yet",
    val parserStatus: String = "Deterministic parser is intentionally not implemented yet",
    val permissionState: CameraPermissionState = CameraPermissionState.Unknown,
    val capturePhase: ScanCapturePhase = ScanCapturePhase.AwaitingPermission,
    val capturedImage: CapturedImageMetadata? = null,
    val cameraErrorMessage: String? = null,
    val cameraSessionId: Int = 0,
) {
    val canCapture: Boolean
        get() = permissionState == CameraPermissionState.Granted &&
            capturePhase == ScanCapturePhase.PreviewReady

    val canRetry: Boolean
        get() = capturedImage != null || capturePhase == ScanCapturePhase.CameraFailure

    val canContinueToReview: Boolean
        get() = capturedImage != null

    val needsCameraPermission: Boolean
        get() = permissionState != CameraPermissionState.Granted
}
