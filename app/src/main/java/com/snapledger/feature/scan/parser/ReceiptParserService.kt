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

        val merchantSelection = selectMerchant(lines)
        val expenseDateSelection = selectExpenseDate(lines)
        val totalSelection = selectTotalAmount(lines)
        val items = selectItems(
            lines = lines,
            merchantLineIndex = merchantSelection.lineIndex,
            totalLineIndex = totalSelection.lineIndex,
        )

        warnings += merchantSelection.warnings
        warnings += expenseDateSelection.warnings
        warnings += totalSelection.warnings
        if (items.isEmpty()) {
            warnings += "No line items were confidently parsed from the OCR lines."
        }
        if (merchantSelection.value == null) {
            warnings += "Merchant could not be determined from the OCR lines."
        }
        if (expenseDateSelection.value == null) {
            warnings += "Expense date could not be determined from the OCR lines."
        }
        if (totalSelection.value == null) {
            warnings += "Total amount could not be determined from the OCR lines."
        }

        return ParsedReceiptCandidate(
            merchant = merchantSelection.value,
            expenseDate = expenseDateSelection.value,
            totalAmount = totalSelection.value,
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
)

private data class ScoredMerchantLine(
    val lineIndex: Int,
    val text: String,
    val score: Int,
)

private fun selectMerchant(lines: List<NormalizedOcrLine>): MerchantSelection {
    val candidates = lines
        .take(6)
        .map { line ->
            ScoredMerchantLine(
                lineIndex = line.index,
                text = line.text,
                score = scoreMerchantLine(line),
            )
        }
        .sortedByDescending { it.score }

    val best = candidates.firstOrNull() ?: return MerchantSelection(null, null, emptyList())
    if (best.score <= 0) {
        return MerchantSelection(null, null, emptyList())
    }

    val warnings = mutableListOf<String>()
    val secondBest = candidates.getOrNull(1)
    if (secondBest != null && abs(best.score - secondBest.score) <= 4) {
        warnings += "Merchant is ambiguous; selected '${best.text}' over '${secondBest.text}'."
    }

    return MerchantSelection(
        value = best.text,
        lineIndex = best.lineIndex,
        warnings = warnings,
    )
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
    if (letters >= 4) score += 12
    if (digits == 0) score += 10
    if (uppercaseLetters > 0 && uppercaseLetters >= letters * 0.6) score += 8

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

    lines.forEach { line ->
        val matchedDate = matchesKnownDate(line.text) ?: return@forEach
        if (matchedDate.isAmbiguous) {
            warnings += "Expense date '${matchedDate.raw}' is ambiguous; interpreted as ${matchedDate.normalized}."
        }
        return DateSelection(
            value = matchedDate.normalized,
            warnings = warnings,
        )
    }

    return DateSelection(
        value = null,
        warnings = emptyList(),
    )
}

private fun selectTotalAmount(lines: List<NormalizedOcrLine>): TotalSelection {
    val warnings = mutableListOf<String>()
    var bestCandidate: Pair<Int, ParsedMoneyCandidate>? = null
    var bestScore = Int.MIN_VALUE

    lines.forEachIndexed { index, line ->
        val lowercase = line.text.lowercase(Locale.US)
        val containsTotalKeyword = TOTAL_KEYWORDS.any { lowercase.contains(it) }
        val excluded = NON_TOTAL_KEYWORDS.any { lowercase.contains(it) }
        val inlineAmount = parseLastMoneyCandidate(line.text)
        val nextAmount = if (containsTotalKeyword && inlineAmount == null) {
            lines.getOrNull(index + 1)?.let { parseLastMoneyCandidate(it.text) }
        } else {
            null
        }

        val score = when {
            lowercase.contains("grand total") -> 120
            lowercase.contains("amount due") -> 118
            lowercase.contains("balance due") -> 116
            containsTotalKeyword && inlineAmount != null -> 112
            containsTotalKeyword && nextAmount != null -> 108
            else -> Int.MIN_VALUE
        }

        if (!excluded && score > bestScore) {
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

    if (bestCandidate != null) {
        return TotalSelection(
            value = bestCandidate?.second,
            lineIndex = bestCandidate?.first,
            warnings = warnings,
        )
    }

    val fallback = lines
        .takeLast(6)
        .mapNotNull { line -> parseLastMoneyCandidate(line.text)?.let { line.index to it } }
        .maxByOrNull { (_, amount) -> amount.amountMinor }

    return if (fallback != null) {
        TotalSelection(
            value = fallback.second,
            lineIndex = fallback.first,
            warnings = listOf("Total amount was inferred from the largest trailing amount without an explicit total label."),
        )
    } else {
        TotalSelection(
            value = null,
            lineIndex = null,
            warnings = emptyList(),
        )
    }
}

private fun selectItems(
    lines: List<NormalizedOcrLine>,
    merchantLineIndex: Int?,
    totalLineIndex: Int?,
): List<ParsedReceiptItemCandidate> {
    val startIndex = lines.indexOfFirst { it.index == merchantLineIndex }
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

            val money = parseLastMoneyCandidate(line.text) ?: return@mapNotNull null
            val amountMatch = AMOUNT_REGEX.findAll(line.text).lastOrNull() ?: return@mapNotNull null
            val description = line.text.substring(0, amountMatch.range.first)
                .replace(Regex("[\\s.:-]+$"), "")
                .trim()
            if (description.length < 2 || description.count { it.isLetter() } < 2) return@mapNotNull null

            ParsedReceiptItemCandidate(
                description = description,
                amount = money,
            )
        }
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
                // Try the next formatter.
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

    val amountMinor = normalized.toBigDecimalOrNull()
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
)

private val AMOUNT_REGEX = Regex("""(?:[$₱]|PHP\s*)?\d+(?:,\d{3})*(?:\.\d{2})""")
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
