package com.snapledger.navigation

import com.snapledger.R

sealed class SnapLedgerDestination(val route: String, val icon: Int, val label: String) {
    object Home : SnapLedgerDestination("home", R.drawable.home, "Home")
    object Scan : SnapLedgerDestination("scan", R.drawable.scan, "Scan")
    object History : SnapLedgerDestination("history", R.drawable.list, "History")
    object Budgets : SnapLedgerDestination("budgets", R.drawable.pie, "Budget")
    object AddTransaction : SnapLedgerDestination("add_transaction", android.R.drawable.ic_input_add, "Add")
}
