package com.snapledger.feature.scan.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.snapledger.feature.scan.domain.CapturedImageMetadata
import com.snapledger.feature.scan.domain.NormalizedOcrLine
import com.snapledger.feature.scan.domain.OcrExtractionMetadata
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

interface ReceiptOcrService {
    suspend fun extractReceiptText(capturedImage: CapturedImageMetadata): ReceiptOcrResult
}

sealed interface ReceiptOcrResult {
    data class Success(
        val lines: List<NormalizedOcrLine>,
        val metadata: OcrExtractionMetadata,
        val warningMessages: List<String> = emptyList(),
    ) : ReceiptOcrResult

    data class Empty(
        val metadata: OcrExtractionMetadata,
        val message: String,
        val warningMessages: List<String> = emptyList(),
    ) : ReceiptOcrResult

    data class Failure(
        val message: String,
    ) : ReceiptOcrResult
}

class MlKitReceiptOcrService(
    private val context: Context,
) : ReceiptOcrService {
    override suspend fun extractReceiptText(capturedImage: CapturedImageMetadata): ReceiptOcrResult {
        val metadata = capturedImage.toOcrExtractionMetadata()
        val inputImage = createInputImage(capturedImage) ?: return ReceiptOcrResult.Failure(
            message = "Captured image is unreadable. Retry capture before running OCR.",
        )

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val recognizedText = recognizer.process(inputImage).awaitResult()
            val normalized = normalizeRecognizedText(
                recognizedText = recognizedText,
                metadata = metadata,
            )
            when {
                normalized.lines.isEmpty() -> {
                    ReceiptOcrResult.Empty(
                        metadata = metadata,
                        message = normalized.emptyMessage,
                        warningMessages = normalized.warningMessages,
                    )
                }

                else -> {
                    ReceiptOcrResult.Success(
                        lines = normalized.lines,
                        metadata = metadata,
                        warningMessages = normalized.warningMessages,
                    )
                }
            }
        } catch (error: Exception) {
            ReceiptOcrResult.Failure(
                message = error.message ?: "ML Kit text recognition failed.",
            )
        } finally {
            recognizer.close()
        }
    }

    private fun createInputImage(capturedImage: CapturedImageMetadata): InputImage? {
        val imageFile = File(capturedImage.absolutePath)
        if (!imageFile.exists() || !imageFile.canRead()) {
            return null
        }

        val imageUri = Uri.parse(capturedImage.contentUri).takeIf { it.scheme != null }
            ?: Uri.fromFile(imageFile)

        return runCatching {
            InputImage.fromFilePath(context, imageUri)
        }.getOrElse {
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null
            InputImage.fromBitmap(bitmap, 0)
        }
    }
}

private data class NormalizedRecognition(
    val lines: List<NormalizedOcrLine>,
    val warningMessages: List<String>,
    val emptyMessage: String,
)

private fun normalizeRecognizedText(
    recognizedText: Text,
    metadata: OcrExtractionMetadata,
): NormalizedRecognition {
    val warnings = mutableListOf<String>()
    val normalizedLines = mutableListOf<NormalizedOcrLine>()
    var droppedLineCount = 0
    var rawIndex = 0

    recognizedText.textBlocks.forEach { block ->
        block.lines.forEach { line ->
            val normalizedText = line.text
                .replace(Regex("\\s+"), " ")
                .trim()
            if (normalizedText.isEmpty()) {
                droppedLineCount += 1
            } else {
                normalizedLines += NormalizedOcrLine(
                    index = rawIndex,
                    text = normalizedText,
                )
            }
            rawIndex += 1
        }
    }

    if (droppedLineCount > 0) {
        warnings += "Ignored $droppedLineCount blank OCR line(s) during normalization."
    }
    if (metadata.widthPx == null || metadata.heightPx == null) {
        warnings += "Image dimensions were unavailable, so OCR metadata is partial."
    }

    val emptyMessage = when {
        recognizedText.text.isBlank() -> "No text was detected in the captured receipt image."
        normalizedLines.isEmpty() -> "OCR detected content, but no normalized lines were usable."
        else -> ""
    }

    return NormalizedRecognition(
        lines = normalizedLines,
        warningMessages = warnings,
        emptyMessage = emptyMessage,
    )
}

private fun CapturedImageMetadata.toOcrExtractionMetadata(): OcrExtractionMetadata {
    return OcrExtractionMetadata(
        capturedAtMillis = capturedAtMillis,
        widthPx = widthPx,
        heightPx = heightPx,
        fileSizeBytes = fileSizeBytes,
        sourcePath = absolutePath,
        sourceUri = contentUri,
    )
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            task.isSuccessful -> continuation.resume(task.result)
            error != null -> continuation.resumeWithException(error)
            else -> continuation.resumeWithException(
                IllegalStateException("ML Kit task failed without an exception."),
            )
        }
    }
}
