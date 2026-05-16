package com.snapledger.feature.dashboard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snapledger.R
import java.text.NumberFormat
import java.util.Locale

// --- Backend-Ready Data Models ---

data class NotificationSummary(
    val id: String,
    val title: String,
    val message: String,
    val date: String,
    val isUnread: Boolean
)

data class DashboardUiState(
    val userName: String = "",
    val budget: BudgetSummary = BudgetSummary(),
    val alert: AlertSummary? = null,
    val trend: TrendSummary = TrendSummary(),
    val insight: String? = null,
    val categories: List<CategorySummary> = emptyList(),
    val recentActivity: List<TransactionSummary> = emptyList(),
    // Completely empty to trigger default states
    val notifications: List<NotificationSummary> = emptyList(),
    val isLoading: Boolean = false
) {
    val hasUnreadNotifications: Boolean get() = notifications.any { it.isUnread }
}

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
    val period: String = "Last 4 weeks"
)

data class CategorySummary(
    val name: String,
    val amount: Double,
    val percentage: Float
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
fun formatCurrency(amount: Double): String = phFormatter.format(amount)

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
    onMarkAllNotificationsAsRead: () -> Unit = {}
) {
    var isEditingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf(state.userName) }
    var showNotifications by remember { mutableStateOf(false) }

    LaunchedEffect(state.userName) {
        nameDraft = state.userName
    }

    // Top Level Box allows controlling Z-Index of overlays and buttons
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
    ) {
        // SCROLLABLE CONTENT
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Top padding ensures content starts below the sticky header (24dp + 40dp + 16dp)
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 88.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                BudgetCard(budget = state.budget, onManageClick = onManageBudgetClick)
            }

            if (state.alert != null) {
                item { AlertCard(alert = state.alert) }
            }

            item { TrendCard(trend = state.trend) }

            item { InsightCard(insightText = state.insight) }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                CategoriesSection(categories = state.categories)
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                RecentActivitySection(transactions = state.recentActivity)
            }
        }

        // STICKY GREETING HEADER WITH FADING GRADIENT
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
            // Fading gradient edge
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp) //fade size
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFFF8F9FA), Color(0x00F8F9FA))
                        )
                    )
            )
        }

        // DARK OVERLAY
        if (showNotifications) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f))
                    .noRippleClickable { showNotifications = false }
            )
        }

        // NOTIFICATION BELL
        NotificationBell(
            hasUnread = state.hasUnreadNotifications,
            onClick = { showNotifications = !showNotifications },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 24.dp, end = 24.dp)
        )

        // NOTIFICATION POPOVER
        AnimatedVisibility(
            visible = showNotifications,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                // Padded exactly to align under the bell
                .padding(start = 24.dp, end = 24.dp, top = 76.dp)
        ) {
            NotificationsPopover(
                notifications = state.notifications,
                onMarkAllAsRead = onMarkAllNotificationsAsRead,
                onDismiss = { showNotifications = false }
            )
        }
    }

    // Name Editing Dialog
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

        // Only shows when hasUnread is true
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

                // Mail button toggle
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

// ... BudgetCard, AlertCard, TrendCard, InsightCard, CategoriesSection, RecentActivitySection remaining components untouched.

@Composable
private fun BudgetCard(
    budget: BudgetSummary,
    onManageClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                Text(text = "Monthly budget", color = Color.White, fontSize = 14.sp)

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
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2EBA86))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Income", color = Color(0xFFE0F2F1), fontSize = 12.sp)
                        Text(
                            text = formatCurrency(budget.totalIncome),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2EBA86))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "Expenses", color = Color(0xFFE0F2F1), fontSize = 12.sp)
                        Text(
                            text = formatCurrency(budget.totalExpenses),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
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
        Column(modifier = Modifier.padding(16.dp)) {
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
                        text = "${trend.percentageChange}% vs prev",
                        color = if (trend.isUp) Color(0xFFD32F2F) else Color(0xFF00A86B),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .padding(top = 24.dp)
                    .background(Color(0xFFE8F5E9))
            )
        }
    }
}

@Composable
private fun InsightCard(insightText: String?) {
    val isDataEmpty = insightText.isNullOrBlank()
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
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconBgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.astroid),
                    contentDescription = "Insight",
                    tint = if (isDataEmpty) Color(0xFFBDBDBD) else Color(0xFF7F22FE),
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = "Insight",
                    color = if (isDataEmpty) Color(0xFF9E9E9E) else Color(0xFF7F22FE),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = insightText ?: "No insights yet. Keep tracking your spending to unlock AI analysis.",
                    color = displayColor,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (!isDataEmpty) {
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
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Categories", color = Color(0xFF1F1F1F), fontSize = 16.sp)
            if (categories.isNotEmpty()) {
                Text(text = "Manage", color = Color(0xFF00A86B), fontSize = 14.sp)
            }
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
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .background(Color(0xFFEEEEEE), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "100%",
                            color = Color(0xFF1F1F1F),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(
                        modifier = Modifier
                            .padding(start = 24.dp)
                            .weight(1f)
                    ) {
                        categories.forEach { category ->
                            CategoryItem(name = category.name, amount = formatCurrency(category.amount))
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryItem(name: String, amount: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "• $name", color = Color(0xFF424242), fontSize = 14.sp)
        Text(text = amount, color = Color(0xFF757575), fontSize = 14.sp)
    }
}

@Composable
private fun RecentActivitySection(transactions: List<TransactionSummary>) {
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
                Text(text = "See all", color = Color(0xFF00A86B), fontSize = 14.sp)
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
        when (transaction.category.lowercase()) {
            "food" -> Pair(R.drawable.utensils, Color(0xFF4CAF50))
            "transport" -> Pair(R.drawable.car, Color(0xFF009688))
            "income", "salary" -> Pair(R.drawable.hand_coins, Color(0xFF00A86B))
            "shopping" -> Pair(R.drawable.shopping_basket, Color(0xFFF57F17))
            "entertainment" -> Pair(R.drawable.film, Color(0xFF673AB7))
            "bills" -> Pair(R.drawable.banknote_arrow_up, Color(0xFFD32F2F))
            "health" -> Pair(R.drawable.heart_pulse, Color(0xFFE91E63))
            else -> Pair(R.drawable.receipt, Color(0xFF757575))
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