package com.snapledger.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.snapledger.feature.dashboard.ui.DashboardScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.snapledger.feature.scan.ui.ScanRoute
import com.snapledger.feature.scan.vm.ScanViewModel
import androidx.compose.ui.platform.LocalContext

@Composable
fun SnapLedgerNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = SnapLedgerDestination.Home.route,
        modifier = modifier
    ) {
        composable(SnapLedgerDestination.Home.route) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DashboardScreen()
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
                    // navController.navigate("review_route_placeholder")
                }
            )
        }
        composable(SnapLedgerDestination.History.route) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("History Screen")
            }
        }
        composable(SnapLedgerDestination.Budgets.route) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Budget Screen")
            }
        }
        composable(SnapLedgerDestination.AddTransaction.route) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Add Transaction Screen")
            }
        }
    }
}