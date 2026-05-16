package com.snapledger.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.snapledger.core.profile.UserProfile
import com.snapledger.feature.budget.ui.BudgetRoute
import com.snapledger.feature.dashboard.ui.DashboardScreen
import com.snapledger.feature.dashboard.ui.DashboardUiState
import com.snapledger.feature.entry.ui.AddTransactionRoute
import com.snapledger.feature.history.ui.HistoryRoute
import com.snapledger.feature.review.domain.LocalFirstReviewRepository
import com.snapledger.feature.review.ui.ReviewRoute
import com.snapledger.feature.review.vm.ReviewViewModel
import com.snapledger.feature.scan.ui.ScanRoute
import com.snapledger.feature.scan.vm.ScanViewModel
import com.snapledger.feature.settings.ui.SettingsRoute
import com.snapledger.feature.settings.vm.SettingsViewModel
import com.snapledger.feature.settings.vm.SettingsUiState

@Composable
fun SnapLedgerNavHost(
    navController: NavHostController,
    profile: UserProfile,
    onDisplayNameChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = SnapLedgerDestination.Home.route,
        modifier = modifier
    ) {
        composable(SnapLedgerDestination.Home.route) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DashboardScreen(
                    state = DashboardUiState(userName = profile.displayName),
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
                onNavigateToDetail = { transactionId ->

                }
            )
        }
        composable(SnapLedgerDestination.Budgets.route) {
            BudgetRoute()
        }
        composable(SnapLedgerDestination.AddTransaction.route) {
            AddTransactionRoute(
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