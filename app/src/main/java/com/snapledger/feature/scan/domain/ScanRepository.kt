package com.snapledger.feature.scan.domain

import android.graphics.BitmapFactory
import androidx.core.net.toUri
import java.io.File

interface ScanRepository {
    fun loadInitialState(): ScanUiState

    fun createPendingCapture(cacheDirectory: File, timestampMillis: Long): PendingCapture

    fun readCapturedImageMetadata(outputPath: String, savedUri: String?): CapturedImageMetadata
}

class CameraCaptureRepository : ScanRepository {
    override fun loadInitialState(): ScanUiState = ScanUiState()

    override fun createPendingCapture(
        cacheDirectory: File,
        timestampMillis: Long,
    ): PendingCapture {
        val captureDirectory = File(cacheDirectory, "scan-captures").apply {
            mkdirs()
        }
        val outputFile = File(captureDirectory, "receipt-$timestampMillis.jpg")
        return PendingCapture(outputPath = outputFile.absolutePath)
    }

    override fun readCapturedImageMetadata(
        outputPath: String,
        savedUri: String?,
    ): CapturedImageMetadata {
        val imageFile = File(outputPath)
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)

        return CapturedImageMetadata(
            fileName = imageFile.name,
            absolutePath = imageFile.absolutePath,
            contentUri = savedUri ?: imageFile.toUri().toString(),
            capturedAtMillis = imageFile.lastModified(),
            fileSizeBytes = imageFile.length(),
            widthPx = bounds.outWidth.takeIf { it > 0 },
            heightPx = bounds.outHeight.takeIf { it > 0 },
        )
    }
}
