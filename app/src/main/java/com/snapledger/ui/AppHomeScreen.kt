package com.snapledger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.snapledger.core.profile.UserProfile
import com.snapledger.navigation.SnapLedgerDestination
import com.snapledger.navigation.SnapLedgerNavHost

@Composable
fun AppHomeScreen(
    navController: NavHostController,
    profile: UserProfile,
    onDisplayNameChange: (String) -> Unit,
) {
    val items = remember {
        listOf(
            SnapLedgerDestination.Home,
            SnapLedgerDestination.Scan,
            SnapLedgerDestination.History,
            SnapLedgerDestination.Budgets,
        )
    }

    val activeGreen = remember { Color(0xFF00A86B) }
    val inactiveGray = remember { Color(0xFF757575) }
    val bgColor = remember { Color(0xFFF8F9FA) }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val isFabVisible = currentRoute != SnapLedgerDestination.Scan.route &&
            currentRoute != SnapLedgerDestination.AddTransaction.route

    Scaffold(
        bottomBar = {
            if (isFabVisible) {
                BottomAppBar(
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                    modifier = Modifier.shadow(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            NavBarItem(
                                icon = items[0].icon,
                                label = items[0].label,
                                isSelected = currentRoute == items[0].route,
                                activeColor = activeGreen,
                                inactiveColor = inactiveGray,
                                onClick = {
                                    navController.navigate(items[0].route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                            NavBarItem(
                                icon = items[1].icon,
                                label = items[1].label,
                                isSelected = currentRoute == items[1].route,
                                activeColor = activeGreen,
                                inactiveColor = inactiveGray,
                                onClick = {
                                    navController.navigate(items[1].route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }

                        Spacer(modifier = Modifier.width(72.dp))

                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            NavBarItem(
                                icon = items[2].icon,
                                label = items[2].label,
                                isSelected = currentRoute == items[2].route,
                                activeColor = activeGreen,
                                inactiveColor = inactiveGray,
                                onClick = {
                                    navController.navigate(items[2].route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                            NavBarItem(
                                icon = items[3].icon,
                                label = items[3].label,
                                isSelected = currentRoute == items[3].route,
                                activeColor = activeGreen,
                                inactiveColor = inactiveGray,
                                onClick = {
                                    navController.navigate(items[3].route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
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
                    ),
                    modifier = Modifier
                        .size(80.dp)
                        .offset(y = 80.dp)
                ) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_input_add),
                        contentDescription = "Add Transaction",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        containerColor = bgColor
    ) { innerPadding ->
        SnapLedgerNavHost(
            navController = navController,
            profile = profile,
            onDisplayNameChange = onDisplayNameChange,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
private fun NavBarItem(
    icon: Int,
    label: String,
    isSelected: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    onClick: () -> Unit
) {
    val color = if (isSelected) activeColor else inactiveColor

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}