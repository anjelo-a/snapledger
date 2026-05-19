package com.snapledger.feature.scan.vm

import com.snapledger.feature.review.domain.ReviewRepository
import com.snapledger.feature.review.domain.ReviewSaveResult
import com.snapledger.feature.scan.domain.CameraPermissionState
import com.snapledger.feature.scan.domain.CapturedImageMetadata
import com.snapledger.feature.scan.domain.ParsedMoneyCandidate
import com.snapledger.feature.scan.domain.ParsedReceiptCandidate
import com.snapledger.feature.scan.domain.ParsedReceiptItemCandidate
import com.snapledger.feature.scan.domain.ParserPhase
import com.snapledger.feature.scan.domain.PendingCapture
import com.snapledger.feature.scan.domain.ScanCapturePhase
import com.snapledger.feature.scan.domain.ScanRepository
import com.snapledger.feature.scan.domain.ScanUiState
import com.snapledger.feature.scan.network.ReceiptProcessClient
import com.snapledger.feature.scan.network.RemoteProcessResult
import com.snapledger.feature.scan.parser.ReceiptParserService
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `capture success stores metadata and enables next step`() {
        val viewModel = ScanViewModel(
            repository = FakeScanRepository(),
            parserService = FakeReceiptParserService(),
            processService = FakeReceiptProcessService(),
            reviewRepository = FakeReviewRepository(),
            ioDispatcher = mainDispatcherRule.dispatcher,
        )

        viewModel.onPermissionUpdated(granted = true, canRequestPermissionAgain = true)
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
        assertTrue(!viewModel.uiState.canContinueToReview)
        assertNull(viewModel.uiState.cameraErrorMessage)
    }

    @Test
    fun `remote success stores candidate and allows review`() = runTest {
        val viewModel = ScanViewModel(
            repository = FakeScanRepository(),
            parserService = FakeReceiptParserService(),
            processService = FakeReceiptProcessService(
                result = RemoteProcessResult.Success(
                    candidate = ParsedReceiptCandidate(
                        merchant = "Merchant Example",
                        expenseDate = "2026-05-07",
                        totalAmount = ParsedMoneyCandidate(rawText = "123.45", amountMinor = 12345),
                        items = listOf(
                            ParsedReceiptItemCandidate(
                                description = "Coffee",
                                amount = ParsedMoneyCandidate(rawText = "100.00", amountMinor = 10000),
                            ),
                        ),
                        warnings = emptyList(),
                    ),
                ),
            ),
            reviewRepository = FakeReviewRepository(),
            ioDispatcher = mainDispatcherRule.dispatcher,
        )

        seedCapturedImage(viewModel)
        viewModel.onOcrRequested()
        advanceUntilIdle()

        assertEquals(ParserPhase.Success, viewModel.uiState.parser.phase)
        assertEquals("Merchant Example", viewModel.uiState.parser.candidate?.merchant)
        assertTrue(viewModel.uiState.canContinueToReview)
    }

    @Test
    fun `remote failure creates manual review candidate so local save flow is not blocked`() = runTest {
        val reviewRepository = FakeReviewRepository()
        val viewModel = ScanViewModel(
            repository = FakeScanRepository(),
            parserService = FakeReceiptParserService(),
            processService = FakeReceiptProcessService(
                result = RemoteProcessResult.Failure("Receipt processing failed."),
            ),
            reviewRepository = reviewRepository,
            ioDispatcher = mainDispatcherRule.dispatcher,
        )

        seedCapturedImage(viewModel)
        viewModel.onOcrRequested()
        advanceUntilIdle()

        assertEquals(ParserPhase.Partial, viewModel.uiState.parser.phase)
        assertEquals("Processing unavailable. Continue with manual review.", viewModel.uiState.parser.status)
        assertEquals("Receipt processing failed.", viewModel.uiState.parser.candidate?.warnings?.single())
        assertEquals("REMOTE_PROCESS_UNAVAILABLE", viewModel.uiState.parser.candidate?.warningCodes?.single())
        assertNotNull(viewModel.uiState.parser.candidate)
        assertTrue(viewModel.uiState.canContinueToReview)
        assertEquals(viewModel.uiState.parser.candidate, reviewRepository.latestCandidate)
    }

    @Test
    fun `capture failure is visible and retry is recoverable`() {
        val viewModel = ScanViewModel(
            repository = FakeScanRepository(),
            parserService = FakeReceiptParserService(),
            processService = FakeReceiptProcessService(),
            reviewRepository = FakeReviewRepository(),
            ioDispatcher = mainDispatcherRule.dispatcher,
        )

        viewModel.onPermissionUpdated(granted = true, canRequestPermissionAgain = true)
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
}

private class FakeReviewRepository : ReviewRepository {
    var latestCandidate: ParsedReceiptCandidate? = null

    override fun loadDraft(): com.snapledger.feature.review.domain.ReviewUiState {
        return com.snapledger.feature.review.domain.ReviewUiState()
    }

    override fun storeParsedCandidate(candidate: ParsedReceiptCandidate?) {
        latestCandidate = candidate
    }

    override suspend fun saveReviewedReceipt(uiState: com.snapledger.feature.review.domain.ReviewUiState): ReviewSaveResult {
        return ReviewSaveResult.ValidationFailed(uiState)
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

private class FakeReceiptParserService : ReceiptParserService {
    override fun parse(lines: List<com.snapledger.feature.scan.domain.NormalizedOcrLine>): ParsedReceiptCandidate {
        return ParsedReceiptCandidate(
            merchant = null,
            expenseDate = null,
            totalAmount = null,
            items = emptyList(),
            warnings = emptyList(),
        )
    }
}

private class FakeReceiptProcessService(
    private val result: RemoteProcessResult = RemoteProcessResult.Failure("offline"),
) : ReceiptProcessClient {
    override suspend fun processReceipt(capturedImage: CapturedImageMetadata): RemoteProcessResult {
        return result
    }
}

private fun seedCapturedImage(viewModel: ScanViewModel) {
    viewModel.onPermissionUpdated(granted = true, canRequestPermissionAgain = true)
    viewModel.onCameraPreviewReady()
    viewModel.onCaptureSucceeded(
        outputPath = "/tmp/fake.jpg",
        savedUri = "file:///tmp/fake.jpg",
    )
}
