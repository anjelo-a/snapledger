package com.snapledger.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.snapledger.R
import com.snapledger.core.profile.UserProfile
import com.snapledger.navigation.SnapLedgerDestination
import com.snapledger.navigation.SnapLedgerNavHost

data class BottomNavItem(
    val route: String,
    val iconResId: Int?,
    val iconVector: ImageVector?,
    val label: String
)

@Composable
fun AppHomeScreen(
    navController: NavHostController,
    profile: UserProfile,
    onDisplayNameChange: (String) -> Unit,
) {
    val navItems = remember {
        listOf(
            BottomNavItem(SnapLedgerDestination.Home.route, R.drawable.home, null, "Home"),
            BottomNavItem(SnapLedgerDestination.Budgets.route, R.drawable.pie, null, "Budget"),
            BottomNavItem(SnapLedgerDestination.History.route, R.drawable.list, null, "History"),
            BottomNavItem("settings", R.drawable.settings, null, "Settings")
        )
    }

    val activeGreen = remember { Color(0xFF00A86B) }
    val inactiveGray = remember { Color(0xFF757575) }
    val bgColor = remember { Color(0xFFF8F9FA) }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val isFabVisible = currentRoute != SnapLedgerDestination.Scan.route &&
            currentRoute != SnapLedgerDestination.AddExpense.route &&
            currentRoute != SnapLedgerDestination.AddIncome.route &&
            currentRoute != "review" &&
            currentRoute != SnapLedgerDestination.AiInsights.route

    var isFabMenuExpanded by remember { mutableStateOf(false) }
    val fabRotation by animateFloatAsState(targetValue = if (isFabMenuExpanded) 45f else 0f, label = "fabRotate")

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
                            navItems.take(2).forEach { item ->
                                NavBarItem(
                                    item = item,
                                    isSelected = currentRoute == item.route,
                                    activeColor = activeGreen,
                                    inactiveColor = inactiveGray,
                                    onClick = {
                                        isFabMenuExpanded = false
                                        navController.navigate(item.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = item.route != SnapLedgerDestination.Home.route
                                        }
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(72.dp))

                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            navItems.drop(2).forEach { item ->
                                NavBarItem(
                                    item = item,
                                    isSelected = currentRoute == item.route,
                                    activeColor = activeGreen,
                                    inactiveColor = inactiveGray,
                                    onClick = {
                                        isFabMenuExpanded = false
                                        navController.navigate(item.route) {
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
            }
        },
        floatingActionButton = {
            if (isFabVisible) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.offset(y = 80.dp)
                ) {
                    AnimatedVisibility(
                        visible = isFabMenuExpanded,
                        enter = slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = tween(durationMillis = 200, delayMillis = 100)
                        ) + fadeIn(tween(200, delayMillis = 100)),
                        exit = slideOutVertically(
                            targetOffsetY = { it },
                            animationSpec = tween(durationMillis = 200, delayMillis = 0)
                        ) + fadeOut(tween(200, delayMillis = 0))
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FabMenuItem(
                                iconResId = R.drawable.camera,
                                label = "Scan receipt",
                                onClick = {
                                    isFabMenuExpanded = false
                                    navController.navigate(SnapLedgerDestination.Scan.route)
                                }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }

                    AnimatedVisibility(
                        visible = isFabMenuExpanded,
                        enter = slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = tween(durationMillis = 200, delayMillis = 50)
                        ) + fadeIn(tween(200, delayMillis = 50)),
                        exit = slideOutVertically(
                            targetOffsetY = { it },
                            animationSpec = tween(durationMillis = 200, delayMillis = 50)
                        ) + fadeOut(tween(200, delayMillis = 50))
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FabMenuItem(
                                iconResId = android.R.drawable.ic_input_add,
                                label = "Add expense",
                                onClick = {
                                    isFabMenuExpanded = false
                                    navController.navigate(SnapLedgerDestination.AddExpense.route)
                                }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }

                    AnimatedVisibility(
                        visible = isFabMenuExpanded,
                        enter = slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = tween(durationMillis = 200, delayMillis = 0)
                        ) + fadeIn(tween(200, delayMillis = 0)),
                        exit = slideOutVertically(
                            targetOffsetY = { it },
                            animationSpec = tween(durationMillis = 200, delayMillis = 100)
                        ) + fadeOut(tween(200, delayMillis = 100))
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FabMenuItem(
                                iconResId = R.drawable.hand_coins,
                                label = "Add income",
                                onClick = {
                                    isFabMenuExpanded = false
                                    navController.navigate(SnapLedgerDestination.AddIncome.route)
                                }
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    FloatingActionButton(
                        onClick = { isFabMenuExpanded = !isFabMenuExpanded },
                        shape = CircleShape,
                        containerColor = activeGreen,
                        contentColor = Color.White,
                        elevation = FloatingActionButtonDefaults.elevation(
                            defaultElevation = 6.dp,
                            pressedElevation = 12.dp
                        ),
                        modifier = Modifier.size(80.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = android.R.drawable.ic_input_add),
                            contentDescription = "Expand Menu",
                            modifier = Modifier
                                .size(28.dp)
                                .rotate(fabRotation)
                        )
                    }
                }
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        containerColor = bgColor
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            SnapLedgerNavHost(
                navController = navController,
                profile = profile,
                onDisplayNameChange = onDisplayNameChange,
                modifier = Modifier.padding(innerPadding)
            )

            if (isFabMenuExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { isFabMenuExpanded = false }
                )
            }
        }
    }
}

@Composable
private fun NavBarItem(
    item: BottomNavItem,
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
        if (item.iconResId != null) {
            Icon(
                painter = painterResource(id = item.iconResId),
                contentDescription = item.label,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
        } else if (item.iconVector != null) {
            Icon(
                imageVector = item.iconVector,
                contentDescription = item.label,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = item.label,
            color = color,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun FabMenuItem(
    iconResId: Int,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .shadow(4.dp, RoundedCornerShape(12.dp))
            .background(Color.White, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconResId),
            contentDescription = label,
            tint = Color(0xFF00A86B),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            color = Color(0xFF1F1F1F),
            fontSize = 14.sp,
        )
    }
}
