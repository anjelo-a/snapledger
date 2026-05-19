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
    val source: CaptureSource = CaptureSource.Camera,
    val capturedAtMillis: Long,
    val fileSizeBytes: Long,
    val widthPx: Int?,
    val heightPx: Int?,
)

enum class CaptureSource {
    Camera,
    Gallery,
}

data class PendingCapture(
    val outputPath: String,
)

enum class OcrExtractionPhase {
    Idle,
    Running,
    Success,
    Partial,
    Empty,
    Failure,
}

data class NormalizedOcrLine(
    val index: Int,
    val text: String,
    val bbox: NormalizedBoundingBox? = null,
    val ocrConfidence: Float? = null,
)

data class NormalizedBoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class OcrExtractionMetadata(
    val capturedAtMillis: Long,
    val widthPx: Int?,
    val heightPx: Int?,
    val fileSizeBytes: Long,
    val sourcePath: String,
    val sourceUri: String,
)

data class OcrUiState(
    val phase: OcrExtractionPhase = OcrExtractionPhase.Idle,
    val status: String = "Run OCR after capturing a receipt image",
    val lines: List<NormalizedOcrLine> = emptyList(),
    val metadata: OcrExtractionMetadata? = null,
    val warningMessages: List<String> = emptyList(),
    val errorMessage: String? = null,
)

enum class ParserPhase {
    Idle,
    Running,
    Success,
    Partial,
    Failure,
}

data class ParsedMoneyCandidate(
    val rawText: String,
    val amountMinor: Long,
)

data class ParsedReceiptItemCandidate(
    val description: String,
    val amount: ParsedMoneyCandidate?,
)

data class ParsedReceiptCandidate(
    val merchant: String?,
    val expenseDate: String?,
    val totalAmount: ParsedMoneyCandidate?,
    val items: List<ParsedReceiptItemCandidate>,
    val warnings: List<String>,
    val warningCodes: List<String> = emptyList(),
    val fieldConfidence: ParsedReceiptFieldConfidence? = null,
)

data class ParsedReceiptFieldConfidence(
    val merchant: Float? = null,
    val expenseDate: Float? = null,
    val totalAmount: Float? = null,
    val items: Float? = null,
)

data class ParserUiState(
    val phase: ParserPhase = ParserPhase.Idle,
    val status: String = "Run the deterministic parser after OCR completes",
    val candidate: ParsedReceiptCandidate? = null,
    val errorMessage: String? = null,
)

data class ScanUiState(
    val title: String = "Receipt Scan",
    val status: String = "Waiting for camera permission",
    val captureStatus: String = "Grant camera access to start the Phase 2 capture flow",
    val permissionState: CameraPermissionState = CameraPermissionState.Unknown,
    val capturePhase: ScanCapturePhase = ScanCapturePhase.AwaitingPermission,
    val capturedImage: CapturedImageMetadata? = null,
    val ocr: OcrUiState = OcrUiState(),
    val parser: ParserUiState = ParserUiState(),
    val cameraErrorMessage: String? = null,
    val cameraSessionId: Int = 0,
    val isCameraActive: Boolean = false, //checks if camera is turned on
) {
    val canCapture: Boolean
        get() = permissionState == CameraPermissionState.Granted &&
            capturePhase == ScanCapturePhase.PreviewReady

    val canRetry: Boolean
        get() = capturedImage != null || capturePhase == ScanCapturePhase.CameraFailure

    val canContinueToReview: Boolean
        get() = parser.candidate != null

    val canRunOcr: Boolean
        get() = capturedImage != null &&
            ocr.phase != OcrExtractionPhase.Running &&
            parser.phase != ParserPhase.Running

    val canSelectFromGallery: Boolean
        get() = ocr.phase != OcrExtractionPhase.Running &&
            parser.phase != ParserPhase.Running

    val canRunParser: Boolean
        get() = ocr.lines.isNotEmpty() && parser.phase != ParserPhase.Running

    val needsCameraPermission: Boolean
        get() = permissionState != CameraPermissionState.Granted
}
