package com.snapledger.feature.scan.parser

import com.snapledger.feature.scan.domain.NormalizedOcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicReceiptParserServiceTest {
    private val parser = DeterministicReceiptParserService()

    @Test
    fun `clean receipt parses merchant date total and items`() {
        val candidate = parser.parse(
            lines = fixtureLines(
                "BEAN BARN CAFE",
                "04/29/2026",
                "Latte 4.50",
                "Blueberry Muffin 3.25",
                "TOTAL 7.75",
            ),
        )

        assertEquals("BEAN BARN CAFE", candidate.merchant)
        assertEquals("2026-04-29", candidate.expenseDate)
        assertEquals(775L, candidate.totalAmount?.amountMinor)
        assertEquals(2, candidate.items.size)
        assertTrue(candidate.warnings.isEmpty())
    }

    @Test
    fun `noisy receipt still parses a candidate with warnings`() {
        val candidate = parser.parse(
            lines = fixtureLines(
                "STORE COPY",
                "SUNRISE MARKET",
                "123 MAIN ST",
                "Date: 04/05/2026",
                "BANANAS 2.49",
                "MEMBER SAVINGS 0.50",
                "TOTAL DUE 2.49",
                "VISA **** 1111",
            ),
        )

        assertEquals("SUNRISE MARKET", candidate.merchant)
        assertEquals("2026-04-05", candidate.expenseDate)
        assertEquals(249L, candidate.totalAmount?.amountMinor)
        assertEquals(1, candidate.items.size)
        assertTrue(candidate.warnings.isNotEmpty())
    }

    @Test
    fun `missing total returns partial candidate with warning`() {
        val candidate = parser.parse(
            lines = fixtureLines(
                "CORNER SHOP",
                "04/29/2026",
                "Bread 2.00",
                "Milk 1.50",
            ),
        )

        assertEquals("CORNER SHOP", candidate.merchant)
        assertEquals("2026-04-29", candidate.expenseDate)
        assertNull(candidate.totalAmount)
        assertTrue(candidate.warnings.any { it.contains("Total amount could not be determined") })
    }

    @Test
    fun `standalone trailing amount without total label is not inferred as total`() {
        val candidate = parser.parse(
            lines = fixtureLines(
                "CORNER SHOP",
                "04/29/2026",
                "Bread 2.00",
                "Milk 1.50",
                "$3.50",
            ),
        )

        assertNull(candidate.totalAmount)
        assertTrue(candidate.warnings.any { it.contains("Total amount could not be determined") })
    }

    @Test
    fun `ambiguous merchant adds warning`() {
        val candidate = parser.parse(
            lines = fixtureLines(
                "WEST COFFEE",
                "EAST COFFEE",
                "04/29/2026",
                "Americano 3.00",
                "TOTAL 3.00",
            ),
        )

        assertNotNull(candidate.merchant)
        assertTrue(candidate.warnings.any { it.contains("Merchant is ambiguous") })
    }

    @Test
    fun `multi line total is assembled deterministically`() {
        val candidate = parser.parse(
            lines = fixtureLines(
                "NORTH DELI",
                "April 29, 2026",
                "Sandwich 8.00",
                "TOTAL",
                "$8.00",
            ),
        )

        assertEquals("NORTH DELI", candidate.merchant)
        assertEquals("2026-04-29", candidate.expenseDate)
        assertEquals(800L, candidate.totalAmount?.amountMinor)
        assertTrue(candidate.warnings.any { it.contains("multi-line total label") })
    }

    @Test
    fun `faded total keyword with ocr confusion still parses total`() {
        val candidate = parser.parse(
            lines = fixtureLines(
                "THERMAL MART",
                "05/01/2026",
                "BREAD 50.00",
                "T0TAL 50.00",
            ),
        )

        assertEquals(5000L, candidate.totalAmount?.amountMinor)
    }

    @Test
    fun `faded amount due context can infer total`() {
        val candidate = parser.parse(
            lines = fixtureLines(
                "THERMAL MART",
                "05/01/2026",
                "MILK 22.50",
                "AM0UNT DUE 22.50",
            ),
        )

        assertEquals(2250L, candidate.totalAmount?.amountMinor)
        assertTrue(candidate.warnings.any { it.contains("faded total context") })
    }

    @Test
    fun `integer amount total is accepted and normalized`() {
        val candidate = parser.parse(
            lines = fixtureLines(
                "THERMAL MART",
                "05/01/2026",
                "TOTAL 66",
            ),
        )

        assertEquals(6600L, candidate.totalAmount?.amountMinor)
    }

    @Test
    fun `single decimal amount total is accepted and padded`() {
        val candidate = parser.parse(
            lines = fixtureLines(
                "THERMAL MART",
                "05/01/2026",
                "TOTAL 13.7",
            ),
        )

        assertEquals(1370L, candidate.totalAmount?.amountMinor)
    }

    @Test
    fun `bottom standalone total fallback handles faded receipt without total keyword`() {
        val candidate = parser.parse(
            lines = fixtureLines(
                "Starbucks Cotlee",
                "HI by: Rust Uof fee Cop.",
                "Latte 120.00",
                "120.00",
            ),
        )

        assertEquals(12000L, candidate.totalAmount?.amountMinor)
        assertEquals("Starbucks Cotlee", candidate.merchant)
        assertTrue(candidate.warnings.any { it.contains("bottom-right standalone amount") })
    }

    @Test
    fun `kiosk receipt keeps date ignores guests metadata and avoids cash as total`() {
        val candidate = parser.parse(
            lines = fixtureLines(
                "KO",
                "MR Ona Jkr1to",
                "Vytento g. 50-431",
                "Vilnius LT-03229",
                "11/20/2019 11:05 AM",
                "Table: 415 Guests: 2",
                "Server: Rebecca",
                "Americano 2.99",
                "Chocolate Cookie 1.98",
                "Water bottle 0.50",
                "Subtotal 5.47",
                "Tax 0.24",
                "Total 5.71",
                "Cash 6.00",
                "Change 0.29",
            ),
        )

        assertEquals("2019-11-20", candidate.expenseDate)
        assertEquals(571L, candidate.totalAmount?.amountMinor)
        assertTrue(candidate.items.none { it.description.contains("Guest", ignoreCase = true) })
    }

    @Test
    fun `starbucks receipt extracts date items and labeled total`() {
        val candidate = parser.parse(
            lines = fixtureLines(
                "Starbucks",
                "Caesars Palace Augustus Tower",
                "Store #16859",
                "3570 Las Vegas Blvd",
                "Las Vegas, NV 89109",
                "2235042 Diego N",
                "THK 5120",
                "3/31/2025 9:40 AM",
                "To Go",
                "1 VT LCD COFFEE 8.75",
                "1 BREAKFAST BURRITO 18.75",
                "Subtotal 27.50",
                "Tax 2.30",
                "Total 29.80",
                "Mastercard 29.40",
                "Check Closed",
                "3/31/2025 9:41 AM",
            ),
        )

        assertEquals("2025-03-31", candidate.expenseDate)
        assertEquals(2980L, candidate.totalAmount?.amountMinor)
        assertTrue(candidate.items.size >= 2)
    }

    @Test
    fun `ocr confused total label and glyphs still parse total`() {
        val candidate = parser.parse(
            lines = fixtureLines(
                "STARBUCKS",
                "3/31/2025 9:40 AM",
                "1 VT LCD COFFEE 8.7S",
                "1 BREAKFAST BURRITO 18.7S",
                "TotaI 29.8O",
            ),
        )

        assertEquals("2025-03-31", candidate.expenseDate)
        assertEquals(2980L, candidate.totalAmount?.amountMinor)
    }

    @Test
    fun `total can be inferred from subtotal plus tax when total line is unreadable`() {
        val candidate = parser.parse(
            lines = fixtureLines(
                "STARBUCKS",
                "3/31/2025 9:40 AM",
                "1 VT LCD COFFEE 8.75",
                "1 BREAKFAST BURRITO 18.75",
                "Subtotal 27.50",
                "Tax 2.30",
            ),
        )

        assertEquals(2980L, candidate.totalAmount?.amountMinor)
        assertTrue(candidate.warnings.any { it.contains("subtotal plus tax") })
    }
}

private fun fixtureLines(vararg values: String): List<NormalizedOcrLine> {
    return values.mapIndexed { index, value ->
        NormalizedOcrLine(
            index = index,
            text = value,
        )
    }
}
