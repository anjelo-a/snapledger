package com.snapledger.feature.dashboard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapledger.R
import com.snapledger.core.categories.transactionCategoryOptionForName
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

data class NotificationSummary(
    val id: String,
    val title: String,
    val message: String,
    val date: String,
    val isUnread: Boolean
)

data class DashboardUiState(
    val userName: String = "",
    val monthlyBudget: BudgetSummary = BudgetSummary(),
    val weeklyBudget: BudgetSummary = BudgetSummary(),
    val alert: AlertSummary? = null,
    val trend: TrendSummary = TrendSummary(),
    val insight: String? = null,
    val insightActionTip: String? = null,
    val isInsightLoading: Boolean = false,
    val categories: List<CategorySummary> = emptyList(),
    val recentActivity: List<TransactionSummary> = emptyList(),
    val notifications: List<NotificationSummary> = emptyList(),
    val isLoading: Boolean = false
) {
    val hasUnreadNotifications: Boolean get() = notifications.any { it.isUnread }
}

enum class DashboardBudgetPeriod { WEEKLY, MONTHLY }

data class BudgetSummary(
    val limit: Double = 0.0,
    val spent: Double = 0.0,
    val totalIncome: Double = 0.0,
    val totalExpenses: Double = 0.0
) {
    val remaining: Double get() = limit - spent
    val percentageUsed: Float get() = if (limit > 0) (spent / limit).toFloat() else 0f
}

data class AlertSummary(
    val message: String,
    val actionText: String
)

data class TrendSummary(
    val percentageChange: Double = 0.0,
    val isUp: Boolean = true,
    val period: String = "Last 4 weeks",
    val dataPoints: List<Double> = emptyList()
)

data class CategorySummary(
    val name: String,
    val amount: Double,
    val percentage: Float
)

private data class CategoryChartStyle(
    val color: Color,
)

data class TransactionSummary(
    val id: String,
    val title: String,
    val category: String,
    val date: String,
    val amount: Double,
    val isIncome: Boolean
)

private val phFormatter = NumberFormat.getCurrencyInstance(Locale("en", "PH"))
fun formatCurrency(amount: Double): String = phFormatter.format(amount).replace("₱", "PHP ")

private val categoryChartPalette = listOf(
    Color(0xFF00A86B),
    Color(0xFF4CAF50),
    Color(0xFF009688),
    Color(0xFF5C6BC0),
    Color(0xFFF57F17),
    Color(0xFFE91E63),
    Color(0xFF8E24AA),
    Color(0xFF78909C),
)

fun Modifier.noRippleClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
fun DashboardScreen(
    state: DashboardUiState = DashboardUiState(),
    onDisplayNameChange: (String) -> Unit = {},
    onManageBudgetClick: () -> Unit = {},
    onSeeAllActivityClick: () -> Unit = {},
    onMarkAllNotificationsAsRead: () -> Unit = {}
) {
    var isEditingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf(state.userName) }
    var showNotifications by remember { mutableStateOf(false) }
    var selectedBudgetPeriod by remember { mutableStateOf(DashboardBudgetPeriod.MONTHLY) }
    val selectedBudget by remember(state.weeklyBudget, state.monthlyBudget, selectedBudgetPeriod) {
        derivedStateOf {
            when (selectedBudgetPeriod) {
                DashboardBudgetPeriod.WEEKLY -> state.weeklyBudget
                DashboardBudgetPeriod.MONTHLY -> state.monthlyBudget
            }
        }
    }

    LaunchedEffect(state.userName) {
        nameDraft = state.userName
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 88.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                BudgetCard(
                    budget = selectedBudget,
                    selectedPeriod = selectedBudgetPeriod,
                    onPeriodChanged = { selectedBudgetPeriod = it },
                    onManageClick = onManageBudgetClick
                )
            }

            if (state.alert != null) {
                item { AlertCard(alert = state.alert) }
            }

            item { TrendCard(trend = state.trend) }

            item {
                InsightCard(
                    insightText = state.insight,
                    actionTip = state.insightActionTip,
                    isLoading = state.isInsightLoading,
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                CategoriesSection(categories = state.categories)
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                RecentActivitySection(
                    transactions = state.recentActivity,
                    onSeeAllClick = onSeeAllActivityClick
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF8F9FA))
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp)
            ) {
                GreetingTexts(
                    userName = state.userName,
                    onNameClick = { isEditingName = true },
                    modifier = Modifier.align(Alignment.CenterStart)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFFF8F9FA), Color(0x00F8F9FA))
                        )
                    )
            )
        }

        if (showNotifications) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f))
                    .noRippleClickable { showNotifications = false }
            )
        }

        NotificationBell(
            hasUnread = state.hasUnreadNotifications,
            onClick = { showNotifications = !showNotifications },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 24.dp, end = 24.dp)
        )

        AnimatedVisibility(
            visible = showNotifications,
            enter = slideInVertically(
                initialOffsetY = { -it / 4 },
                animationSpec = tween(durationMillis = 250)
            ) + fadeIn(tween(durationMillis = 250)),
            exit = slideOutVertically(
                targetOffsetY = { -it / 4 },
                animationSpec = tween(durationMillis = 200)
            ) + fadeOut(tween(durationMillis = 200)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 76.dp)
        ) {
            NotificationsPopover(
                notifications = state.notifications,
                onMarkAllAsRead = onMarkAllNotificationsAsRead,
                onDismiss = { showNotifications = false }
            )
        }
    }

    if (isEditingName) {
        AlertDialog(
            onDismissRequest = { isEditingName = false },
            title = { Text("Edit name") },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it.take(80) },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDisplayNameChange(nameDraft)
                        isEditingName = false
                    },
                    enabled = nameDraft.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { isEditingName = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun GreetingTexts(userName: String, onNameClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = "Good afternoon,", color = Color(0xFF757575), fontSize = 14.sp)
        Text(
            text = userName.ifBlank { "User" },
            color = Color(0xFF1F1F1F),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onNameClick),
        )
    }
}

@Composable
private fun NotificationBell(hasUnread: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0xFFE0E0E0))
            .noRippleClickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.bell),
            contentDescription = "Notifications",
            modifier = Modifier.size(22.dp),
            tint = Color(0xFF1F1F1F)
        )

        if (hasUnread) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 10.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF5252))
                    .border(1.dp, Color(0xFFE0E0E0), CircleShape)
            )
        }
    }
}

@Composable
private fun NotificationsPopover(
    notifications: List<NotificationSummary>,
    onMarkAllAsRead: () -> Unit,
    onDismiss: () -> Unit
) {
    val hasUnread = notifications.any { it.isUnread }
    val iconResId = if (hasUnread) R.drawable.mail_close else R.drawable.mail_open
    val iconTint = if (hasUnread) Color(0xFF00C875) else Color(0xFFBDBDBD)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Notifications", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F1F1F))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .noRippleClickable { if (hasUnread) onMarkAllAsRead() }
                        .padding(4.dp)
                ) {
                    Icon(
                        painter = painterResource(id = iconResId),
                        contentDescription = "Mark as read",
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            HorizontalDivider(color = Color(0xFFEEEEEE), modifier = Modifier.padding(top = 12.dp))

            if (notifications.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.bell),
                        contentDescription = "No notifications",
                        tint = Color(0xFFE0E0E0),
                        modifier = Modifier
                            .size(48.dp)
                            .padding(bottom = 8.dp)
                    )
                    Text(
                        text = "You're all caught up!",
                        fontSize = 14.sp,
                        color = Color(0xFF757575),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Check back later for alerts.",
                        fontSize = 12.sp,
                        color = Color(0xFF9E9E9E),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    itemsIndexed(notifications) { index, notif ->
                        NotificationItemRow(notif)
                        if (index < notifications.lastIndex) {
                            HorizontalDivider(color = Color(0xFFF5F5F5), modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationItemRow(notification: NotificationSummary) {
    val bgColor = if (notification.isUnread) Color(0xFFE8F5E9).copy(alpha = 0.4f) else Color.Transparent
    val titleColor = if (notification.isUnread) Color(0xFF1F1F1F) else Color(0xFF757575)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .padding(top = 6.dp)
                .clip(CircleShape)
                .background(if (notification.isUnread) Color(0xFF00C875) else Color.Transparent)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(text = notification.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = titleColor)
            Text(text = notification.message, fontSize = 13.sp, color = Color(0xFF757575), modifier = Modifier.padding(top = 2.dp))
            Text(text = notification.date, fontSize = 11.sp, color = Color(0xFFBDBDBD), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun BudgetCard(
    budget: BudgetSummary,
    selectedPeriod: DashboardBudgetPeriod,
    onPeriodChanged: (DashboardBudgetPeriod) -> Unit,
    onManageClick: () -> Unit
) {
    var previousPeriod by remember { mutableStateOf(selectedPeriod) }
    val tiltAngle = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val totalBalance = budget.totalIncome - budget.totalExpenses

    LaunchedEffect(selectedPeriod) {
        if (previousPeriod != selectedPeriod) {
            val targetAngle = if (selectedPeriod == DashboardBudgetPeriod.MONTHLY) 3f else -3f
            tiltAngle.animateTo(targetAngle, animationSpec = tween(150))
            tiltAngle.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
            previousPeriod = selectedPeriod
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationZ = tiltAngle.value
            },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF00A86B)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedPeriod == DashboardBudgetPeriod.WEEKLY) "Weekly budget" else "Monthly budget",
                    color = Color.White,
                    fontSize = 14.sp
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .noRippleClickable { onManageClick() }
                        .padding(4.dp)
                ) {
                    Text(text = "Manage", color = Color.White, fontSize = 14.sp)
                    Icon(
                        painter = painterResource(id = R.drawable.chevron_right),
                        contentDescription = "Manage",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .padding(top = 14.dp)
                    .height(34.dp)
                    .width(180.dp)
                    .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
                    .padding(3.dp)
            ) {
                val tabWidth = 87.dp
                val offsetX by animateDpAsState(
                    targetValue = if (selectedPeriod == DashboardBudgetPeriod.WEEKLY) 0.dp else tabWidth,
                    animationSpec = tween(durationMillis = 200),
                    label = "sliderAnim"
                )

                Box(
                    modifier = Modifier
                        .offset(x = offsetX)
                        .width(tabWidth)
                        .fillMaxHeight()
                        .background(Color.White, RoundedCornerShape(15.dp))
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(15.dp))
                            .noRippleClickable {
                                if (selectedPeriod != DashboardBudgetPeriod.WEEKLY) {
                                    onPeriodChanged(DashboardBudgetPeriod.WEEKLY)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Weekly",
                            color = if (offsetX < (tabWidth / 2)) Color(0xFF00A86B) else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(15.dp))
                            .noRippleClickable {
                                if (selectedPeriod != DashboardBudgetPeriod.MONTHLY) {
                                    onPeriodChanged(DashboardBudgetPeriod.MONTHLY)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Monthly",
                            color = if (offsetX >= (tabWidth / 2)) Color(0xFF00A86B) else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = formatCurrency(budget.remaining),
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "left",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
                )
            }

            LinearProgressIndicator(
                progress = { budget.percentageUsed },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .padding(top = 16.dp),
                color = Color.White,
                trackColor = Color(0x4DFFFFFF),
                strokeCap = StrokeCap.Round
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Spent ${formatCurrency(budget.spent)} of ${formatCurrency(budget.limit)}",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Text(
                    text = "${(budget.percentageUsed * 100).toInt()}% used",
                    color = Color.White,
                    fontSize = 12.sp
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Total balance",
                    color = Color(0xFFE0F2F1),
                    fontSize = 12.sp,
                )
                Text(
                    text = formatCurrency(totalBalance),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BudgetMetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Income",
                    value = formatCurrency(budget.totalIncome),
                )
                BudgetMetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Expenses",
                    value = formatCurrency(budget.totalExpenses),
                )
            }
        }
    }
}

@Composable
private fun BudgetMetricCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Card(
        modifier = modifier
            .height(88.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2EBA86))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, color = Color(0xFFE0F2F1), fontSize = 12.sp)
            Text(
                text = value,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun AlertCard(alert: AlertSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.triangle_alert),
                contentDescription = "Alert",
                tint = Color(0xFFF57F17),
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = alert.message,
                color = Color(0xFF795548),
                fontSize = 14.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            )
            Text(
                text = alert.actionText,
                color = Color(0xFFD84315),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TrendCard(trend: TrendSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 22.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Spending trend", color = Color(0xFF1F1F1F), fontSize = 18.sp)
                    Text(
                        text = trend.period,
                        color = Color(0xFF9E9E9E),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = if (trend.isUp) R.drawable.trending_up else R.drawable.trending_down),
                        contentDescription = "Trend",
                        tint = if (trend.isUp) Color(0xFFD32F2F) else Color(0xFF00A86B),
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 4.dp)
                    )
                    Text(
                        text = "${String.format(Locale.getDefault(), "%.2f", trend.percentageChange)}% vs prev",
                        color = if (trend.isUp) Color(0xFFD32F2F) else Color(0xFF00A86B),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            val lineColor = if (trend.isUp) Color(0xFFD32F2F) else Color(0xFF00A86B)
            val gradientColors = listOf(lineColor.copy(alpha = 0.3f), Color.Transparent)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(top = 24.dp)
            ) {
                if (trend.dataPoints.isEmpty()) return@Canvas

                val maxPoint = trend.dataPoints.maxOrNull() ?: 1.0
                val minPoint = trend.dataPoints.minOrNull() ?: 0.0
                val range = (maxPoint - minPoint).takeIf { it > 0 } ?: 1.0
                val yMultiplier = size.height / range
                val xStep = size.width / (trend.dataPoints.size - 1).coerceAtLeast(1)

                val path = Path()
                val fillPath = Path()

                trend.dataPoints.forEachIndexed { index, value ->
                    val x = index * xStep
                    val y = size.height - ((value - minPoint) * yMultiplier).toFloat()

                    if (index == 0) {
                        path.moveTo(x, y)
                        fillPath.moveTo(x, size.height)
                        fillPath.lineTo(x, y)
                    } else {
                        path.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }

                fillPath.lineTo(size.width, size.height)
                fillPath.close()

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(colors = gradientColors),
                    style = Fill
                )

                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            if (trend.dataPoints.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "W1", color = Color(0xFF9E9E9E), fontSize = 12.sp)
                    Text(text = "W2", color = Color(0xFF9E9E9E), fontSize = 12.sp)
                    Text(text = "W3", color = Color(0xFF9E9E9E), fontSize = 12.sp)
                    Text(text = "W4", color = Color(0xFF9E9E9E), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun InsightCard(
    insightText: String?,
    actionTip: String?,
    isLoading: Boolean,
) {
    val isDataEmpty = insightText.isNullOrBlank() && !isLoading
    val displayColor = if (isDataEmpty) Color(0xFF9E9E9E) else Color(0xFF1F1F1F)
    val cardColor = if (isDataEmpty) Color.White else Color(0xFFF5F3FF)
    val iconBgColor = if (isDataEmpty) Color(0xFFF5F5F5) else Color.White

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDataEmpty) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 18.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconBgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.astroid_single),
                    contentDescription = "Insight",
                    tint = if (isDataEmpty) Color(0xFFBDBDBD) else Color(0xFF7F22FE),
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = "Insight",
                    color = if (isDataEmpty) Color(0xFF9E9E9E) else Color(0xFF7F22FE),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        isLoading -> "Generating your latest spending insight..."
                        insightText != null -> insightText
                        else -> "No insights yet. Keep tracking your spending to unlock AI analysis."
                    },
                    color = displayColor,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (!actionTip.isNullOrBlank()) {
                    Text(
                        text = actionTip,
                        color = Color(0xFF5E35B1),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (!isDataEmpty && !isLoading) {
                    Text(
                        text = "Powered by AI",
                        color = Color(0xFF9E9E9E),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoriesSection(categories: List<CategorySummary>) {
    val categoryStyles = remember(categories) {
        categories.associate { category ->
            category.name to CategoryChartStyle(
                color = categoryColorForName(category.name)
            )
        }
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Categories", color = Color(0xFF1F1F1F), fontSize = 16.sp)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            if (categories.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(Color(0xFFF5F5F5), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "0%",
                            color = Color(0xFFBDBDBD),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "No spending data available yet.",
                        color = Color(0xFF9E9E9E),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    CategoryDonutChart(
                        categories = categories,
                        categoryStyles = categoryStyles,
                        modifier = Modifier.size(80.dp)
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                    ) {
                        categories.forEach { category ->
                            CategoryItem(
                                name = category.name,
                                amount = formatCurrency(category.amount),
                                percentage = category.percentage,
                                color = categoryStyles.getValue(category.name).color
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryDonutChart(
    categories: List<CategorySummary>,
    categoryStyles: Map<String, CategoryChartStyle>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension * 0.20f
            val gapDegrees = 2.5f
            var startAngle = -90f

            categories.forEach { category ->
                val rawSweep = (category.percentage / 100f) * 360f
                val sweepAngle = (rawSweep - gapDegrees).coerceAtLeast(0f)
                drawArc(
                    color = categoryStyles.getValue(category.name).color,
                    startAngle = startAngle,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                )
                startAngle += rawSweep
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "100%",
                color = Color(0xFF1F1F1F),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "spent",
                color = Color(0xFF9E9E9E),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun CategoryItem(name: String, amount: String, percentage: Float, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color = color, shape = CircleShape)
            )
            Text(
                text = name,
                color = Color(0xFF424242),
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        Text(
            text = "${percentage.toInt()}% · $amount",
            color = Color(0xFF757575),
            fontSize = 13.sp
        )
    }
}

private fun categoryColorForName(name: String): Color {
    val sharedColor = transactionCategoryOptionForName(name).tintColor
    return if (sharedColor != Color(0xFF9E9E9E) || name.equals("Other", ignoreCase = true)) {
        sharedColor
    } else {
        val normalized = name.trim().ifBlank { "other" }
        categoryChartPalette[normalized.hashCode().mod(categoryChartPalette.size)]
    }
}

@Composable
private fun RecentActivitySection(
    transactions: List<TransactionSummary>,
    onSeeAllClick: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Recent activity", color = Color(0xFF1F1F1F), fontSize = 16.sp)
            if (transactions.isNotEmpty()) {
                Text(
                    text = "See all",
                    color = Color(0xFF00A86B),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSeeAllClick() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            if (transactions.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.receipt),
                        contentDescription = "No receipts",
                        tint = Color(0xFFE0E0E0),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "No recent activity.",
                        color = Color(0xFF757575),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        text = "Tap the + button to add your first transaction!",
                        color = Color(0xFF9E9E9E),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            } else {
                Column {
                    transactions.forEachIndexed { index, transaction ->
                        TransactionItem(transaction = transaction)
                        if (index < transactions.lastIndex) {
                            HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 1.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionItem(transaction: TransactionSummary) {
    val iconData = remember(transaction.category) {
        transactionCategoryOptionForName(transaction.category).let { option ->
            Pair(option.iconResId, option.tintColor)
        }
    }

    val iconResId = iconData.first
    val iconTint = iconData.second
    val iconBgColor = remember(iconTint) { iconTint.copy(alpha = 0.1f) }

    val amountPrefix = if (transaction.isIncome) "+" else "-"
    val amountColor = if (transaction.isIncome) Color(0xFF00A86B) else Color(0xFF1F1F1F)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(iconBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = iconResId),
                contentDescription = transaction.category,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(text = transaction.title, color = Color(0xFF1F1F1F), fontSize = 16.sp)
            Text(text = "${transaction.category} · ${transaction.date}", color = Color(0xFF757575), fontSize = 12.sp)
        }

        Text(
            text = "$amountPrefix${formatCurrency(transaction.amount)}",
            color = amountColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}