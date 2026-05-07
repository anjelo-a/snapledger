package com.snapledger.feature.scan.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.snapledger.feature.scan.domain.CapturedImageMetadata
import com.snapledger.feature.scan.domain.NormalizedBoundingBox
import com.snapledger.feature.scan.domain.NormalizedOcrLine
import com.snapledger.feature.scan.domain.OcrExtractionMetadata
import java.io.File
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

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
        val sourceBitmap = createBitmap(capturedImage) ?: return ReceiptOcrResult.Failure(
            message = "Captured image is unreadable. Retry capture before running OCR.",
        )
        val preprocessedBitmap = preprocessForOcr(sourceBitmap)

        val advisories = qualityAdvisories(preprocessedBitmap)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        return try {
            val directPass = createInputImageFromUri(capturedImage)?.let { safeImage ->
                withTimeoutOrNull(OCR_PASS_TIMEOUT_MS) {
                    val recognizedText = recognizer.process(safeImage).awaitResult()
                    normalizeRecognizedText(
                        recognizedText = recognizedText,
                        metadata = metadata,
                    )
                }
            }
            val topBandPasses = runZonePasses(
                recognizer = recognizer,
                sourceBitmap = preprocessedBitmap,
                metadata = metadata,
                viewport = OcrViewport(left = 0f, top = 0f, right = 1f, bottom = 0.34f),
            )
            val bottomRightPasses = runZonePasses(
                recognizer = recognizer,
                sourceBitmap = preprocessedBitmap,
                metadata = metadata,
                viewport = OcrViewport(left = 0.48f, top = 0.58f, right = 1f, bottom = 1f),
            )
            val rightAmountColumnPasses = runZonePasses(
                recognizer = recognizer,
                sourceBitmap = preprocessedBitmap,
                metadata = metadata,
                viewport = OcrViewport(left = 0.64f, top = 0.22f, right = 1f, bottom = 0.9f),
            )
            val fullRightStripPasses = runZonePasses(
                recognizer = recognizer,
                sourceBitmap = preprocessedBitmap,
                metadata = metadata,
                viewport = OcrViewport(left = 0.72f, top = 0.0f, right = 1f, bottom = 1f),
            )
            val variantPasses = buildOcrVariants(preprocessedBitmap).mapNotNull { variant ->
                withTimeoutOrNull(OCR_PASS_TIMEOUT_MS) {
                    val recognizedText = recognizer.process(InputImage.fromBitmap(variant, 0)).awaitResult()
                    normalizeRecognizedText(
                        recognizedText = recognizedText,
                        metadata = metadata,
                    )
                }
            }
            val passes = buildList {
                addAll(topBandPasses)
                addAll(bottomRightPasses)
                addAll(rightAmountColumnPasses)
                addAll(fullRightStripPasses)
                if (directPass != null) add(directPass)
                addAll(variantPasses)
            }

            if (passes.isEmpty()) {
                return ReceiptOcrResult.Failure(
                    message = "OCR timed out while processing faded-receipt recovery passes.",
                )
            }

            val mergedBase = mergeRecognitions(passes)
            val merged = mergedBase.copy(
                warningMessages = (
                    mergedBase.warningMessages +
                        if (directPass != null && looksStrong(directPass)) {
                            listOf(
                                "Direct OCR pass selected as primary for readability.",
                                "Region OCR passes were also evaluated for top/date and bottom-total recovery.",
                            )
                        } else {
                            listOf("Region OCR passes were also evaluated for top/date and bottom-total recovery.")
                        }
                    ).distinct(),
            )
            when {
                merged.lines.isEmpty() -> {
                    ReceiptOcrResult.Empty(
                        metadata = metadata,
                        message = merged.emptyMessage,
                        warningMessages = (merged.warningMessages + advisories).distinct(),
                    )
                }

                else -> {
                    ReceiptOcrResult.Success(
                        lines = merged.lines,
                        metadata = metadata,
                        warningMessages = (merged.warningMessages + advisories).distinct(),
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

    private fun createBitmap(capturedImage: CapturedImageMetadata): Bitmap? {
        val imageFile = File(capturedImage.absolutePath)
        if (!imageFile.exists() || !imageFile.canRead()) {
            return null
        }

        val imageUri = Uri.parse(capturedImage.contentUri).takeIf { it.scheme != null }
            ?: Uri.fromFile(imageFile)

        val fromFilePath = BitmapFactory.decodeFile(imageFile.absolutePath)
        if (fromFilePath != null) {
            return rotateByExifIfNeeded(imageFile.absolutePath, fromFilePath)
        }
        return runCatching {
            val stream: InputStream = context.contentResolver.openInputStream(imageUri) ?: return null
            stream.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    private fun createInputImageFromUri(capturedImage: CapturedImageMetadata): InputImage? {
        val imageFile = File(capturedImage.absolutePath)
        val imageUri = Uri.parse(capturedImage.contentUri).takeIf { it.scheme != null }
            ?: Uri.fromFile(imageFile)
        return runCatching { InputImage.fromFilePath(context, imageUri) }.getOrNull()
    }
}

private suspend fun runZonePasses(
    recognizer: TextRecognizer,
    sourceBitmap: Bitmap,
    metadata: OcrExtractionMetadata,
    viewport: OcrViewport,
): List<NormalizedRecognition> {
    val cropped = cropNormalized(sourceBitmap, viewport) ?: return emptyList()
    return buildOcrVariants(cropped).mapNotNull { variant ->
        withTimeoutOrNull(OCR_PASS_TIMEOUT_MS) {
            val recognizedText = recognizer.process(InputImage.fromBitmap(variant, 0)).awaitResult()
            normalizeRecognizedText(
                recognizedText = recognizedText,
                metadata = metadata,
                viewport = viewport,
                viewportWidthPx = variant.width,
                viewportHeightPx = variant.height,
            )
        }
    }
}

private data class NormalizedRecognition(
    val lines: List<NormalizedOcrLine>,
    val warningMessages: List<String>,
    val emptyMessage: String,
)

private data class OcrViewport(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

private fun normalizeRecognizedText(
    recognizedText: Text,
    metadata: OcrExtractionMetadata,
    viewport: OcrViewport? = null,
    viewportWidthPx: Int? = null,
    viewportHeightPx: Int? = null,
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
                    bbox = line.boundingBox?.toNormalizedBoundingBox(
                        imageWidth = viewportWidthPx ?: metadata.widthPx,
                        imageHeight = viewportHeightPx ?: metadata.heightPx,
                        viewport = viewport,
                    ),
                    ocrConfidence = null,
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

private fun android.graphics.Rect.toNormalizedBoundingBox(
    imageWidth: Int?,
    imageHeight: Int?,
    viewport: OcrViewport? = null,
): NormalizedBoundingBox? {
    val w = imageWidth ?: return null
    val h = imageHeight ?: return null
    if (w <= 0 || h <= 0) return null
    val base = NormalizedBoundingBox(
        left = (left.toFloat() / w).coerceIn(0f, 1f),
        top = (top.toFloat() / h).coerceIn(0f, 1f),
        right = (right.toFloat() / w).coerceIn(0f, 1f),
        bottom = (bottom.toFloat() / h).coerceIn(0f, 1f),
    )
    if (viewport == null) return base
    val viewportWidth = (viewport.right - viewport.left).coerceAtLeast(0.01f)
    val viewportHeight = (viewport.bottom - viewport.top).coerceAtLeast(0.01f)
    return NormalizedBoundingBox(
        left = (viewport.left + base.left * viewportWidth).coerceIn(0f, 1f),
        top = (viewport.top + base.top * viewportHeight).coerceIn(0f, 1f),
        right = (viewport.left + base.right * viewportWidth).coerceIn(0f, 1f),
        bottom = (viewport.top + base.bottom * viewportHeight).coerceIn(0f, 1f),
    )
}

private fun mergeRecognitions(recognitions: List<NormalizedRecognition>): NormalizedRecognition {
    val ranked = recognitions
        .map { it to scoreRecognition(it) }
        .sortedByDescending { it.second }
        .map { it.first }

    val primary = ranked.first()
    val mergedLines = primary.lines.mapIndexed { idx, line -> line.copy(index = idx) }.toMutableList()
    val seen = mergedLines.map { it.text.lowercase() }.toMutableSet()

    var moneyCount = mergedLines.count { hasMoneyAmount(it.text) }
    var hasDate = mergedLines.any { hasDate(it.text) }

    ranked.drop(1).forEach { pass ->
        pass.lines.forEach { line ->
            val key = line.text.lowercase()
            if (key in seen) return@forEach
            val hasAmountLike = hasAmount(line.text)
            val hasMoneyLike = hasMoneyAmount(line.text)
            val hasDateLike = hasDate(line.text)
            val hasLabeledTotalLike = looksLikeTotalLabel(line.text)
            val valuable = hasAmountLike || hasDateLike || hasLabeledTotalLike || looksLikeMerchantSignal(line) || looksLikeDateLabel(line.text)
            val zoneUseful = isTopBandLine(line) || isBottomRightLine(line)
            if (!valuable && !zoneUseful) return@forEach
            if (likelyNoiseLine(line.text) && !valuable) return@forEach

            if (hasMoneyLike && moneyCount >= 28 && !hasLabeledTotalLike) return@forEach
            if (hasDateLike && hasDate && !looksLikeDateLabel(line.text)) return@forEach

            seen += key
            mergedLines += line.copy(index = mergedLines.size)
            if (hasMoneyLike) moneyCount += 1
            if (hasDateLike) hasDate = true
        }
    }

    val warnings = mutableListOf<String>()
    if (recognitions.size > 1) {
        warnings += "Faded-ink OCR recovery used multiple deterministic threshold passes."
    }

    val emptyMessage = if (mergedLines.isEmpty()) {
        recognitions.firstOrNull()?.emptyMessage ?: "No text was detected in the captured receipt image."
    } else {
        ""
    }

    return NormalizedRecognition(
        lines = mergedLines,
        warningMessages = warnings,
        emptyMessage = emptyMessage,
    )
}

private fun scoreRecognition(recognition: NormalizedRecognition): Int {
    val lines = recognition.lines.size.coerceAtMost(100)
    val amounts = recognition.lines.count { hasMoneyAmount(it.text) }.coerceAtMost(20)
    val dates = recognition.lines.count { hasDate(it.text) }.coerceAtMost(4)
    val badNoise = recognition.lines.count { likelyNoiseLine(it.text) }.coerceAtMost(20)
    return lines * 2 + amounts * 6 + dates * 8 - badNoise * 2
}

private fun hasAmount(text: String): Boolean {
    return Regex("(?:[$₱]|PHP\\s*)?\\d+(?:,\\d{3})*(?:[.,]\\d{1,2})?").containsMatchIn(text)
}

private fun hasMoneyAmount(text: String): Boolean {
    return Regex("(?:[$₱]|PHP\\s*)?\\d+[.,]\\d{2}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
}

private fun hasDate(text: String): Boolean {
    return Regex("\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b|\\b\\d{4}-\\d{2}-\\d{2}\\b").containsMatchIn(text)
}

private fun likelyNoiseLine(text: String): Boolean {
    val clean = text.trim()
    if (clean.isEmpty()) return true
    val alphaNum = clean.count { it.isLetterOrDigit() }
    val symbols = clean.count { !it.isLetterOrDigit() && !it.isWhitespace() }
    return alphaNum > 0 && symbols > alphaNum
}

private fun looksStrong(recognition: NormalizedRecognition): Boolean {
    val lineCount = recognition.lines.size
    val amountCount = recognition.lines.count { hasMoneyAmount(it.text) }
    return lineCount >= 4 && amountCount >= 1
}

private fun looksLikeMerchantSignal(line: NormalizedOcrLine): Boolean {
    val text = line.text.trim()
    if (text.length < 3) return false
    val lower = text.lowercase()
    if (lower.contains("total") || lower.contains("subtotal") || lower.contains("tax")) return false
    if (lower.contains("tin") || lower.contains("sn") || lower.contains("auth") || lower.contains("ref")) return false
    val letters = text.count { it.isLetter() }
    val digits = text.count { it.isDigit() }
    val hasAmountLike = hasAmount(text)
    return letters >= 4 && digits <= 2 && !hasAmountLike
}

private fun looksLikeDateLabel(text: String): Boolean {
    val lower = text.lowercase()
    return lower.contains("date") || lower.contains("time") || lower.contains("txn")
}

private fun looksLikeTotalLabel(text: String): Boolean {
    val lower = text.lowercase()
    val normalized = lower
        .replace('0', 'o')
        .replace('1', 'l')
        .replace('5', 's')
        .replace('8', 'b')
        .replace('6', 'g')
        .replace('7', 't')
    return normalized.contains("total") ||
        normalized.contains("amount due") ||
        normalized.contains("balance due")
}

private fun isTopBandLine(line: NormalizedOcrLine): Boolean {
    val box = line.bbox ?: return line.index <= 8
    return ((box.top + box.bottom) / 2f) <= 0.38f
}

private fun isBottomRightLine(line: NormalizedOcrLine): Boolean {
    val box = line.bbox ?: return false
    val cx = (box.left + box.right) / 2f
    val cy = (box.top + box.bottom) / 2f
    return cx >= 0.52f && cy >= 0.58f
}

private fun buildOcrVariants(bitmap: Bitmap): List<Bitmap> {
    val scaled = downscale(bitmap)
    val variants = mutableListOf<Bitmap>()
    variants += toGrayscale(scaled)
    variants += mildContrastStretch(scaled)
    variants += adaptiveBinarize(scaled, -8)
    variants += adaptiveBinarize(scaled, -14)
    variants += adaptiveBinarize(scaled, -20)
    return variants
}

private fun preprocessForOcr(bitmap: Bitmap): Bitmap {
    val flattened = tryPerspectiveFlatten(bitmap)
    val deglared = reduceGlare(flattened)
    return deskewBitmap(deglared)
}

private fun tryPerspectiveFlatten(bitmap: Bitmap): Bitmap {
    val quad = detectReceiptQuad(bitmap) ?: return bitmap
    val widthTop = distance(quad[0], quad[1])
    val widthBottom = distance(quad[3], quad[2])
    val targetW = maxOf(widthTop, widthBottom).toInt().coerceAtLeast(1)
    val heightLeft = distance(quad[0], quad[3])
    val heightRight = distance(quad[1], quad[2])
    val targetH = maxOf(heightLeft, heightRight).toInt().coerceAtLeast(1)
    val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
    val matrix = Matrix()
    val src = floatArrayOf(
        quad[0].x, quad[0].y,
        quad[1].x, quad[1].y,
        quad[2].x, quad[2].y,
        quad[3].x, quad[3].y,
    )
    val dst = floatArrayOf(
        0f, 0f,
        targetW.toFloat(), 0f,
        targetW.toFloat(), targetH.toFloat(),
        0f, targetH.toFloat(),
    )
    if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return bitmap
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(bitmap, matrix, paint)
    return out
}

private fun detectReceiptQuad(bitmap: Bitmap): Array<PointF>? {
    val gray = toGrayMatrix(downscale(bitmap))
    val h = gray.size
    val w = gray.firstOrNull()?.size ?: return null
    if (h < 20 || w < 20) return null
    val mask = Array(h) { BooleanArray(w) }
    for (y in 0 until h) {
        for (x in 0 until w) {
            mask[y][x] = gray[y][x] < 245
        }
    }
    fun firstFromTop(col: Int): Int? {
        for (y in 0 until h) if (mask[y][col]) return y
        return null
    }
    fun firstFromBottom(col: Int): Int? {
        for (y in h - 1 downTo 0) if (mask[y][col]) return y
        return null
    }
    val leftX = (w * 0.15f).toInt()
    val rightX = (w * 0.85f).toInt()
    val topYLeft = firstFromTop(leftX) ?: return null
    val topYRight = firstFromTop(rightX) ?: return null
    val bottomYRight = firstFromBottom(rightX) ?: return null
    val bottomYLeft = firstFromBottom(leftX) ?: return null
    val scaleX = bitmap.width.toFloat() / w.toFloat()
    val scaleY = bitmap.height.toFloat() / h.toFloat()
    return arrayOf(
        PointF(leftX * scaleX, topYLeft * scaleY),
        PointF(rightX * scaleX, topYRight * scaleY),
        PointF(rightX * scaleX, bottomYRight * scaleY),
        PointF(leftX * scaleX, bottomYLeft * scaleY),
    )
}

private fun deskewBitmap(bitmap: Bitmap): Bitmap {
    val bestAngle = estimateDeskewAngle(bitmap)
    if (kotlin.math.abs(bestAngle) < 0.4f) return bitmap
    val matrix = Matrix().apply { postRotate(bestAngle) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun estimateDeskewAngle(bitmap: Bitmap): Float {
    val gray = toGrayMatrix(downscale(bitmap))
    val h = gray.size
    val w = gray.firstOrNull()?.size ?: return 0f
    var bestAngle = 0f
    var bestScore = Double.NEGATIVE_INFINITY
    for (a in -8..8) {
        val angle = a.toFloat()
        val rad = Math.toRadians(angle.toDouble())
        val sinA = kotlin.math.sin(rad)
        val cosA = kotlin.math.cos(rad)
        val bins = IntArray(h + w)
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                if (gray[y][x] > 175) continue
                val ry = (x * sinA + y * cosA).toInt() + w / 2
                if (ry in bins.indices) bins[ry]++
            }
        }
        val mean = bins.average()
        val variance = bins.fold(0.0) { acc, v ->
            val d = v - mean
            acc + d * d
        } / bins.size.toDouble()
        if (variance > bestScore) {
            bestScore = variance
            bestAngle = angle
        }
    }
    return bestAngle
}

private fun reduceGlare(bitmap: Bitmap): Bitmap {
    val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            val p = bitmap.getPixel(x, y)
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val brightness = (r + g + b) / 3
            val saturation = maxOf(r, g, b) - minOf(r, g, b)
            if (brightness >= 238 && saturation <= 18) {
                val toned = (brightness * 0.82f).toInt().coerceIn(0, 255)
                out.setPixel(x, y, Color.argb(255, toned, toned, toned))
            } else {
                out.setPixel(x, y, p)
            }
        }
    }
    return out
}

private fun distance(a: PointF, b: PointF): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

private fun cropNormalized(bitmap: Bitmap, viewport: OcrViewport): Bitmap? {
    val left = (viewport.left.coerceIn(0f, 1f) * bitmap.width).toInt()
    val top = (viewport.top.coerceIn(0f, 1f) * bitmap.height).toInt()
    val right = (viewport.right.coerceIn(0f, 1f) * bitmap.width).toInt()
    val bottom = (viewport.bottom.coerceIn(0f, 1f) * bitmap.height).toInt()
    if (left >= right || top >= bottom) return null
    if (left !in 0 until bitmap.width || top !in 0 until bitmap.height) return null
    val width = min(right - left, bitmap.width - left).coerceAtLeast(1)
    val height = min(bottom - top, bitmap.height - top).coerceAtLeast(1)
    return Bitmap.createBitmap(bitmap, left, top, width, height)
}

private fun rotateByExifIfNeeded(path: String, bitmap: Bitmap): Bitmap {
    val rotation = runCatching {
        val exif = androidx.exifinterface.media.ExifInterface(path)
        when (exif.getAttributeInt(
            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
        )) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)

    if (rotation == 0f) return bitmap
    val matrix = Matrix().apply { postRotate(rotation) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun qualityAdvisories(bitmap: Bitmap): List<String> {
    val advisories = mutableListOf<String>()
    if (isFadedInk(bitmap)) {
        advisories += "faded_ink_detected"
    }
    if (highGlare(bitmap)) {
        advisories += "glare_risk"
    }
    return advisories
}

private fun downscale(bitmap: Bitmap): Bitmap {
    val maxDim = max(bitmap.width, bitmap.height)
    if (maxDim <= 1400) return bitmap
    val scale = 1400f / maxDim.toFloat()
    val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, w, h, true)
}

private fun toGrayscale(bitmap: Bitmap): Bitmap {
    val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            val p = bitmap.getPixel(x, y)
            val g = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)).toInt()
            out.setPixel(x, y, Color.argb(255, g, g, g))
        }
    }
    return out
}

private fun mildContrastStretch(bitmap: Bitmap): Bitmap {
    val gray = toGrayMatrix(bitmap)
    var minV = 255
    var maxV = 0
    for (row in gray) {
        for (v in row) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
    }
    if (maxV - minV < 10) return toGrayscale(bitmap)

    val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val range = (maxV - minV).toFloat()
    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            val norm = (((gray[y][x] - minV) / range) * 255f).toInt().coerceIn(0, 255)
            out.setPixel(x, y, Color.argb(255, norm, norm, norm))
        }
    }
    return out
}

private fun adaptiveBinarize(bitmap: Bitmap, offset: Int): Bitmap {
    val gray = toGrayMatrix(bitmap)
    val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val tile = 24

    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val xEnd = min(bitmap.width, x + tile)
            val yEnd = min(bitmap.height, y + tile)
            var sum = 0
            var count = 0
            for (yy in y until yEnd) {
                for (xx in x until xEnd) {
                    sum += gray[yy][xx]
                    count += 1
                }
            }
            val mean = if (count == 0) 127 else sum / count
            for (yy in y until yEnd) {
                for (xx in x until xEnd) {
                    val v = if (gray[yy][xx] < mean + offset) 0 else 255
                    out.setPixel(xx, yy, Color.argb(255, v, v, v))
                }
            }
            x += tile
        }
        y += tile
    }
    return out
}

private fun toGrayMatrix(bitmap: Bitmap): Array<IntArray> {
    val gray = Array(bitmap.height) { IntArray(bitmap.width) }
    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            val p = bitmap.getPixel(x, y)
            gray[y][x] = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)).toInt()
        }
    }
    return gray
}

private fun isFadedInk(bitmap: Bitmap): Boolean {
    val gray = toGrayMatrix(bitmap)
    val stepX = max(1, bitmap.width / 180)
    val stepY = max(1, bitmap.height / 180)
    var sum = 0.0
    var sumSq = 0.0
    var edges = 0
    var n = 0

    var y = 1
    while (y < bitmap.height - 1) {
        var x = 1
        while (x < bitmap.width - 1) {
            val v = gray[y][x].toDouble()
            sum += v
            sumSq += v * v
            val gx = kotlin.math.abs(gray[y][x + 1] - gray[y][x - 1])
            val gy = kotlin.math.abs(gray[y + 1][x] - gray[y - 1][x])
            if (gx + gy > 36) edges += 1
            n += 1
            x += stepX
        }
        y += stepY
    }

    if (n == 0) return false
    val mean = sum / n
    val variance = (sumSq / n) - (mean * mean)
    val edgeDensity = edges.toDouble() / n.toDouble()
    return mean > 165.0 && variance < 1700.0 && edgeDensity < 0.12
}

private fun highGlare(bitmap: Bitmap): Boolean {
    val stepX = max(1, bitmap.width / 250)
    val stepY = max(1, bitmap.height / 250)
    var glare = 0
    var total = 0

    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val p = bitmap.getPixel(x, y)
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val brightness = (r + g + b) / 3
            val saturation = max(max(r, g), b) - min(min(r, g), b)
            if (brightness >= 242 && saturation < 16) glare += 1
            total += 1
            x += stepX
        }
        y += stepY
    }

    if (total == 0) return false
    return glare.toFloat() / total.toFloat() > 0.55f
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

private const val OCR_PASS_TIMEOUT_MS = 4_000L
