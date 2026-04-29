package com.snapledger.feature.scan.vm

import com.snapledger.feature.scan.domain.CameraPermissionState
import com.snapledger.feature.scan.domain.CapturedImageMetadata
import com.snapledger.feature.scan.domain.NormalizedOcrLine
import com.snapledger.feature.scan.domain.OcrExtractionMetadata
import com.snapledger.feature.scan.domain.OcrExtractionPhase
import com.snapledger.feature.scan.domain.PendingCapture
import com.snapledger.feature.scan.domain.ScanCapturePhase
import com.snapledger.feature.scan.domain.ScanRepository
import com.snapledger.feature.scan.domain.ScanUiState
import com.snapledger.feature.scan.ocr.ReceiptOcrResult
import com.snapledger.feature.scan.ocr.ReceiptOcrService
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `capture success stores metadata and enables review`() {
        val repository = FakeScanRepository()
        val viewModel = ScanViewModel(
            repository = repository,
            ocrService = FakeReceiptOcrService(),
            ioDispatcher = mainDispatcherRule.dispatcher,
        )

        viewModel.onPermissionUpdated(
            granted = true,
            canRequestPermissionAgain = true,
        )
        viewModel.onCameraPreviewReady()
        val pendingCapture = viewModel.prepareCapture(File("/tmp"))

        assertNotNull(pendingCapture)
        assertEquals(ScanCapturePhase.Capturing, viewModel.uiState.capturePhase)

        viewModel.onCaptureSucceeded(
            outputPath = "/tmp/fake.jpg",
            savedUri = "file:///tmp/fake.jpg",
        )

        assertEquals(ScanCapturePhase.CaptureSucceeded, viewModel.uiState.capturePhase)
        assertEquals("fake.jpg", viewModel.uiState.capturedImage?.fileName)
        assertTrue(viewModel.uiState.canContinueToReview)
        assertNull(viewModel.uiState.cameraErrorMessage)
    }

    @Test
    fun `capture failure is visible and retry is recoverable`() {
        val viewModel = ScanViewModel(
            repository = FakeScanRepository(),
            ocrService = FakeReceiptOcrService(),
            ioDispatcher = mainDispatcherRule.dispatcher,
        )

        viewModel.onPermissionUpdated(
            granted = true,
            canRequestPermissionAgain = true,
        )
        viewModel.onCameraPreviewReady()
        viewModel.prepareCapture(File("/tmp"))
        viewModel.onCaptureFailed("Lens blocked")

        assertEquals(ScanCapturePhase.CameraFailure, viewModel.uiState.capturePhase)
        assertEquals("Lens blocked", viewModel.uiState.cameraErrorMessage)
        assertTrue(viewModel.uiState.canRetry)

        viewModel.onRetryCapture()

        assertEquals(CameraPermissionState.Granted, viewModel.uiState.permissionState)
        assertEquals(ScanCapturePhase.PreviewLoading, viewModel.uiState.capturePhase)
        assertNull(viewModel.uiState.capturedImage)
        assertNull(viewModel.uiState.cameraErrorMessage)
    }

    @Test
    fun `ocr success stores normalized lines and metadata`() = runTest {
        val viewModel = ScanViewModel(
            repository = FakeScanRepository(),
            ocrService = FakeReceiptOcrService(
                result = ReceiptOcrResult.Success(
                    lines = listOf(
                        NormalizedOcrLine(index = 0, text = "Merchant Example"),
                        NormalizedOcrLine(index = 1, text = "Total 123.45"),
                    ),
                    metadata = fakeOcrMetadata(),
                ),
            ),
            ioDispatcher = mainDispatcherRule.dispatcher,
        )

        seedCapturedImage(viewModel)
        viewModel.onOcrRequested()
        advanceUntilIdle()

        assertEquals(OcrExtractionPhase.Success, viewModel.uiState.ocr.phase)
        assertEquals(2, viewModel.uiState.ocr.lines.size)
        assertEquals("Merchant Example", viewModel.uiState.ocr.lines.first().text)
        assertNull(viewModel.uiState.ocr.errorMessage)
    }

    @Test
    fun `ocr empty result is represented as empty`() = runTest {
        val viewModel = ScanViewModel(
            repository = FakeScanRepository(),
            ocrService = FakeReceiptOcrService(
                result = ReceiptOcrResult.Empty(
                    metadata = fakeOcrMetadata(),
                    message = "No text was detected in the captured receipt image.",
                ),
            ),
            ioDispatcher = mainDispatcherRule.dispatcher,
        )

        seedCapturedImage(viewModel)
        viewModel.onOcrRequested()
        advanceUntilIdle()

        assertEquals(OcrExtractionPhase.Empty, viewModel.uiState.ocr.phase)
        assertEquals("OCR found no usable lines", viewModel.uiState.ocr.status)
        assertEquals("No text was detected in the captured receipt image.", viewModel.uiState.ocr.errorMessage)
    }

    @Test
    fun `ocr failure is represented as failure`() = runTest {
        val viewModel = ScanViewModel(
            repository = FakeScanRepository(),
            ocrService = FakeReceiptOcrService(
                result = ReceiptOcrResult.Failure(
                    message = "Captured image is unreadable.",
                ),
            ),
            ioDispatcher = mainDispatcherRule.dispatcher,
        )

        seedCapturedImage(viewModel)
        viewModel.onOcrRequested()
        advanceUntilIdle()

        assertEquals(OcrExtractionPhase.Failure, viewModel.uiState.ocr.phase)
        assertEquals("OCR failed", viewModel.uiState.ocr.status)
        assertEquals("Captured image is unreadable.", viewModel.uiState.ocr.errorMessage)
    }

    @Test
    fun `ocr warnings are represented as partial`() = runTest {
        val viewModel = ScanViewModel(
            repository = FakeScanRepository(),
            ocrService = FakeReceiptOcrService(
                result = ReceiptOcrResult.Success(
                    lines = listOf(NormalizedOcrLine(index = 0, text = "Receipt line")),
                    metadata = fakeOcrMetadata(),
                    warningMessages = listOf("Image dimensions were unavailable, so OCR metadata is partial."),
                ),
            ),
            ioDispatcher = mainDispatcherRule.dispatcher,
        )

        seedCapturedImage(viewModel)
        viewModel.onOcrRequested()
        advanceUntilIdle()

        assertEquals(OcrExtractionPhase.Partial, viewModel.uiState.ocr.phase)
        assertEquals(1, viewModel.uiState.ocr.warningMessages.size)
    }
}

private class FakeScanRepository : ScanRepository {
    override fun loadInitialState(): ScanUiState = ScanUiState()

    override fun createPendingCapture(
        cacheDirectory: File,
        timestampMillis: Long,
    ): PendingCapture {
        return PendingCapture(outputPath = "/tmp/fake.jpg")
    }

    override fun readCapturedImageMetadata(
        outputPath: String,
        savedUri: String?,
    ): CapturedImageMetadata {
        return CapturedImageMetadata(
            fileName = "fake.jpg",
            absolutePath = outputPath,
            contentUri = savedUri ?: "file:///tmp/fake.jpg",
            capturedAtMillis = 123L,
            fileSizeBytes = 456L,
            widthPx = 800,
            heightPx = 600,
        )
    }
}

private class FakeReceiptOcrService(
    private val result: ReceiptOcrResult = ReceiptOcrResult.Success(
        lines = emptyList(),
        metadata = fakeOcrMetadata(),
    ),
) : ReceiptOcrService {
    override suspend fun extractReceiptText(capturedImage: CapturedImageMetadata): ReceiptOcrResult {
        return result
    }
}

private fun seedCapturedImage(viewModel: ScanViewModel) {
    viewModel.onPermissionUpdated(
        granted = true,
        canRequestPermissionAgain = true,
    )
    viewModel.onCameraPreviewReady()
    viewModel.onCaptureSucceeded(
        outputPath = "/tmp/fake.jpg",
        savedUri = "file:///tmp/fake.jpg",
    )
}

private fun fakeOcrMetadata(): OcrExtractionMetadata {
    return OcrExtractionMetadata(
        capturedAtMillis = 123L,
        widthPx = 800,
        heightPx = 600,
        fileSizeBytes = 456L,
        sourcePath = "/tmp/fake.jpg",
        sourceUri = "file:///tmp/fake.jpg",
    )
}
