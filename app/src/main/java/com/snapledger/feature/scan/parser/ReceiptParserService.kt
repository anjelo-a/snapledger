package com.snapledger.feature.scan.parser

import com.snapledger.feature.scan.domain.NormalizedOcrLine
import com.snapledger.feature.scan.domain.ParsedMoneyCandidate
import com.snapledger.feature.scan.domain.ParsedReceiptCandidate
import com.snapledger.feature.scan.domain.ParsedReceiptItemCandidate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs

interface ReceiptParserService {
    fun parse(lines: List<NormalizedOcrLine>): ParsedReceiptCandidate
}

class DeterministicReceiptParserService : ReceiptParserService {
    override fun parse(lines: List<NormalizedOcrLine>): ParsedReceiptCandidate {
        val warnings = mutableListOf<String>()
        if (lines.isEmpty()) {
            return ParsedReceiptCandidate(
                merchant = null,
                expenseDate = null,
                totalAmount = null,
                items = emptyList(),
                warnings = listOf("No OCR lines were provided to the deterministic parser."),
            )
        }

        val filteredLines = lines.filterNot { isStrongNoiseLine(it.text) }
        val cleanedLines = if (filteredLines.size >= 3) filteredLines else lines
        val merchantSelection = selectMerchant(cleanedLines)
        val expenseDateSelection = selectExpenseDate(cleanedLines)
        val tableHeaderIndex = detectItemTableHeaderIndex(cleanedLines)
        val items = selectItems(
            lines = cleanedLines,
            merchantLineIndex = merchantSelection.lineIndex,
            totalLineIndex = null,
            tableHeaderIndex = tableHeaderIndex,
        )
        val totalSelection = selectTotalAmount(cleanedLines, items, tableHeaderIndex)
        val reliability = computeReliabilityScore(cleanedLines, merchantSelection, expenseDateSelection, totalSelection)
        val merchantPass = computeMerchantReliability(cleanedLines, merchantSelection) >= MIN_MERCHANT_RELIABILITY_SCORE
        val datePass = computeDateReliability(cleanedLines, expenseDateSelection) >= MIN_DATE_RELIABILITY_SCORE
        val totalPass = computeTotalReliability(cleanedLines, totalSelection) >= MIN_TOTAL_RELIABILITY_SCORE
        warnings += merchantSelection.warnings
        warnings += expenseDateSelection.warnings
        warnings += totalSelection.warnings
        if (!(merchantPass || datePass || totalPass)) {
            warnings += "Low OCR parse reliability score ($reliability/$MAX_PARSE_RELIABILITY_SCORE). Manual confirmation required."
        }
        if (items.isEmpty()) warnings += "No line items were confidently parsed from the OCR lines."
        if ((if (merchantPass) merchantSelection.value else null) == null) warnings += "Merchant could not be determined from the OCR lines."
        if ((if (datePass) expenseDateSelection.value else null) == null) warnings += "Expense date could not be determined from the OCR lines."
        if ((if (totalPass) totalSelection.value else null) == null) warnings += "Total amount could not be determined from the OCR lines."

        return ParsedReceiptCandidate(
            merchant = if (merchantPass) merchantSelection.value else null,
            expenseDate = if (datePass) expenseDateSelection.value else null,
            totalAmount = if (totalPass) totalSelection.value else null,
            items = items,
            warnings = warnings.distinct(),
        )
    }
}

private data class MerchantSelection(
    val value: String?,
    val lineIndex: Int?,
    val warnings: List<String>,
)

private data class DateSelection(
    val value: String?,
    val warnings: List<String>,
)

private data class TotalSelection(
    val value: ParsedMoneyCandidate?,
    val lineIndex: Int?,
    val warnings: List<String>,
    val confidenceScore: Int = 0,
)

private data class ScoredMerchantLine(
    val lineIndex: Int,
    val text: String,
    val score: Int,
)

private data class InferredTotal(
    val lineIndex: Int,
    val amount: ParsedMoneyCandidate,
    val score: Int,
)

private fun selectMerchant(lines: List<NormalizedOcrLine>): MerchantSelection {
    val orderedForMerchant = lines
        .sortedWith(
            compareBy<NormalizedOcrLine> { it.bbox?.top ?: 1f }
                .thenBy { it.index },
        )
        .take(10)

    val candidates = orderedForMerchant.map { line ->
        ScoredMerchantLine(line.index, line.text, scoreMerchantLine(line))
    }.sortedByDescending { it.score }

    val best = candidates.firstOrNull() ?: return MerchantSelection(null, null, emptyList())
    if (best.score <= 0) return MerchantSelection(null, null, emptyList())

    val secondBest = candidates.getOrNull(1)
    val canonicalMerchant = canonicalizeMerchant(best.text)
    val bestLower = canonicalMerchant.lowercase(Locale.US)
    if (secondBest != null && abs(best.score - secondBest.score) <= 2 && KNOWN_MERCHANT_HINTS.none { bestLower.contains(it) }) {
        return MerchantSelection(
            value = canonicalMerchant,
            lineIndex = best.lineIndex,
            warnings = listOf("Merchant was ambiguous; selected highest-confidence top candidate '$canonicalMerchant'."),
        )
    }

    return MerchantSelection(value = canonicalMerchant, lineIndex = best.lineIndex, warnings = emptyList())
}

private fun scoreMerchantLine(line: NormalizedOcrLine): Int {
    val text = line.text
    val lowercase = text.lowercase(Locale.US)
    if (text.length < 3) return Int.MIN_VALUE

    var score = 0
    val letters = text.count { it.isLetter() }
    val digits = text.count { it.isDigit() }
    val uppercaseLetters = text.count { it.isUpperCase() }

    if (line.index <= 1) score += 10
    if (isTopZone(line)) score += 20
    if (isBottomZone(line)) score -= 24
    if (isRightZone(line)) score -= 14
    if (letters >= 4) score += 12
    if (digits == 0) score += 10
    if (letters > 0 && digits > letters) score -= 28
    if (letters > 0 && letters * 100 / (letters + digits).coerceAtLeast(1) < 55) score -= 24
    if (uppercaseLetters > 0 && uppercaseLetters >= letters * 0.6) score += 8
    if (KNOWN_MERCHANT_HINTS.any { lowercase.contains(it) }) score += 8

    if (lowercase.contains("receipt") || lowercase.contains("invoice") || lowercase.contains("order")) score -= 12
    if (lowercase.contains("copy")) score -= 18
    if (lowercase.contains("total") || lowercase.contains("subtotal") || lowercase.contains("tax")) score -= 20
    if (lowercase.contains("date") || lowercase.contains("time")) score -= 16
    if (lowercase.contains("phone") || lowercase.contains("tel") || lowercase.contains("www") || lowercase.contains("http")) score -= 22
    if (lowercase.contains("street") || lowercase.contains(" st") || lowercase.contains("ave") || lowercase.contains("road") || lowercase.contains("blvd")) score -= 20
    if (containsMoney(text)) score -= 24
    if (matchesKnownDate(text) != null) score -= 18

    return score
}

private fun selectExpenseDate(lines: List<NormalizedOcrLine>): DateSelection {
    val warnings = mutableListOf<String>()
    val prioritized = lines.sortedByDescending { line ->
        val lower = line.text.lowercase(Locale.US)
        val zoneBoost = when {
            isTopZone(line) -> 2
            isMiddleZone(line) -> 1
            else -> 0
        }
        when {
            lower.contains("date") -> 3
            lower.contains("time") -> 2
            else -> 1
        } + zoneBoost
    }

    prioritized.forEach { line ->
        if (isReceiptMetaNoise(line.text)) return@forEach
        val matchedDate = matchesKnownDate(line.text) ?: return@forEach
        val year = matchedDate.normalized.take(4).toIntOrNull() ?: return@forEach
        if (year !in VALID_RECEIPT_YEAR_RANGE) return@forEach
        if (matchedDate.isAmbiguous) {
            warnings += "Expense date '${matchedDate.raw}' is ambiguous; interpreted as ${matchedDate.normalized}."
        }
        return DateSelection(matchedDate.normalized, warnings)
    }

    return DateSelection(null, emptyList())
}

private fun selectTotalAmount(
    lines: List<NormalizedOcrLine>,
    items: List<ParsedReceiptItemCandidate>,
    tableHeaderIndex: Int?,
): TotalSelection {
    val warnings = mutableListOf<String>()
    var bestCandidate: Pair<Int, ParsedMoneyCandidate>? = null
    var bestScore = Int.MIN_VALUE
    lines.forEachIndexed { index, line ->
        val lowercase = line.text.lowercase(Locale.US)
        val keywordNormalized = normalizeOcrKeywordText(lowercase)
        val containsTotalKeyword = TOTAL_KEYWORDS.any { lowercase.contains(it) || keywordNormalized.contains(it) }
        val excluded = NON_TOTAL_KEYWORDS.any { lowercase.contains(it) }
        if (excluded) return@forEachIndexed

        val inlineAmount = parseLastMoneyCandidate(line.text)
        val nextAmount = if (containsTotalKeyword && inlineAmount == null) {
            lines.getOrNull(index + 1)?.let { parseLastMoneyCandidate(it.text) }
        } else {
            null
        }

        var score = when {
            lowercase.contains("grand total") -> 120
            lowercase.contains("amount due") -> 118
            lowercase.contains("balance due") -> 116
            containsTotalKeyword && inlineAmount != null -> 112
            containsTotalKeyword && nextAmount != null -> 108
            else -> Int.MIN_VALUE
        }
        if (score != Int.MIN_VALUE) {
            if (isBottomZone(line)) score += 16
            if (isRightZone(line)) score += 12
            if (isTopZone(line)) score -= 12
            if (tableHeaderIndex != null && line.index > tableHeaderIndex) score += 3
        }

        if (score > bestScore) {
            val money = inlineAmount ?: nextAmount
            if (money != null) {
                bestCandidate = line.index to money
                bestScore = score
                if (inlineAmount == null && nextAmount != null) {
                    warnings.clear()
                    warnings += "Total amount was assembled from a multi-line total label."
                }
            }
        }
    }

    if (bestCandidate == null) {
        val fromTable = inferTotalFromItemTable(lines, tableHeaderIndex)
        if (fromTable != null && fromTable.score >= MIN_TABLE_TOTAL_INFER_SCORE) {
            warnings += "Total amount was inferred from item table amount column under detected header."
            bestCandidate = fromTable.lineIndex to fromTable.amount
            bestScore = fromTable.score
        }
    }

    if (bestCandidate == null) {
        val inferred = inferFadedTotal(lines)
        if (inferred != null && inferred.score >= 14) {
            warnings += "Total amount was inferred from faded total context near receipt bottom."
            bestCandidate = inferred.lineIndex to inferred.amount
            bestScore = inferred.score
        }
    }

    if (bestCandidate == null) {
        val standalone = inferBottomRightStandaloneTotal(lines)
        if (standalone != null) {
            warnings += "Total amount was inferred from bottom-right standalone amount."
            bestCandidate = standalone.lineIndex to standalone.amount
            bestScore = standalone.score
        }
    }

    if (bestCandidate == null) {
        val fromItems = inferTotalFromItems(items)
        if (fromItems != null) {
            warnings += "Total amount was inferred from parsed item sum."
            bestCandidate = -1 to fromItems
            bestScore = 58
        }
    }

    if (bestCandidate != null) {
        return TotalSelection(bestCandidate?.second, bestCandidate?.first, warnings, confidenceScore = bestScore)
    }

    return TotalSelection(null, null, emptyList(), confidenceScore = 0)
}

private fun inferFadedTotal(lines: List<NormalizedOcrLine>): InferredTotal? {
    return lines.mapNotNull { line ->
        val rawLower = line.text.lowercase(Locale.US)
        val normalized = normalizeOcrKeywordText(rawLower)
        if (isHardNegativeTotalLine(rawLower)) return@mapNotNull null
        if (!normalized.contains("total") && !normalized.contains("amount due") && !normalized.contains("balance due")) {
            return@mapNotNull null
        }
        val amount = parseLastMoneyCandidate(line.text) ?: return@mapNotNull null
        var score = 0
        if (line.index >= lines.size - 6) score += 8
        if (isBottomZone(line)) score += 8
        if (isRightZone(line)) score += 8
        score += when {
            normalized.contains("grand total") -> 12
            normalized.contains("amount due") -> 10
            normalized.contains("balance due") -> 9
            normalized.contains("total") -> 8
            else -> 0
        }
        InferredTotal(line.index, amount, score)
    }.maxByOrNull { it.score }
}

private fun normalizeTotalKeywordText(text: String): String {
    return text
        .replace('0', 'o')
        .replace('1', 'l')
        .replace('5', 's')
        .replace('8', 'b')
        .replace('6', 'g')
        .replace('7', 't')
}

private fun normalizeOcrKeywordText(text: String): String {
    return normalizeTotalKeywordText(text)
        .replace('|', 'l')
        .replace('!', 'i')
        .replace('@', 'a')
}

private fun detectItemTableHeaderIndex(lines: List<NormalizedOcrLine>): Int? {
    val candidate = lines.map { line ->
        val normalizedTokens = tokenize(normalizeOcrKeywordText(line.text.lowercase(Locale.US)))
        var score = 0
        if (normalizedTokens.any { similarToAny(it, HEADER_ITEM_TOKENS) }) score += 2
        if (normalizedTokens.any { similarToAny(it, HEADER_QTY_TOKENS) }) score += 2
        if (normalizedTokens.any { similarToAny(it, HEADER_PRICE_TOKENS) }) score += 2
        if (isTopZone(line)) score += 1
        if (isMiddleZone(line)) score += 2
        line.index to score
    }.maxByOrNull { it.second } ?: return null
    return if (candidate.second >= 4) candidate.first else null
}

private fun inferTotalFromItemTable(lines: List<NormalizedOcrLine>, tableHeaderIndex: Int?): InferredTotal? {
    if (tableHeaderIndex == null) return null
    val rowCandidates = lines.filter { it.index > tableHeaderIndex }
    return rowCandidates.mapNotNull { line ->
        val amount = parseLastMoneyCandidate(line.text) ?: return@mapNotNull null
        var score = 0
        if (isRightZone(line)) score += 8
        if (isBottomZone(line)) score += 8
        if (line.index >= lines.size - 8) score += 6
        if (isStrongNoiseLine(line.text)) score -= 10
        if (line.text.lowercase(Locale.US).contains("total")) score += 6
        if (amount.amountMinor <= 0L) score -= 8
        InferredTotal(line.index, amount, score)
    }.maxByOrNull { it.score }?.takeIf { it.score >= 14 }
}

private fun inferTotalFromItems(items: List<ParsedReceiptItemCandidate>): ParsedMoneyCandidate? {
    if (items.size < 2) return null
    val amounts = items.mapNotNull { it.amount?.amountMinor }.filter { it > 0L }
    if (amounts.size < 2) return null
    val sum = amounts.sum()
    if (sum <= 0L) return null
    return ParsedMoneyCandidate(
        rawText = "items_sum",
        amountMinor = sum,
    )
}

private fun inferBottomRightStandaloneTotal(lines: List<NormalizedOcrLine>): InferredTotal? {
    return lines.mapNotNull { line ->
        val amount = parseLastMoneyCandidate(line.text) ?: return@mapNotNull null
        if (amount.amountMinor <= 0L) return@mapNotNull null
        if (isReceiptMetaNoise(line.text) || looksLikeReferenceNoise(line.text)) return@mapNotNull null
        val lower = line.text.lowercase(Locale.US)
        var score = 0
        if (isBottomZone(line)) score += 14
        if (isRightZone(line)) score += 14
        if (line.text.count { it.isLetter() } <= 3) score += 4
        if (lower.contains("total")) score += 6
        if (lower.contains("cash") || lower.contains("change") || lower.contains("tax")) score -= 10
        InferredTotal(line.index, amount, score)
    }.maxByOrNull { it.score }?.takeIf { it.score >= 18 }
}

private fun computeReliabilityScore(
    lines: List<NormalizedOcrLine>,
    merchant: MerchantSelection,
    date: DateSelection,
    total: TotalSelection,
): Int {
    var score = 0
    val topLines = lines.count { isTopZone(it) }
    val rightLines = lines.count { isRightZone(it) }
    val moneyLines = lines.count { containsMoney(it.text) }
    if (merchant.value != null) score += 25
    if (date.value != null) score += 20
    if (total.value != null) score += 30
    score += total.confidenceScore.coerceAtMost(10)
    if (topLines >= 2) score += 6
    if (rightLines >= 2) score += 4
    if (moneyLines >= 2) score += 5
    return score.coerceAtMost(MAX_PARSE_RELIABILITY_SCORE)
}

private fun computeMerchantReliability(
    lines: List<NormalizedOcrLine>,
    merchant: MerchantSelection,
): Int {
    val value = merchant.value ?: return 0
    val lower = value.lowercase(Locale.US)
    var score = 0
    if (KNOWN_MERCHANT_HINTS.any { lower.contains(it) }) score += 60
    if (value.count { it.isLetter() } >= 4) score += 20
    if (value.count { it.isDigit() } == 0) score += 15
    val line = lines.firstOrNull { it.index == merchant.lineIndex }
    if (line != null && isTopZone(line)) score += 20
    return score.coerceAtMost(100)
}

private fun computeDateReliability(
    lines: List<NormalizedOcrLine>,
    date: DateSelection,
): Int {
    val value = date.value ?: return 0
    var score = 50
    val line = lines.firstOrNull { matchesKnownDate(it.text)?.normalized == value }
    if (line != null && (isTopZone(line) || isMiddleZone(line))) score += 20
    if (value.take(4).toIntOrNull() in VALID_RECEIPT_YEAR_RANGE) score += 20
    return score.coerceAtMost(100)
}

private fun computeTotalReliability(
    lines: List<NormalizedOcrLine>,
    total: TotalSelection,
): Int {
    val value = total.value ?: return 0
    var score = 45
    score += total.confidenceScore.coerceAtMost(20)
    val line = lines.firstOrNull { it.index == total.lineIndex }
    if (line != null && isBottomZone(line)) score += 25
    if (line != null && isRightZone(line)) score += 20
    if (value.amountMinor > 0L) score += 10
    return score.coerceAtMost(100)
}

private fun tokenize(text: String): List<String> {
    return text.split(Regex("[^a-z0-9]+"))
        .map { it.trim() }
        .filter { it.length >= 2 }
}

private fun similarToAny(token: String, dictionary: Set<String>): Boolean {
    return dictionary.any { token == it || token.contains(it) || it.contains(token) || levenshteinDistance(token, it) <= 1 }
}

private fun canonicalizeMerchant(raw: String): String {
    val cleaned = raw.replace(Regex("\\s+"), " ").trim()
    val lowered = normalizeOcrKeywordText(cleaned.lowercase(Locale.US))
    val compact = lowered.replace(Regex("[^a-z0-9]"), "")
    if (compact.isEmpty()) return cleaned

    val best = KNOWN_MERCHANT_CANONICAL.entries.maxByOrNull { merchant ->
        merchantMatchScore(compact, merchant.key)
    } ?: return cleaned
    return if (merchantMatchScore(compact, best.key) >= 70) best.value else cleaned
}

private fun merchantMatchScore(compact: String, key: String): Int {
    if (compact == key) return 100
    var score = 0
    if (compact.contains(key) || key.contains(compact)) score += 75
    val dist = levenshteinDistance(compact, key)
    if (dist <= 1) score += 80
    else if (dist == 2) score += 65
    else if (dist == 3) score += 50
    if (key.length >= 6 && compact.startsWith(key.take(4))) score += 25
    if (key.length >= 6 && compact.contains(key.take(5))) score += 20
    return score
}

private fun levenshteinDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val dp = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        var prev = dp[0]
        dp[0] = i
        for (j in 1..b.length) {
            val temp = dp[j]
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            dp[j] = minOf(
                dp[j] + 1,
                dp[j - 1] + 1,
                prev + cost,
            )
            prev = temp
        }
    }
    return dp[b.length]
}

private fun isHardNegativeTotalLine(text: String): Boolean {
    return text.contains("visa") ||
        text.contains("mastercard") ||
        text.contains("auth") ||
        text.contains("ref") ||
        text.contains("terminal") ||
        text.contains("trace")
}

private fun selectItems(
    lines: List<NormalizedOcrLine>,
    merchantLineIndex: Int?,
    totalLineIndex: Int?,
    tableHeaderIndex: Int?,
): List<ParsedReceiptItemCandidate> {
    val startIndex = tableHeaderIndex?.plus(1) ?: lines.indexOfFirst { it.index == merchantLineIndex }
        .takeIf { it >= 0 }
        ?.plus(1)
        ?: 0
    val endExclusive = lines.indexOfFirst { it.index == totalLineIndex }
        .takeIf { it >= 0 }
        ?: lines.size

    return lines
        .subList(startIndex.coerceAtMost(lines.size), endExclusive.coerceAtLeast(startIndex))
        .mapNotNull { line ->
            val lowercase = line.text.lowercase(Locale.US)
            if (NON_ITEM_KEYWORDS.any { lowercase.contains(it) }) return@mapNotNull null
            if (matchesKnownDate(line.text) != null) return@mapNotNull null
            if (looksLikeReferenceNoise(line.text)) return@mapNotNull null
            if (isReceiptMetaNoise(line.text)) return@mapNotNull null
            if (!(isMiddleZone(line) || isBottomZone(line))) return@mapNotNull null

            val money = parseLastMoneyCandidate(line.text) ?: return@mapNotNull null
            val amountMatch = AMOUNT_REGEX.findAll(line.text).lastOrNull() ?: return@mapNotNull null
            if (!amountMatch.value.contains(".") && !amountMatch.value.contains(",") && money.amountMinor >= 100_000L) return@mapNotNull null

            val description = line.text.substring(0, amountMatch.range.first)
                .replace(Regex("[\\s.:-]+$"), "")
                .trim()
            if (description.length < 2 || description.count { it.isLetter() } < 2) return@mapNotNull null
            if (description.length > 40) return@mapNotNull null
            val digitRatio = description.count { it.isDigit() }.toFloat() / description.length.coerceAtLeast(1)
            if (digitRatio > 0.35f) return@mapNotNull null
            if (Regex("""\b(?:tin|sn|rid|uat|auth|ref|terminal|trace|balance|bal)\b""", RegexOption.IGNORE_CASE).containsMatchIn(description)) {
                return@mapNotNull null
            }

            ParsedReceiptItemCandidate(
                description = description,
                amount = money,
            )
        }
        .filter { it.amount?.amountMinor?.let { amt -> amt in 1..250_000 } == true }
        .distinctBy { "${it.description.lowercase(Locale.US)}|${it.amount?.amountMinor}" }
        .take(20)
}

private data class MatchedDate(
    val raw: String,
    val normalized: String,
    val isAmbiguous: Boolean,
)

private fun matchesKnownDate(text: String): MatchedDate? {
    SLASH_DATE_REGEX.findAll(text).forEach { match ->
        val raw = match.value
        val parts = raw.split("/", "-")
        val first = parts[0].toIntOrNull() ?: return@forEach
        val second = parts[1].toIntOrNull() ?: return@forEach
        val year = normalizeYear(parts[2].toIntOrNull() ?: return@forEach)
        val isAmbiguous = first in 1..12 && second in 1..12
        val month = if (isAmbiguous || first in 1..12) first else second
        val day = if (isAmbiguous || first in 1..12) second else first
        val normalized = runCatching {
            LocalDate.of(year, month, day).format(DateTimeFormatter.ISO_LOCAL_DATE)
        }.getOrNull()
        if (normalized != null) {
            return MatchedDate(raw = raw, normalized = normalized, isAmbiguous = isAmbiguous)
        }
    }

    MONTH_NAME_DATE_REGEX.find(text)?.let { match ->
        val raw = match.value
        MONTH_NAME_FORMATTERS.forEach { formatter ->
            try {
                val normalized = LocalDate.parse(raw, formatter).format(DateTimeFormatter.ISO_LOCAL_DATE)
                return MatchedDate(raw = raw, normalized = normalized, isAmbiguous = false)
            } catch (_: DateTimeParseException) {
            }
        }
    }

    ISO_DATE_REGEX.find(text)?.let { match ->
        val raw = match.value
        return runCatching {
            MatchedDate(
                raw = raw,
                normalized = LocalDate.parse(raw).format(DateTimeFormatter.ISO_LOCAL_DATE),
                isAmbiguous = false,
            )
        }.getOrNull()
    }

    return null
}

private fun normalizeYear(year: Int): Int {
    return if (year < 100) {
        if (year >= 70) 1900 + year else 2000 + year
    } else {
        year
    }
}

private fun parseLastMoneyCandidate(text: String): ParsedMoneyCandidate? {
    val raw = AMOUNT_REGEX.findAll(text)
        .lastOrNull()
        ?.value
        ?.trim()
        ?: return null

    val normalized = raw
        .replace("$", "")
        .replace("₱", "")
        .replace("PHP", "", ignoreCase = true)
        .replace(",", "")
        .trim()

    val canonical = if (normalized.contains(".")) {
        val parts = normalized.split(".")
        val whole = parts.firstOrNull().orEmpty()
        val frac = parts.getOrNull(1).orEmpty()
        val normalizedFrac = when {
            frac.isEmpty() -> "00"
            frac.length == 1 -> "${frac}0"
            else -> frac.take(2)
        }
        "$whole.$normalizedFrac"
    } else {
        "$normalized.00"
    }

    val amountMinor = canonical.toBigDecimalOrNull()
        ?.movePointRight(2)
        ?.longValueExactOrNull()
        ?: return null

    return ParsedMoneyCandidate(
        rawText = raw,
        amountMinor = amountMinor,
    )
}

private fun java.math.BigDecimal.longValueExactOrNull(): Long? {
    return try {
        longValueExact()
    } catch (_: ArithmeticException) {
        null
    }
}

private fun containsMoney(text: String): Boolean = AMOUNT_REGEX.containsMatchIn(text)

private fun looksLikeReferenceNoise(text: String): Boolean {
    val lower = text.lowercase(Locale.US)
    if (isHardNegativeTotalLine(lower)) return true
    val longDigits = Regex("\\d{6,}").containsMatchIn(text)
    val fewLetters = text.count { it.isLetter() } <= 2
    return longDigits && fewLetters
}

private fun isReceiptMetaNoise(text: String): Boolean {
    val lower = text.lowercase(Locale.US)
    if (META_NOISE_TOKENS.any { lower.contains(it) }) return true
    val slashCount = text.count { it == '/' }
    val colonCount = text.count { it == ':' }
    val digitCount = text.count { it.isDigit() }
    val letterCount = text.count { it.isLetter() }
    if (slashCount >= 2 && digitCount >= 6 && letterCount <= 4) return true
    if (colonCount >= 1 && digitCount >= 5 && letterCount <= 4) return true
    return false
}

private fun isStrongNoiseLine(text: String): Boolean {
    val lower = text.lowercase(Locale.US)
    if (isReceiptMetaNoise(text)) return true
    if (looksLikeReferenceNoise(text)) return true
    if (Regex("""\b(?:tin|s/n|rid|ri0|uat|auth|ref|trace|terminal)\b""").containsMatchIn(lower)) return true
    val amountOnlyLike = Regex("""^\s*(?:[$₱]|php\s*)?\d+(?:[.,]\d{1,2})?\s*$""", RegexOption.IGNORE_CASE).matches(text)
    if (amountOnlyLike) return false
    val digitCount = text.count { it.isDigit() }
    val letterCount = text.count { it.isLetter() }
    val punctuationCount = text.count { !it.isLetterOrDigit() && !it.isWhitespace() }
    if (digitCount >= 10 && letterCount <= 2) return true
    if (punctuationCount >= 4 && letterCount <= 4 && digitCount >= 4) return true
    if (text.length <= 4 && letterCount >= 2) return false
    return false
}

private fun isTopZone(line: NormalizedOcrLine): Boolean {
    val bbox = line.bbox ?: return line.index <= 4
    return bboxCenterY(bbox) <= TOP_ZONE_MAX_Y
}

private fun isMiddleZone(line: NormalizedOcrLine): Boolean {
    val bbox = line.bbox ?: return line.index in 5..18
    val y = bboxCenterY(bbox)
    return y > TOP_ZONE_MAX_Y && y < BOTTOM_ZONE_MIN_Y
}

private fun isBottomZone(line: NormalizedOcrLine): Boolean {
    val bbox = line.bbox ?: return false
    return bboxCenterY(bbox) >= BOTTOM_ZONE_MIN_Y
}

private fun isRightZone(line: NormalizedOcrLine): Boolean {
    val bbox = line.bbox ?: return false
    return bboxCenterX(bbox) >= RIGHT_ZONE_MIN_X
}

private fun bboxCenterX(bbox: com.snapledger.feature.scan.domain.NormalizedBoundingBox): Float {
    return (bbox.left + bbox.right) / 2f
}

private fun bboxCenterY(bbox: com.snapledger.feature.scan.domain.NormalizedBoundingBox): Float {
    return (bbox.top + bbox.bottom) / 2f
}

private val TOTAL_KEYWORDS = listOf(
    "grand total",
    "total",
    "amount due",
    "balance due",
)

private val NON_TOTAL_KEYWORDS = listOf(
    "subtotal",
    "tax",
    "vat",
    "tip",
    "change",
    "cash",
    "savings",
)

private val NON_ITEM_KEYWORDS = listOf(
    "subtotal",
    "total",
    "tax",
    "vat",
    "amount due",
    "balance due",
    "change",
    "cash",
    "credit",
    "debit",
    "visa",
    "mastercard",
    "receipt",
    "invoice",
    "order",
    "phone",
    "www",
    "http",
    "savings",
)

private val AMOUNT_REGEX = Regex("""(?:[$₱]|PHP\s*)?\d+(?:,\d{3})*(?:[.,]\d{1,2})?""")
private val KNOWN_MERCHANT_HINTS = listOf(
    "starbucks",
    "sbux",
    "coffee bean",
    "cbtl",
    "mcdonald",
    "mcdo",
    "jollibee",
    "chowking",
    "greenwich",
    "mang inasal",
    "7-eleven",
    "seven eleven",
    "puregold",
    "mercury drug",
    "watsons",
)
private val META_NOISE_TOKENS = listOf(
    "tin",
    "sn",
    "s/n",
    "uat",
    "ri0",
    "rid",
    "bal",
    "vat reg",
    "permit",
    "ref",
    "auth",
)
private val ISO_DATE_REGEX = Regex("""\b\d{4}-\d{2}-\d{2}\b""")
private val SLASH_DATE_REGEX = Regex("""\b\d{1,2}[/-]\d{1,2}[/-]\d{2,4}\b""")
private val MONTH_NAME_DATE_REGEX = Regex(
    """\b(?:Jan|January|Feb|February|Mar|March|Apr|April|May|Jun|June|Jul|July|Aug|August|Sep|Sept|September|Oct|October|Nov|November|Dec|December)\s+\d{1,2},?\s+\d{2,4}\b""",
    RegexOption.IGNORE_CASE,
)
private val MONTH_NAME_FORMATTERS = listOf(
    DateTimeFormatter.ofPattern("MMM d yyyy", Locale.US),
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US),
    DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.US),
    DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US),
)
private val VALID_RECEIPT_YEAR_RANGE = 2018..2035
private const val TOP_ZONE_MAX_Y = 0.30f
private const val BOTTOM_ZONE_MIN_Y = 0.70f
private const val RIGHT_ZONE_MIN_X = 0.58f
private const val MAX_PARSE_RELIABILITY_SCORE = 100
private const val MIN_TABLE_TOTAL_INFER_SCORE = 18
private const val MIN_MERCHANT_RELIABILITY_SCORE = 54
private const val MIN_DATE_RELIABILITY_SCORE = 62
private const val MIN_TOTAL_RELIABILITY_SCORE = 52
private val HEADER_ITEM_TOKENS = setOf("item", "items", "desc", "description", "product")
private val HEADER_QTY_TOKENS = setOf("qty", "qly", "quant", "quantity")
private val HEADER_PRICE_TOKENS = setOf("price", "amount", "amt", "total")
private val KNOWN_MERCHANT_CANONICAL = linkedMapOf(
    "starbucks" to "Starbucks",
    "sbux" to "Starbucks",
    "mcdonalds" to "McDonald's",
    "mcdo" to "McDonald's",
    "jollibee" to "Jollibee",
    "chowking" to "Chowking",
    "greenwich" to "Greenwich",
    "manginasal" to "Mang Inasal",
    "redribbon" to "Red Ribbon",
    "burgerking" to "Burger King",
    "kfc" to "KFC",
    "wendys" to "Wendy's",
    "pizzahut" to "Pizza Hut",
    "yellowcab" to "Yellow Cab Pizza",
    "dominos" to "Domino's",
    "shakeys" to "Shakey's",
    "timhortons" to "Tim Hortons",
    "coffeebean" to "Coffee Bean & Tea Leaf",
    "cbtl" to "Coffee Bean & Tea Leaf",
    "coffeebeanandtealeaf" to "Coffee Bean & Tea Leaf",
    "dunkin" to "Dunkin'",
    "krispykreme" to "Krispy Kreme",
    "potatocorner" to "Potato Corner",
    "seveneleven" to "7-Eleven",
    "7eleven" to "7-Eleven",
    "ministop" to "Uncle John's",
    "unclejohns" to "Uncle John's",
    "lawson" to "Lawson",
    "alfamart" to "Alfamart",
    "savemore" to "Savemore",
    "waltermart" to "WalterMart",
    "puregold" to "Puregold",
    "robinsonssupermarket" to "Robinsons Supermarket",
    "smhypermarket" to "SM Hypermarket",
    "themarketplace" to "The Marketplace",
    "landers" to "Landers",
    "snr" to "S&R",
    "sandr" to "S&R",
    "mercurydrug" to "Mercury Drug",
    "watsons" to "Watsons",
    "southstar" to "Southstar Drug",
    "tgp" to "The Generics Pharmacy",
    "thegenericspharmacy" to "The Generics Pharmacy",
    "shell" to "Shell",
    "petron" to "Petron",
    "caltex" to "Caltex",
    "seaoil" to "Seaoil",
    "unioil" to "Unioil",
    "phoenix" to "Phoenix",
    "grab" to "Grab",
    "angkas" to "Angkas",
    "joyride" to "JoyRide",
    "lalamove" to "Lalamove",
    "foodpanda" to "foodpanda",
    "uniqlo" to "UNIQLO",
    "miniso" to "MINISO",
    "daiso" to "Daiso",
    "smstore" to "SM Store",
    "acehardware" to "Ace Hardware",
    "nationalbookstore" to "National Book Store",
    "fullybooked" to "Fully Booked",
)
