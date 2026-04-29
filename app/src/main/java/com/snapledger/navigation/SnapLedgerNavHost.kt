package com.snapledger.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.snapledger.ui.AppHomeScreen
import com.snapledger.feature.review.ui.ReviewRoute
import com.snapledger.feature.review.vm.ReviewViewModel
import com.snapledger.feature.scan.ui.ScanRoute
import com.snapledger.feature.scan.vm.ScanViewModel

@Composable
fun SnapLedgerNavHost(
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = SnapLedgerDestination.Home.route,
        modifier = modifier,
    ) {
        composable(SnapLedgerDestination.Home.route) {
            AppHomeScreen(
                onOpenScan = { navController.navigate(SnapLedgerDestination.Scan.route) },
                onOpenReview = { navController.navigate(SnapLedgerDestination.Review.route) },
            )
        }
        composable(SnapLedgerDestination.Scan.route) {
            val scanViewModel: ScanViewModel = viewModel()
            ScanRoute(
                viewModel = scanViewModel,
                onBack = { navController.popBackStack() },
                onOpenReview = { navController.navigate(SnapLedgerDestination.Review.route) },
            )
        }
        composable(SnapLedgerDestination.Review.route) {
            val reviewViewModel: ReviewViewModel = viewModel()
            ReviewRoute(
                viewModel = reviewViewModel,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
