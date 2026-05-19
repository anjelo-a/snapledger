package com.snapledger.navigation

import com.snapledger.core.ledger.LedgerIncomePeriod
import com.snapledger.core.ledger.LedgerSnapshot
import com.snapledger.core.ledger.LedgerTransaction
import com.snapledger.core.ledger.LedgerTransactionSource
import com.snapledger.core.ledger.LedgerTransactionType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardIncomePeriodTest {
    private val today = LocalDate.of(2026, 5, 18)

    @Test
    fun `weekly-only income affects weekly dashboard`() {
        val state = LedgerSnapshot(
            transactions = listOf(
                income("weekly-income", amount = 500.0, incomePeriod = LedgerIncomePeriod.WEEKLY),
            ),
        ).toDashboardState(userName = "Ada", today = today)

        assertEquals(500.0, state.weeklyBudget.totalIncome, 0.001)
        assertEquals(500.0, state.weeklyBudget.remaining, 0.001)
        assertEquals(0.0, state.monthlyBudget.totalIncome, 0.001)
        assertEquals(0.0, state.monthlyBudget.remaining, 0.001)
    }

    @Test
    fun `monthly-only income affects monthly dashboard`() {
        val state = LedgerSnapshot(
            transactions = listOf(
                income("monthly-income", amount = 900.0, incomePeriod = LedgerIncomePeriod.MONTHLY),
            ),
        ).toDashboardState(userName = "Ada", today = today)

        assertEquals(0.0, state.weeklyBudget.totalIncome, 0.001)
        assertEquals(0.0, state.weeklyBudget.remaining, 0.001)
        assertEquals(900.0, state.monthlyBudget.totalIncome, 0.001)
        assertEquals(900.0, state.monthlyBudget.remaining, 0.001)
    }

    @Test
    fun `both income affects weekly and monthly dashboard`() {
        val state = LedgerSnapshot(
            transactions = listOf(
                income("both-income", amount = 700.0, incomePeriod = LedgerIncomePeriod.BOTH),
            ),
        ).toDashboardState(userName = "Ada", today = today)

        assertEquals(700.0, state.weeklyBudget.totalIncome, 0.001)
        assertEquals(700.0, state.weeklyBudget.remaining, 0.001)
        assertEquals(700.0, state.monthlyBudget.totalIncome, 0.001)
        assertEquals(700.0, state.monthlyBudget.remaining, 0.001)
    }

    @Test
    fun `dashboard period summaries expose correct amounts when toggled`() {
        val state = LedgerSnapshot(
            transactions = listOf(
                income("weekly-income", amount = 75.0, incomePeriod = LedgerIncomePeriod.WEEKLY),
                income("monthly-income", amount = 125.0, incomePeriod = LedgerIncomePeriod.MONTHLY),
                income("both-income", amount = 50.0, incomePeriod = LedgerIncomePeriod.BOTH),
                expense("lunch", amount = 20.0),
            ),
        ).toDashboardState(userName = "Ada", today = today)

        assertEquals(125.0, state.weeklyBudget.totalIncome, 0.001)
        assertEquals(20.0, state.weeklyBudget.totalExpenses, 0.001)
        assertEquals(105.0, state.weeklyBudget.remaining, 0.001)
        assertEquals(175.0, state.monthlyBudget.totalIncome, 0.001)
        assertEquals(20.0, state.monthlyBudget.totalExpenses, 0.001)
        assertEquals(155.0, state.monthlyBudget.remaining, 0.001)
    }

    @Test
    fun `all-time dashboard includes all income and expenses`() {
        val state = LedgerSnapshot(
            transactions = listOf(
                income(
                    id = "old-weekly-income",
                    amount = 100.0,
                    incomePeriod = LedgerIncomePeriod.WEEKLY,
                    date = "01/01/2026",
                ),
                income(
                    id = "old-monthly-income",
                    amount = 200.0,
                    incomePeriod = LedgerIncomePeriod.MONTHLY,
                    date = "02/01/2026",
                ),
                income(
                    id = "both-income",
                    amount = 300.0,
                    incomePeriod = LedgerIncomePeriod.BOTH,
                ),
                expense("old-rent", amount = 50.0, date = "03/01/2026"),
                expense("current-lunch", amount = 25.0),
            ),
        ).toDashboardState(userName = "Ada", today = today)

        assertEquals(600.0, state.allTimeBudget.totalIncome, 0.001)
        assertEquals(75.0, state.allTimeBudget.totalExpenses, 0.001)
        assertEquals(525.0, state.allTimeBudget.remaining, 0.001)
    }

    @Test
    fun `all-time spending trend groups full history into readable month buckets`() {
        val state = LedgerSnapshot(
            transactions = listOf(
                expense("jan", amount = 10.0, date = "01/10/2026"),
                expense("feb", amount = 20.0, date = "02/10/2026"),
                expense("mar", amount = 30.0, date = "03/10/2026"),
                expense("apr", amount = 40.0, date = "04/10/2026"),
                expense("may", amount = 50.0, date = "05/10/2026"),
            ),
        ).toDashboardState(userName = "Ada", today = today)

        assertEquals(listOf(10.0, 30.0, 60.0, 100.0, 150.0), state.allTimeTrend.dataPoints)
        assertEquals(5, state.allTimeTrend.dataLabels.size)
        assertEquals("All time by month", state.allTimeTrend.period)
    }

    private fun income(
        id: String,
        amount: Double,
        incomePeriod: LedgerIncomePeriod,
        date: String = "05/18/2026",
    ): LedgerTransaction {
        return LedgerTransaction(
            id = id,
            type = LedgerTransactionType.INCOME,
            source = LedgerTransactionSource.MANUAL,
            amount = amount,
            merchant = "Salary",
            date = date,
            note = null,
            category = "Salary",
            createdAtMillis = 1L,
            incomePeriod = incomePeriod,
        )
    }

    private fun expense(
        id: String,
        amount: Double,
        date: String = "05/18/2026",
    ): LedgerTransaction {
        return LedgerTransaction(
            id = id,
            type = LedgerTransactionType.EXPENSE,
            source = LedgerTransactionSource.MANUAL,
            amount = amount,
            merchant = "Lunch",
            date = date,
            note = null,
            category = "Food",
            createdAtMillis = 2L,
        )
    }
}
