package com.snapledger.feature.scan.vm

import com.snapledger.feature.scan.domain.CameraPermissionState
import com.snapledger.feature.scan.domain.CapturedImageMetadata
import com.snapledger.feature.scan.domain.PendingCapture
import com.snapledger.feature.scan.domain.ScanCapturePhase
import com.snapledger.feature.scan.domain.ScanRepository
import com.snapledger.feature.scan.domain.ScanUiState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanViewModelTest {
    @Test
    fun `capture success stores metadata and enables review`() {
        val repository = FakeScanRepository()
        val viewModel = ScanViewModel(repository)

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
        val viewModel = ScanViewModel(FakeScanRepository())

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
