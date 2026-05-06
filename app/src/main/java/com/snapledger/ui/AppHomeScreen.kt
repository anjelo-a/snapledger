package com.snapledger.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.snapledger.navigation.SnapLedgerDestination
import com.snapledger.navigation.SnapLedgerNavHost

@Composable
fun AppHomeScreen(navController: NavHostController) {
    val items = listOf(
        SnapLedgerDestination.Home,
        SnapLedgerDestination.Scan,
        SnapLedgerDestination.History,
        SnapLedgerDestination.Budgets
    )

    val activeGreen = Color(0xFF00A86B)
    val inactiveGray = Color(0xFF757575)
    //val lightGreenPill = activeGreen.copy(alpha = 0.15f)

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // hide fab
    val isFabVisible = currentRoute != SnapLedgerDestination.Scan.route &&
            currentRoute != SnapLedgerDestination.AddTransaction.route

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color.White,
                tonalElevation = 0.dp,
                modifier = Modifier.shadow(8.dp)
            ) {
                items.forEach { item ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                painter = painterResource(id = item.icon),
                                contentDescription = item.label,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = {
                            Text(
                                text = item.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        selected = currentRoute == item.route, // Uses the hoisted route
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = activeGreen,
                            selectedTextColor = activeGreen,
                            //indicatorColor = lightGreenPill,
                            unselectedIconColor = inactiveGray,
                            unselectedTextColor = inactiveGray
                        )
                    )
                }
            }
        },
        floatingActionButton = {
            if (isFabVisible) {
                FloatingActionButton(
                    onClick = {
                        navController.navigate(SnapLedgerDestination.AddTransaction.route)
                    },
                    shape = CircleShape,
                    containerColor = activeGreen,
                    contentColor = Color.White,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 6.dp,
                        pressedElevation = 12.dp
                    )
                ) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_input_add),
                        contentDescription = "Add Transaction",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        containerColor = Color(0xFFF8F9FA)
    ) { innerPadding ->
        SnapLedgerNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding)
        )
    }
}