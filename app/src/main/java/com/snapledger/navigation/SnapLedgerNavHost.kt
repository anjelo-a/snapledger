package com.snapledger.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.snapledger.core.ledger.DataStoreLedgerRepository
import com.snapledger.core.ledger.LedgerBudgetPeriod
import com.snapledger.core.ledger.LedgerSnapshot
import com.snapledger.core.ledger.LedgerTransaction
import com.snapledger.core.ledger.LedgerTransactionType
import com.snapledger.core.profile.UserProfile
import com.snapledger.feature.budget.ui.BudgetRoute
import com.snapledger.feature.dashboard.ui.BudgetSummary
import com.snapledger.feature.dashboard.ui.DashboardScreen
import com.snapledger.feature.dashboard.ui.DashboardUiState
import com.snapledger.feature.dashboard.ui.TransactionSummary
import com.snapledger.feature.entry.ui.AddTransactionRoute
import com.snapledger.feature.entry.ui.TransactionType
import com.snapledger.feature.history.ui.HistoryTransactionUiModel
import com.snapledger.feature.history.ui.HistoryRoute
import com.snapledger.feature.review.domain.LocalFirstReviewRepository
import com.snapledger.feature.review.ui.ReviewRoute
import com.snapledger.feature.review.vm.ReviewViewModel
import com.snapledger.feature.scan.ui.ScanRoute
import com.snapledger.feature.scan.vm.ScanViewModel
import com.snapledger.feature.settings.ui.SettingsRoute
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.WeekFields
import java.util.Locale

@Composable
fun SnapLedgerNavHost(
    navController: NavHostController,
    profile: UserProfile,
    onDisplayNameChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ledgerRepository = remember(context) {
        DataStoreLedgerRepository.getInstance(context)
    }
    val ledgerSnapshot by ledgerRepository.snapshotFlow.collectAsState(initial = LedgerSnapshot())

    NavHost(
        navController = navController,
        startDestination = SnapLedgerDestination.Home.route,
        modifier = modifier
    ) {
        composable(SnapLedgerDestination.Home.route) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DashboardScreen(
                    state = ledgerSnapshot.toDashboardState(profile.displayName),
                    onDisplayNameChange = onDisplayNameChange,
                    onManageBudgetClick = {
                        navController.navigate(SnapLedgerDestination.Budgets.route)
                    }
                )
            }
        }
        composable(SnapLedgerDestination.Scan.route) {
            val context = LocalContext.current
            val scanViewModel: ScanViewModel = viewModel(
                factory = ScanViewModel.factory(context)
            )

            ScanRoute(
                viewModel = scanViewModel,
                onBack = {
                    navController.popBackStack()
                },
                onOpenReview = {
                    navController.navigate("review")
                }
            )
        }
        composable("review") {
            val context = LocalContext.current

            val repository = LocalFirstReviewRepository.getInstance(context)

            val reviewViewModel: ReviewViewModel = viewModel(
                factory = ReviewViewModel.factory(repository)
            )

            ReviewRoute(
                viewModel = reviewViewModel,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
        composable(SnapLedgerDestination.History.route) {
            HistoryRoute(
                transactions = ledgerSnapshot.transactions.map { it.toHistoryTransaction() },
                onNavigateToDetail = { transactionId ->

                }
            )
        }
        composable(SnapLedgerDestination.Budgets.route) {
            BudgetRoute(ledgerRepository = ledgerRepository)
        }
        composable(SnapLedgerDestination.AddExpense.route) {
            AddTransactionRoute(
                ledgerRepository = ledgerRepository,
                transactionType = TransactionType.EXPENSE,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
        composable(SnapLedgerDestination.AddIncome.route) {
            AddTransactionRoute(
                ledgerRepository = ledgerRepository,
                transactionType = TransactionType.INCOME,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
        composable("settings") {
            SettingsRoute(
                initialUserName = profile.displayName,
                onNameChanged = onDisplayNameChange
            )
        }
    }
}

private fun LedgerSnapshot.toDashboardState(userName: String): DashboardUiState {
    val monthlyTransactions = transactions.filter { it.isInCurrentMonth() }
    val weeklyTransactions = transactions.filter { it.isInCurrentWeek() }

    return DashboardUiState(
        userName = userName,
        monthlyBudget = buildBudgetSummary(
            period = LedgerBudgetPeriod.MONTHLY,
            periodTransactions = monthlyTransactions,
        ),
        weeklyBudget = buildBudgetSummary(
            period = LedgerBudgetPeriod.WEEKLY,
            periodTransactions = weeklyTransactions,
        ),
        recentActivity = transactions
            .sortedByDescending { it.createdAtMillis }
            .take(5)
            .map { transaction ->
                TransactionSummary(
                    id = transaction.id,
                    title = transaction.merchant,
                    category = transaction.category,
                    date = transaction.date,
                    amount = transaction.amount,
                    isIncome = transaction.type == LedgerTransactionType.INCOME,
                )
            },
    )
}

private fun LedgerSnapshot.buildBudgetSummary(
    period: LedgerBudgetPeriod,
    periodTransactions: List<LedgerTransaction>,
): BudgetSummary {
    val expenses = periodTransactions.filter { it.type == LedgerTransactionType.EXPENSE }
    val income = periodTransactions.filter { it.type == LedgerTransactionType.INCOME }
    return BudgetSummary(
        limit = budgetCategories.filter { it.period == period }.sumOf { it.allocated },
        spent = expenses.sumOf { it.amount },
        totalIncome = income.sumOf { it.amount },
        totalExpenses = expenses.sumOf { it.amount },
    )
}

private fun LedgerTransaction.toHistoryTransaction(): HistoryTransactionUiModel {
    return HistoryTransactionUiModel(
        id = id,
        title = merchant,
        category = category,
        note = note,
        amount = amount,
        isIncome = type == LedgerTransactionType.INCOME,
        dateString = date,
    )
}

private val transactionDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")

private fun LedgerTransaction.isInCurrentMonth(today: LocalDate = LocalDate.now()): Boolean {
    val date = parsedDate() ?: return true
    return date.year == today.year && date.month == today.month
}

private fun LedgerTransaction.isInCurrentWeek(today: LocalDate = LocalDate.now()): Boolean {
    val date = parsedDate() ?: return true
    val weekFields = WeekFields.of(Locale.getDefault())
    return date.get(weekFields.weekBasedYear()) == today.get(weekFields.weekBasedYear()) &&
        date.get(weekFields.weekOfWeekBasedYear()) == today.get(weekFields.weekOfWeekBasedYear())
}

private fun LedgerTransaction.parsedDate(): LocalDate? {
    return try {
        LocalDate.parse(date, transactionDateFormatter)
    } catch (_: DateTimeParseException) {
        null
    }
}
