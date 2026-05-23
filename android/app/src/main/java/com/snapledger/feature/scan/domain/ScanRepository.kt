package com.snapledger.feature.scan.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import java.io.File
import kotlin.math.sqrt

interface ScanRepository {
    fun loadInitialState(): ScanUiState

    fun createPendingCapture(cacheDirectory: File, timestampMillis: Long): PendingCapture

    fun readCapturedImageMetadata(
        outputPath: String,
        savedUri: String?,
        source: CaptureSource = CaptureSource.Camera,
    ): CapturedImageMetadata

    fun evaluateUploadQuality(capturedImage: CapturedImageMetadata): UploadQualityGateResult
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
        source: CaptureSource,
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
            source = source,
            capturedAtMillis = imageFile.lastModified(),
            fileSizeBytes = imageFile.length(),
            widthPx = bounds.outWidth.takeIf { it > 0 },
            heightPx = bounds.outHeight.takeIf { it > 0 },
        )
    }

    override fun evaluateUploadQuality(capturedImage: CapturedImageMetadata): UploadQualityGateResult {
        val failures = mutableListOf<String>()
        val width = capturedImage.widthPx
        val height = capturedImage.heightPx
        val minEdge = listOfNotNull(width, height).minOrNull()
        val pixelCount = if (width != null && height != null) width.toLong() * height.toLong() else null

        if (capturedImage.fileSizeBytes < MIN_FILE_SIZE_BYTES) {
            failures += "Image is too small/compressed. Use a higher-quality photo."
        }
        if (minEdge == null || minEdge < MIN_SHORT_EDGE_PX) {
            failures += "Image resolution is too low. Capture a clearer photo."
        }
        if (pixelCount == null || pixelCount < MIN_PIXEL_COUNT) {
            failures += "Image does not contain enough detail for reliable extraction."
        }

        val focusScore = estimateFocusScore(capturedImage.absolutePath)
        if (focusScore == null || focusScore < MIN_FOCUS_SCORE) {
            failures += "Image appears blurry. Retake with steadier focus."
        }

        return if (failures.isEmpty()) {
            UploadQualityGateResult.Pass(focusScore = focusScore)
        } else {
            UploadQualityGateResult.Fail(
                reasons = failures.distinct(),
                focusScore = focusScore,
            )
        }
    }

    private fun estimateFocusScore(path: String): Double? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 4
        }
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
        return try {
            gradientVariance(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun gradientVariance(bitmap: Bitmap): Double {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 3 || height < 3) return 0.0

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val luminance = IntArray(width * height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = color shr 16 and 0xFF
            val g = color shr 8 and 0xFF
            val b = color and 0xFF
            luminance[i] = (r * 299 + g * 587 + b * 114) / 1000
        }

        var count = 0
        var sum = 0.0
        var sumSq = 0.0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = y * width + x
                val gx = luminance[center + 1] - luminance[center - 1]
                val gy = luminance[center + width] - luminance[center - width]
                val magnitude = sqrt((gx * gx + gy * gy).toDouble())
                sum += magnitude
                sumSq += magnitude * magnitude
                count += 1
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }

    companion object {
        private const val MIN_FILE_SIZE_BYTES = 120_000L
        private const val MIN_SHORT_EDGE_PX = 900
        private const val MIN_PIXEL_COUNT = 1_200_000L
        private const val MIN_FOCUS_SCORE = 220.0
    }
}

sealed interface UploadQualityGateResult {
    data class Pass(val focusScore: Double?) : UploadQualityGateResult
    data class Fail(val reasons: List<String>, val focusScore: Double?) : UploadQualityGateResult
}
