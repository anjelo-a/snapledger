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
        assertTrue(candidate.warnings.any { it.contains("bottom standalone amount fallback") })
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
