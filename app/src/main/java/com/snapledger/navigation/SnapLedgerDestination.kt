package com.snapledger.navigation

sealed interface SnapLedgerDestination {
    val route: String

    data object Home : SnapLedgerDestination {
        override val route: String = "home"
    }

    data object Scan : SnapLedgerDestination {
        override val route: String = "scan"
    }

    data object Review : SnapLedgerDestination {
        override val route: String = "review"
    }
}
