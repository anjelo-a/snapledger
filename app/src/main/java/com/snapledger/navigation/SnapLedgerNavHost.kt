package com.snapledger.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.snapledger.feature.dashboard.network.DashboardInsightResult
import com.snapledger.feature.dashboard.network.DashboardInsightService
import com.snapledger.feature.dashboard.ui.DashboardScreen
import com.snapledger.feature.dashboard.ui.DashboardUiState
import com.snapledger.feature.dashboard.ui.CategorySummary
import com.snapledger.feature.dashboard.ui.TrendSummary
import com.snapledger.feature.dashboard.ui.TransactionSummary
import com.snapledger.feature.entry.ui.AddTransactionRoute
import com.snapledger.feature.entry.ui.TransactionType
import com.snapledger.feature.history.ui.HistoryTransactionUiModel
import com.snapledger.feature.history.ui.HistoryRoute
import com.snapledger.feature.insights.ui.AiInsightsRoute
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Helper to determine order of bottom nav items for lateral sliding
private fun getBottomNavIndex(route: String?): Int {
    return when (route) {
        SnapLedgerDestination.Home.route -> 0
        SnapLedgerDestination.Budgets.route -> 1
        SnapLedgerDestination.History.route -> 2
        "settings" -> 3
        else -> -1
    }
}

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
    val insightService = remember { DashboardInsightService() }
    var insightText by remember { mutableStateOf<String?>(null) }
    var insightActionTip by remember { mutableStateOf<String?>(null) }
    var isInsightLoading by remember { mutableStateOf(false) }
    val insightRefreshTotal = remember(ledgerSnapshot.transactions) {
        ledgerSnapshot.transactions.sumOf { it.amount }
    }
    val dashboardBaseState = remember(ledgerSnapshot, profile.displayName) {
        ledgerSnapshot.toDashboardState(profile.displayName)
    }
    val historyTransactions = remember(ledgerSnapshot.transactions) {
        ledgerSnapshot.transactions.map { it.toHistoryTransaction() }
    }

    LaunchedEffect(ledgerSnapshot.transactions.size, insightRefreshTotal) {
        isInsightLoading = true
        when (val result = withContext(Dispatchers.IO) { insightService.generate(period = "monthly") }) {
            is DashboardInsightResult.Success -> {
                insightText = result.text
                insightActionTip = result.actionTip
            }

            is DashboardInsightResult.Failure -> {
                val fallback = ledgerSnapshot.buildLocalInsightFallback()
                insightText = fallback.first
                insightActionTip = fallback.second
            }
        }
        isInsightLoading = false
    }

    NavHost(
        navController = navController,
        startDestination = SnapLedgerDestination.Home.route,
        modifier = modifier,
        enterTransition = {
            val fromIndex = getBottomNavIndex(initialState.destination.route)
            val toIndex = getBottomNavIndex(targetState.destination.route)

            if (fromIndex != -1 && toIndex != -1) {
                val direction = if (fromIndex < toIndex) {
                    AnimatedContentTransitionScope.SlideDirection.Left
                } else {
                    AnimatedContentTransitionScope.SlideDirection.Right
                }
                slideIntoContainer(direction, tween(250))
            } else {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Up, tween(250))
            }
        },
        exitTransition = {
            val fromIndex = getBottomNavIndex(initialState.destination.route)
            val toIndex = getBottomNavIndex(targetState.destination.route)

            if (fromIndex != -1 && toIndex != -1) {
                val direction = if (fromIndex < toIndex) {
                    AnimatedContentTransitionScope.SlideDirection.Left
                } else {
                    AnimatedContentTransitionScope.SlideDirection.Right
                }
                slideOutOfContainer(direction, tween(250))
            } else {
                fadeOut(tween(250))
            }
        },
        popEnterTransition = {
            val fromIndex = getBottomNavIndex(initialState.destination.route)
            val toIndex = getBottomNavIndex(targetState.destination.route)

            if (fromIndex != -1 && toIndex != -1) {
                val direction = if (fromIndex < toIndex) {
                    AnimatedContentTransitionScope.SlideDirection.Left
                } else {
                    AnimatedContentTransitionScope.SlideDirection.Right
                }
                slideIntoContainer(direction, tween(250))
            } else {
                fadeIn(tween(250))
            }
        },
        popExitTransition = {
            val fromIndex = getBottomNavIndex(initialState.destination.route)
            val toIndex = getBottomNavIndex(targetState.destination.route)

            if (fromIndex != -1 && toIndex != -1) {
                val direction = if (fromIndex < toIndex) {
                    AnimatedContentTransitionScope.SlideDirection.Left
                } else {
                    AnimatedContentTransitionScope.SlideDirection.Right
                }
                slideOutOfContainer(direction, tween(250))
            } else {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Down, tween(250))
            }
        }
    ) {
        composable(SnapLedgerDestination.Home.route) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DashboardScreen(
                    state = dashboardBaseState.copy(
                        insight = insightText,
                        insightActionTip = insightActionTip,
                        isInsightLoading = isInsightLoading,
                    ),
                    onDisplayNameChange = onDisplayNameChange,
                    onManageBudgetClick = {
                        navController.navigate(SnapLedgerDestination.Budgets.route)
                    },
                    onSeeAllActivityClick = {
                        navController.navigate(SnapLedgerDestination.History.route)
                    },
                    onViewAiInsightsClick = {
                        navController.navigate(SnapLedgerDestination.AiInsights.route)
                    }
                )
            }
        }
        composable(
            route = SnapLedgerDestination.AiInsights.route,
            enterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(250))
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(250))
            }
        ) {
            AiInsightsRoute(
                currentInsight = insightText,
                currentActionTip = insightActionTip,
                isInsightLoading = isInsightLoading,
                insightClient = insightService,
                onBack = {
                    navController.popBackStack()
                },
            )
        }
        composable(SnapLedgerDestination.Scan.route) {
            val scanContext = LocalContext.current
            val scanViewModel: ScanViewModel = viewModel(
                factory = ScanViewModel.factory(scanContext)
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
        composable(
            route = "review",
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(250)) },
            popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(250)) }
        ) {
            val reviewContext = LocalContext.current

            val repository = LocalFirstReviewRepository.getInstance(reviewContext)

            val reviewViewModel: ReviewViewModel = viewModel(
                factory = ReviewViewModel.factory(repository)
            )

            ReviewRoute(
                viewModel = reviewViewModel,
                onBack = {
                    navController.popBackStack()
                },
                onSaveCompleted = {
                    navController.navigate(SnapLedgerDestination.Home.route) {
                        popUpTo(SnapLedgerDestination.Home.route)
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(SnapLedgerDestination.History.route) {
            HistoryRoute(
                transactions = historyTransactions,
                ledgerRepository = ledgerRepository,
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
    val today = LocalDate.now()
    val weekFields = WeekFields.of(Locale.getDefault())
    val currentWeek = today.get(weekFields.weekOfWeekBasedYear())
    val currentWeekYear = today.get(weekFields.weekBasedYear())

    val monthlyTransactions = mutableListOf<LedgerTransaction>()
    val weeklyTransactions = mutableListOf<LedgerTransaction>()
    val expenseTransactions = mutableListOf<LedgerTransaction>()

    transactions.forEach { transaction ->
        val parsedDate = transaction.parsedDate()
        val isCurrentMonth = parsedDate?.let { date ->
            date.year == today.year && date.month == today.month
        } ?: true
        val isCurrentWeek = parsedDate?.let { date ->
            date.get(weekFields.weekBasedYear()) == currentWeekYear &&
                    date.get(weekFields.weekOfWeekBasedYear()) == currentWeek
        } ?: true

        if (isCurrentMonth) monthlyTransactions += transaction
        if (isCurrentWeek) weeklyTransactions += transaction
        if (transaction.type == LedgerTransactionType.EXPENSE) expenseTransactions += transaction
    }

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
        trend = buildTrendSummary(),
        categories = buildCategorySummaries(expenseTransactions),
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

// FIX: Generate cumulative data points so the chart renders properly
private fun LedgerSnapshot.buildTrendSummary(today: LocalDate = LocalDate.now()): TrendSummary {
    val previousMonth = today.minusMonths(1)
    var thisMonth = 0.0
    var prevMonthTotal = 0.0
    var week1 = 0.0
    var week2 = 0.0
    var week3 = 0.0
    var week4 = 0.0

    transactions.forEach { transaction ->
        if (transaction.type != LedgerTransactionType.EXPENSE) return@forEach
        val date = transaction.parsedDate() ?: return@forEach

        if (date.year == today.year && date.month == today.month) {
            thisMonth += transaction.amount
            when (date.dayOfMonth) {
                in 1..7 -> week1 += transaction.amount
                in 8..14 -> week2 += transaction.amount
                in 15..21 -> week3 += transaction.amount
                else -> week4 += transaction.amount
            }
        } else if (date.year == previousMonth.year && date.month == previousMonth.month) {
            prevMonthTotal += transaction.amount
        }
    }

    // Cumulative sum gives the line an upward trend throughout the month
    val cumulativePoints = listOf(
        week1,
        week1 + week2,
        week1 + week2 + week3,
        week1 + week2 + week3 + week4
    )

    if (thisMonth == 0.0 && prevMonthTotal == 0.0) {
        return TrendSummary(
            percentageChange = 0.0,
            isUp = false,
            period = "This month vs last month",
            dataPoints = cumulativePoints
        )
    }

    if (prevMonthTotal == 0.0) {
        return TrendSummary(
            percentageChange = 100.0,
            isUp = true,
            period = "This month vs last month",
            dataPoints = cumulativePoints
        )
    }

    val delta = ((thisMonth - prevMonthTotal) / prevMonthTotal) * 100.0
    return TrendSummary(
        percentageChange = kotlin.math.abs(delta),
        isUp = delta >= 0.0,
        period = "This month vs last month",
        dataPoints = cumulativePoints
    )
}

private fun buildCategorySummaries(expenses: List<LedgerTransaction>): List<CategorySummary> {
    if (expenses.isEmpty()) return emptyList()

    var total = 0.0
    val totalsByCategory = linkedMapOf<String, Double>()
    expenses.forEach { transaction ->
        val amount = transaction.amount
        val category = transaction.category.ifBlank { "Uncategorized" }
        total += amount
        totalsByCategory[category] = (totalsByCategory[category] ?: 0.0) + amount
    }
    if (total <= 0.0) return emptyList()

    return totalsByCategory
        .map { (name, amount) ->
            val percentage = ((amount / total) * 100.0).toFloat()
            CategorySummary(
                name = name,
                amount = amount,
                percentage = percentage,
            )
        }
        .sortedByDescending { it.amount }
}

private fun LedgerSnapshot.buildBudgetSummary(
    period: LedgerBudgetPeriod,
    periodTransactions: List<LedgerTransaction>,
): BudgetSummary {
    var spent = 0.0
    var totalIncome = 0.0
    periodTransactions.forEach { transaction ->
        when (transaction.type) {
            LedgerTransactionType.EXPENSE -> spent += transaction.amount
            LedgerTransactionType.INCOME -> totalIncome += transaction.amount
        }
    }

    return BudgetSummary(
        limit = budgetCategories.filter { it.period == period }.sumOf { it.allocated },
        spent = spent,
        totalIncome = totalIncome,
        totalExpenses = spent,
    )
}

private fun LedgerSnapshot.buildLocalInsightFallback(): Pair<String?, String?> {
    val totalsByCategory = linkedMapOf<String, Double>()
    var total = 0.0
    transactions.forEach { transaction ->
        if (transaction.type != LedgerTransactionType.EXPENSE) return@forEach
        val category = transaction.category.ifBlank { "Uncategorized" }
        totalsByCategory[category] = (totalsByCategory[category] ?: 0.0) + transaction.amount
        total += transaction.amount
    }

    if (total <= 0.0) {
        return Pair(
            "No strong spending pattern yet. Track a few receipts to unlock your first insight.",
            "Scan or add your next receipt to build a clearer trend.",
        )
    }

    val topCategory = totalsByCategory.maxByOrNull { it.value }
    val categoryName = topCategory?.key ?: "Uncategorized"
    val categoryTotal = topCategory?.value ?: 0.0
    return Pair(
        "$categoryName leads your tracked spending at ${formatCurrency(categoryTotal)} of ${formatCurrency(total)}.",
        "Review recent $categoryName purchases before your next save.",
    )
}

private fun formatCurrency(amount: Double): String {
    return "PHP " + String.format(Locale.getDefault(), "%,.2f", amount)
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
private val isoTransactionDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

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
        try {
            LocalDate.parse(date, isoTransactionDateFormatter)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
